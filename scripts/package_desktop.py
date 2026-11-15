"""Package a PyInstaller onedir build and preserve Linux executable permissions."""
import hashlib
from pathlib import Path
import shutil
import sys
import tarfile

if len(sys.argv) != 4 or sys.argv[1] not in ('Windows', 'Linux'):
    raise SystemExit('Usage: package_desktop.py Windows|Linux source-dir destination-dir')
platform, source, destination = sys.argv[1], Path(sys.argv[2]).resolve(), Path(sys.argv[3]).resolve()
if not source.is_dir():
    raise SystemExit('Dossier de build PC manquant')
exe_name = 'Maillage-PC.exe' if platform == 'Windows' else 'Maillage-PC'
if not (source / exe_name).is_file():
    raise SystemExit('Exécutable PC manquant')
destination.mkdir(parents=True, exist_ok=True)
if platform == 'Windows':
    archive = Path(shutil.make_archive(str(destination / 'Maillage-PC-Windows'), 'zip', source.parent, source.name))
else:
    archive = destination / 'Maillage-PC-Linux.tar.gz'
    with tarfile.open(archive, 'w:gz') as tf:
        tf.add(source, arcname=source.name)
digest = hashlib.sha256()
with archive.open('rb') as stream:
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(block)
hash_value = digest.hexdigest()
(destination / 'SHA256SUMS.txt').write_text(f'{hash_value}  {archive.name}\n', encoding='utf-8')
print(f'Archive: {archive.name}; SHA-256: {hash_value}')
