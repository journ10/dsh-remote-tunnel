#!/usr/bin/env python3
"""Audit candidate source or exact Git blobs; never print matched secret values."""
import argparse
import ipaddress
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
PUBLIC_ROOTS = {'.github', 'docs', 'mobile', 'plugin', 'scripts', 'server'}
PUBLIC_FILES = {'.editorconfig', '.gitignore', 'CHANGELOG.md', 'CONTRIBUTING.md',
                'LICENSE', 'Makefile', 'README.md', 'SECURITY.md', 'VERSION'}
PRIVATE_PARTS = {'.local', '.tooling', '.git', 'node_modules', 'build', '.gradle',
                 '.kotlin', '.idea', 'dist', 'backups', 'data', 'xcuserdata',
                 'oh_modules', '.hvigor', '.cxx'}
SECRET_SUFFIXES = {'.jks', '.keystore', '.pem', '.key', '.p12', '.pfx', '.cer',
                   '.p7b', '.mobileprovision', '.apk', '.aab', '.ipa', '.hap',
                   '.so', '.dmg', '.zip', '.tar', '.gz', '.log', '.bak-local'}
PUBLIC_HOSTS = {
    'github.com', 'raw.githubusercontent.com', 'avatars.githubusercontent.com',
    'objects.githubusercontent.com', 'developers.openai.com',
    'maven.eazytec-cloud.com', 'mirrors.tencent.com', 'services.gradle.org',
    'downloads.gradle.org', 'plugins.gradle.org', 'repo.maven.apache.org',
    'repo1.maven.org', 'dl.google.com', 'developer.android.com',
    'developer.huawei.com', 'ohpm.openharmony.cn', 'repo.huaweicloud.com',
    'registry.npmjs.org', 'www.w3.org', 'schemas.android.com',
    'schemas.openxmlformats.org', 'www.apache.org', 'www.apple.com',
    'kotlinlang.org', 'docs.gradle.org', 'issues.gradle.org', 'spdx.org',
    'get.docker.com', 'ifconfig.me', 'api.ipify.org', 'registry.npmmirror.com',
    'relay', 'public_host', '.',
}
SECRET_PATTERNS = [
    re.compile(r'-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----'),
    re.compile(r'\b(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]{20,}'),
    re.compile(r'\bAKIA[0-9A-Z]{16}\b'),
    re.compile(r'\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}'),
]
URL = re.compile(r'(?:https?|wss?|ssh)://[^\s<>\x22\x27`\\]+')
EMAIL = re.compile(r'[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\.[A-Za-z]{2,})')
HOME = re.compile(r'/(?:Users|home)/([A-Za-z0-9._-]+)')
IP = re.compile(r'(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])')
SIGNING_SECRET = re.compile(r"""(?:storePassword|keyPassword)["']?\s*[:=]\s*["']([^"']+)["']""")


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def safe_host(host):
    return (host in PUBLIC_HOSTS or host == 'localhost' or
            host == 'example.com' or host.endswith(('.example.com', '.example', '.test', '.local', '.invalid')) or
            any(c in host for c in '${}<>') or host in {'127.0.0.1', '0.0.0.0', '::1', '…', '...'})


def inspect(path, data, private_values=()):
    findings = []
    p = Path(path)
    if ((p.parts[0] not in PUBLIC_ROOTS and path not in PUBLIC_FILES) or
            any(part in PRIVATE_PARTS for part in p.parts) or
            p.suffix in SECRET_SUFFIXES or p.name in {'local.properties', 'keystore.properties'} or
            (p.name.startswith('.env') and p.name != '.env.example')):
        findings.append((0, 'private-or-generated-file'))
    if len(data) > 5_000_000:
        findings.append((0, 'oversized-source-file'))
    if path == 'mobile/gradle/wrapper/gradle-wrapper.jar':
        return findings
    if data.startswith(b'\x89PNG\r\n\x1a\n'):
        # Only curated fixture previews and application icons belong in source.
        if not (path.startswith('docs/images/kmp-ui-refresh/') or '/resources/' in path or '/res/' in path):
            findings.append((0, 'unreviewed-image'))
        position = 8
        while position + 12 <= len(data):
            length = int.from_bytes(data[position:position + 4], 'big')
            kind = data[position + 4:position + 8]
            if kind in {b'tEXt', b'zTXt', b'iTXt', b'eXIf'}:
                findings.append((0, 'unreviewed-image-metadata'))
            position += length + 12
        return findings
    try:
        content = data.decode('utf-8')
    except UnicodeDecodeError:
        return findings + [(0, 'unexpected-binary')]
    for number, line in enumerate(content.splitlines(), 1):
        if any(pat.search(line) for pat in SECRET_PATTERNS):
            findings.append((number, 'credential-format'))
        if any(value and value in line for value in private_values):
            findings.append((number, 'private-deployment-value'))
        if any(match.group(1) not in {'CHANGE_ME', '<encrypted-password>', '<password>'}
               for match in SIGNING_SECRET.finditer(line)):
            findings.append((number, 'embedded-signing-password'))
        if any(m.group(1) not in {'you', 'user', 'example', 'runner'} for m in HOME.finditer(line)):
            findings.append((number, 'personal-home-path'))
        if any(not (m.group(1).lower() in {'example.com', 'users.noreply.github.com'} or m.group(1).lower().endswith('.example.com')) for m in EMAIL.finditer(line)):
            findings.append((number, 'personal-email'))
        for m in IP.finditer(line):
            try:
                address = ipaddress.ip_address(m.group())
            except ValueError:
                continue
            if not (address.is_loopback or m.group().startswith('0.') or
                    any(address in ipaddress.ip_network(net) for net in ('192.0.2.0/24', '198.51.100.0/24', '203.0.113.0/24'))):
                findings.append((number, 'non-example-ip'))
        for m in URL.finditer(line):
            try:
                parsed = urlsplit(m.group())
                host = parsed.hostname
            except ValueError:
                continue  # Incomplete interpolated/template URLs.
            if parsed.username and not any(c in parsed.username for c in '${}'):
                findings.append((number, 'url-userinfo'))
            if host and not safe_host(host):
                findings.append((number, 'unreviewed-url-host'))
    return sorted(set(findings))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--staged', action='store_true', help='scan exact full index, not worktree bytes')
    mode.add_argument('--revision', help='scan an exact commit/tree (e.g. HEAD)')
    args = parser.parse_args()
    private_path = ROOT / '.local/security/private-values.json'
    private_values = json.loads(private_path.read_text()) if private_path.exists() else []
    if args.revision:
        paths = git('ls-tree', '-rz', '--name-only', args.revision).decode().split('\0')
    else:
        options = ['--cached'] if args.staged else ['--cached', '--others', '--exclude-standard']
        paths = git('ls-files', '-z', *options).decode().split('\0')
    errors = []
    count = 0
    for path in sorted(set(filter(None, paths))):
        file = ROOT / path
        if args.staged or args.revision:
            data = git('show', f'{args.revision or ""}:{path}')
            mode_data = git('ls-tree', args.revision, '--', path) if args.revision else git('ls-files', '-s', '--', path)
            if mode_data.startswith(b'120000'):
                errors.append((path, 0, 'source-symlink'))
        else:
            if not file.exists():
                continue
            if file.is_symlink():
                errors.append((path, 0, 'source-symlink'))
                continue
            data = file.read_bytes()
        count += 1
        errors.extend((path, line, kind) for line, kind in inspect(path, data, private_values))
    for path, line, kind in errors:
        print(f'{path}:{line}: {kind}')
    print(f'Publication audit: {count} files, {len(errors)} findings. Matched values are never printed.')
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
