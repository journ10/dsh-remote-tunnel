import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('artifacts', SCRIPTS / 'export_artifacts.py')
artifacts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(artifacts)


class ArtifactTest(unittest.TestCase):
    def test_exports_only_selected_files_and_portable_hash_manifest(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(artifacts, 'ROOT', Path(temp)):
            root = Path(temp)
            apk = root / 'app-release.apk'
            apk.write_bytes(b'apk-fixture')
            (root / 'private.env').write_text('not-for-export')
            artifacts.export('android', 'release', [apk], '0.36.0')
            output = root / 'dist/0.36.0/android'
            manifest = json.loads((output / 'manifest.json').read_text())
            entry = manifest['artifacts'][0]
            self.assertEqual('dsh-remote-0.36.0-android-release.apk', entry['file'])
            self.assertEqual(hashlib.sha256(b'apk-fixture').hexdigest(), entry['sha256'])
            self.assertNotIn(temp, (output / 'manifest.json').read_text())
            self.assertEqual({entry['file'], 'SHA256SUMS', 'manifest.json'}, {p.name for p in output.iterdir()})

    def test_unsigned_identity_and_reject_unsupported_or_duplicate_inputs(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(artifacts, 'ROOT', Path(temp)):
            root = Path(temp)
            apk = root / 'app-release-unsigned.apk'
            apk.write_bytes(b'unsigned')
            artifacts.export('android', 'release', [apk], '1.0.0')
            self.assertTrue((root / 'dist/1.0.0/android/dsh-remote-1.0.0-android-release-unsigned.apk').exists())
            with self.assertRaises(ValueError):
                artifacts.export('android', 'release', [apk, apk], '1.0.0')
            with self.assertRaises(ValueError):
                artifacts.export('ios', 'release', [apk], '1.0.0')
            with self.assertRaises(ValueError):
                artifacts.export('android', 'release', [apk], '../../private')
