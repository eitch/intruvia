# SPDX-License-Identifier: AGPL-3.0-only
"""Archive identical distribution bytes deterministically; no Maven rebuild claim."""
from pathlib import Path
import gzip
import hashlib
import io
import tarfile

root = Path.cwd()
dist = root / 'intruvia-app/target/intruvia'
assert (dist / 'intruvia-app-0.0.1.jar').is_file()
files = sorted(file for file in dist.rglob('*') if file.is_file())
manifest = ''.join(f'{hashlib.sha256(file.read_bytes()).hexdigest()}  {file.relative_to(root)}\n' for file in files)
(root / 'docs/verification/022-artifacts.sha256').write_text(manifest)
archive = dist.parent / 'intruvia-0.0.1-mvp.tar.gz'

def content():
	buffer = io.BytesIO()
	with gzip.GzipFile(fileobj=buffer, mode='wb', filename='', mtime=0) as compressed:
		with tarfile.open(fileobj=compressed, mode='w', format=tarfile.PAX_FORMAT) as output:
			for file in files:
				info = tarfile.TarInfo('intruvia/' + file.relative_to(dist).as_posix())
				data = file.read_bytes()
				info.size = len(data)
				info.mode = 0o644
				output.addfile(info, io.BytesIO(data))
	return buffer.getvalue()

first = content()
assert first == content(), 'Archive repeatability failed'
archive.write_bytes(first)
with tarfile.open(archive, 'r:gz') as packed:
	assert len(packed.getmembers()) == len(files)
	for file in files:
		assert packed.extractfile('intruvia/' + file.relative_to(dist).as_posix()).read() == file.read_bytes()
digest = hashlib.sha256(first).hexdigest()
(root / 'docs/verification/022-archive.sha256').write_text(f'{digest}  {archive.relative_to(root)}\n')
print(f'PASS: {len(files)} distribution files hashed; two normalized archive passes identical; every archived byte verified')
print(f'Archive: {archive.relative_to(root)} SHA-256 {digest}')
print('Archive modes 0644: run Python helpers via python3; deployment guide explicitly installs executable permissions.')
