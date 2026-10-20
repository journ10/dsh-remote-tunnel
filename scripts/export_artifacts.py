#!/usr/bin/env python3
"""Copy explicit deliverables to dist/<version>/<platform>/ with SHA-256 manifests."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
EXTENSIONS = {'android': {'.apk', '.aab'}, 'ios': {'.ipa', '.zip'},
              'harmony': {'.hap'}, 'desktop': {'.dmg', '.msi', '.deb'}}


def export(platform, variant, files, version):
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', version):
        raise ValueError('Invalid release version')
    if not files:
        raise ValueError('No artifacts found; build the selected target first')
    plan = []
    for source in files:
        source = Path(source).resolve()
        if not source.is_file() or source.suffix not in EXTENSIONS[platform]:
            raise ValueError(f'Unsupported or missing {platform} artifact: {source.name}')
        suffix = '-unsigned' if 'unsigned' in source.stem else ''
        name = f'dsh-remote-{version}-{platform}-{variant}{suffix}{source.suffix}'
        if any(name == item[1] for item in plan):
            raise ValueError('More than one input resolves to the same artifact name')
        plan.append((source, name))
    destination = ROOT / 'dist' / version / platform
    destination.mkdir(parents=True, exist_ok=True)
    for source, name in plan:
        target = destination / name
        if source != target:
            shutil.copy2(source, target)
    entries = []
    for path in sorted(destination.iterdir()):
        if path.suffix not in EXTENSIONS[platform]:
            continue
        digest = hashlib.sha256()
        with path.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(chunk)
        entries.append({'file': path.name, 'bytes': path.stat().st_size, 'sha256': digest.hexdigest()})
    (destination / 'SHA256SUMS').write_text(''.join(f'{e["sha256"]}  {e["file"]}\n' for e in entries))
    # No absolute build paths, developer names, credentials, or host metadata.
    (destination / 'manifest.json').write_text(json.dumps(
        {'version': version, 'platform': platform, 'artifacts': entries}, indent=2) + '\n')
    print(f'Exported {len(plan)} artifact(s) to {destination.relative_to(ROOT)}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('platform', choices=EXTENSIONS)
    parser.add_argument('--variant', choices=['debug', 'release', 'simulator'], default='release')
    parser.add_argument('--file', action='append', default=[], help='explicit input artifact; repeatable')
    args = parser.parse_args()
    files = [Path(p) for p in args.file]
    if not files and args.platform == 'android':
        outputs = ROOT / 'mobile/androidApp/build/outputs'
        files = list((outputs / 'apk' / args.variant).glob('*.apk'))
        files += list((outputs / 'bundle' / args.variant).glob('*.aab'))
    export(args.platform, args.variant, files, (ROOT / 'VERSION').read_text().strip())


if __name__ == '__main__':
    main()
