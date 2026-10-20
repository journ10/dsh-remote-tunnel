import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('audit', SCRIPTS / 'audit_publication.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class PublicationTest(unittest.TestCase):
    def test_flags_credentials_personal_paths_and_private_fingerprints_without_values(self):
        token = 'ghp_' + 'A' * 36
        personal = '/' + 'Users/' + 'private-owner/project'
        findings = audit.inspect('README.md', f'{token}\n{personal}\nprivate-deployment'.encode(), ['private-deployment'])
        kinds = {item[1] for item in findings}
        self.assertTrue({'credential-format', 'personal-home-path', 'private-deployment-value'} <= kinds)
        self.assertNotIn(token, repr(findings))

    def test_examples_pass_but_deployment_addresses_do_not(self):
        self.assertEqual([], audit.inspect('README.md', b'https://relay.example.com\n203.0.113.10\nyou@example.com'))
        address = 'https://' + 'private-relay.net'
        self.assertIn('unreviewed-url-host', {x[1] for x in audit.inspect('README.md', address.encode())})
        self.assertIn('non-example-ip', {x[1] for x in audit.inspect('README.md', b'10.' + b'1.2.3')})

    def test_rejects_private_and_generated_files_even_when_added_to_git(self):
        for path in ['server/.env', '.local/signing/key.jks', 'mobile/composeApp/build/log.txt', 'android/app.kt']:
            with self.subTest(path=path):
                self.assertTrue(audit.inspect(path, b'not-a-secret'))

    def test_rejects_embedded_signing_material_and_image_metadata(self):
        data = b'{"storePassword":"' + b'Z' * 44 + b'"}'
        self.assertIn('embedded-signing-password', {x[1] for x in audit.inspect('mobile/config.json', data)})
        png = b'\x89PNG\r\n\x1a\n' + (0).to_bytes(4, 'big') + b'eXIf' + b'\0' * 4
        self.assertIn('unreviewed-image-metadata', {x[1] for x in audit.inspect('docs/images/kmp-ui-refresh/a.png', png)})

    def test_staged_audit_reads_index_not_clean_worktree(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'scripts').mkdir()
            script = root / 'scripts/audit_publication.py'
            script.write_bytes((SCRIPTS / 'audit_publication.py').read_bytes())
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            token = 'ghp_' + 'B' * 36
            (root / 'README.md').write_text(token)
            subprocess.run(['git', '-C', str(root), 'add', 'README.md'], check=True)
            (root / 'README.md').write_text('Safe working tree')
            result = subprocess.run([sys.executable, str(script), '--staged'], capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertIn('credential-format', result.stdout)
            self.assertNotIn(token, result.stdout)
