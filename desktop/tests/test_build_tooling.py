import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / 'scripts' / 'package_desktop.py'
SMOKE = ROOT / 'scripts' / 'smoke_desktop.py'


class BuildToolTests(unittest.TestCase):
    def test_packages_have_expected_tree_and_hashes(self):
        with tempfile.TemporaryDirectory() as t:
            base = Path(t)
            source = base / 'Maillage-PC'
            source.mkdir()
            (source / 'Maillage-PC.exe').write_bytes(b'windows fixture')
            executable = source / 'Maillage-PC'
            executable.write_bytes(b'linux fixture')
            executable.chmod(0o755)
            for platform in ('Windows', 'Linux'):
                out = base / ('out-' + platform)
                subprocess.run([sys.executable, str(PACKAGE), platform, str(source), str(out)], check=True, capture_output=True)
                archive = out / ('Maillage-PC-Windows.zip' if platform == 'Windows' else 'Maillage-PC-Linux.tar.gz')
                self.assertTrue(archive.is_file())
                checksum = (out / 'SHA256SUMS.txt').read_text().split()[0]
                self.assertEqual(checksum, hashlib.sha256(archive.read_bytes()).hexdigest())
                if platform == 'Windows':
                    with zipfile.ZipFile(archive) as z:
                        self.assertIn('Maillage-PC/Maillage-PC.exe', z.namelist())
                else:
                    with tarfile.open(archive) as tar:
                        entry = tar.getmember('Maillage-PC/Maillage-PC')
                        if os.name != 'nt': self.assertTrue(entry.mode & 0o111)

    @unittest.skipIf(os.name == 'nt', 'POSIX executable fixture')
    def test_smoke_runs_executable_and_checks_its_report(self):
        with tempfile.TemporaryDirectory() as t:
            exe = Path(t) / 'fake-app'
            exe.write_text('#!/usr/bin/env python3\nimport json,sys\nopen(sys.argv[2],"w").write(json.dumps({"ok": True}))\n')
            exe.chmod(0o755)
            subprocess.run([sys.executable, str(SMOKE), str(exe)], check=True, capture_output=True)

if __name__ == '__main__': unittest.main()
