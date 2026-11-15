"""Fail-safe checks only; a real trusted signature must be tested on a Windows runner."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'scripts' / 'sign-windows.ps1'


@unittest.skipUnless(os.name == 'nt' and shutil.which('pwsh'), 'Windows PowerShell required')
class SigningGuardTests(unittest.TestCase):
    def test_missing_secrets_fails_without_creating_cert_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            executable = Path(tmp) / 'app.exe'
            executable.write_bytes(b'test fixture')
            env = os.environ.copy()
            env['RUNNER_TEMP'] = tmp
            env.pop('WINDOWS_CODE_SIGNING_PFX_BASE64', None)
            env.pop('WINDOWS_CODE_SIGNING_PASSWORD', None)
            result = subprocess.run(['pwsh', '-NoProfile', '-File', str(SCRIPT), '-Executable', str(executable)],
                                    env=env, capture_output=True, text=True, timeout=20)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(list(Path(tmp).glob('*.pfx')), [])

    def test_invalid_certificate_fails_and_does_not_publish(self):
        sdk = Path(os.environ.get('ProgramFiles(x86)', 'C:/Program Files (x86)')) / 'Windows Kits' / '10' / 'bin'
        if not sdk.is_dir():
            self.skipTest('Windows SDK absent')
        with tempfile.TemporaryDirectory() as tmp:
            executable = Path(tmp) / 'app.exe'
            executable.write_bytes(b'test fixture')
            env = os.environ.copy()
            env['RUNNER_TEMP'] = tmp
            env['WINDOWS_CODE_SIGNING_PFX_BASE64'] = 'not-valid-base64!'
            env['WINDOWS_CODE_SIGNING_PASSWORD'] = 'fixture-not-a-real-secret'
            result = subprocess.run(['pwsh', '-NoProfile', '-File', str(SCRIPT), '-Executable', str(executable)],
                                    env=env, capture_output=True, text=True, timeout=20)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(list(Path(tmp).glob('*.pfx')), [])
            self.assertEqual(executable.read_bytes(), b'test fixture')

if __name__ == '__main__': unittest.main()
