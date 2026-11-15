"""Run the built app in isolation and require its actual bundled self-test to pass."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

if len(sys.argv) != 2:
    raise SystemExit('Usage: python scripts/smoke_desktop.py path-to-executable')
exe = Path(sys.argv[1]).resolve()
if not exe.is_file():
    raise SystemExit(f'Exécutable manquant: {exe}')
env = os.environ.copy()
env.pop('PYTHONPATH', None)
env.pop('PYTHONHOME', None)
with tempfile.TemporaryDirectory() as temp:
    report = Path(temp) / 'smoke.json'
    result = subprocess.run([str(exe), '--self-test-file', str(report)], cwd=temp, env=env, timeout=120)
    if not report.is_file():
        raise SystemExit(f'L’exécutable n’a pas écrit son rapport (code {result.returncode})')
    content = json.loads(report.read_text(encoding='utf-8'))
    print(json.dumps(content, indent=2))
    if result.returncode != 0 or content.get('ok') is not True:
        raise SystemExit('Échec du test intégré du programme PC')
