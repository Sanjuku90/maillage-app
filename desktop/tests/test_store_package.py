import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts' / 'prepare_msix.py'
spec = importlib.util.spec_from_file_location('store_manifest', SCRIPT)
store = importlib.util.module_from_spec(spec)
spec.loader.exec_module(store)


class StorePackageTests(unittest.TestCase):
    def test_manifest_escapes_values_and_has_required_capabilities(self):
        tree = store.create_manifest('12345Nejdj.Maillage', 'CN=example', 'Maillage & Friends', 'Nejdj', '1.2.3.0')
        serialized = ET.tostring(tree.getroot(), encoding='unicode')
        parsed = ET.fromstring(serialized)
        self.assertIn('Maillage &amp; Friends', serialized)
        identity = parsed.find(store.q(store.NS, 'Identity'))
        self.assertEqual(identity.attrib['Publisher'], 'CN=example')
        self.assertEqual(identity.attrib['ProcessorArchitecture'], 'x64')
        caps = parsed.find(store.q(store.NS, 'Capabilities'))
        self.assertIsNotNone(caps.find(f"{store.q(store.RES, 'Capability')}[@Name='runFullTrust']"))
        self.assertIsNotNone(caps.find(f"{store.q(store.NS, 'DeviceCapability')}[@Name='bluetooth']"))

    def test_invalid_or_missing_identity_does_not_write_stage(self):
        for values in [('', 'CN=x', 'Maillage', 'Nejdj', '1.0.0.0'),
                       ('good.name', 'wrong', 'Maillage', 'Nejdj', '1.0.0.0'),
                       ('good.name', 'CN=x', 'Maillage', 'Nejdj', '1.0.0.1')]:
            with self.assertRaises(ValueError): store.create_manifest(*values)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = root / 'source'; stage = root / 'stage'; source.mkdir()
            (source / 'Maillage-PC.exe').write_bytes(b'fixture')
            result = subprocess.run([sys.executable, str(SCRIPT), '--source', str(source), '--stage', str(stage),
                                     '--identity-name', 'bad space', '--publisher', 'CN=test', '--display-name', 'Maillage',
                                     '--publisher-display', 'Nejdj', '--version', '1.0.0.0'], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(stage.exists())

    def test_existing_stage_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = root / 'source'; stage = root / 'stage'
            source.mkdir(); stage.mkdir()
            (source / 'Maillage-PC.exe').write_bytes(b'fixture')
            (stage / 'sentinel').write_text('keep')
            result = subprocess.run([sys.executable, str(SCRIPT), '--source', str(source), '--stage', str(stage),
                                     '--identity-name', 'good.name', '--publisher', 'CN=test', '--display-name', 'Maillage',
                                     '--publisher-display', 'Nejdj', '--version', '1.0.0.0'], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual((stage / 'sentinel').read_text(), 'keep')

    def test_complete_staging_contains_binaries_and_logo(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = root / 'source'; stage = root / 'stage'; source.mkdir()
            (source / 'Maillage-PC.exe').write_bytes(b'fixture')
            subprocess.run([sys.executable, str(SCRIPT), '--source', str(source), '--stage', str(stage),
                            '--identity-name', 'good.name', '--publisher', 'CN=test', '--display-name', 'Maillage',
                            '--publisher-display', 'Nejdj', '--version', '1.0.0.0'], check=True, capture_output=True)
            self.assertTrue((stage / 'app' / 'Maillage-PC.exe').is_file())
            self.assertTrue((stage / 'Assets' / 'StoreLogo.png').is_file())
            ET.parse(stage / 'AppxManifest.xml')

if __name__ == '__main__': unittest.main()
