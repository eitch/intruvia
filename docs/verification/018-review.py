# SPDX-License-Identifier: AGPL-3.0-only
"""Review task 018 distribution, reports, source style and local framework identity."""
import hashlib
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DIST = ROOT / 'intruvia-app/target/intruvia'


def sha(path):
	return hashlib.sha256(path.read_bytes()).hexdigest()


for source in sorted((ROOT / 'integrations/fail2ban').glob('*')):
	if source.is_file():
		target = DIST / 'integrations/fail2ban' / source.name
		assert source.read_bytes() == target.read_bytes(), source
		print('Packaged identical:', source.relative_to(ROOT), sha(source))
assert not (DIST / 'integrations/fail2ban/tests').exists()
assert not list((DIST / 'integrations/fail2ban').rglob('__pycache__'))
for source in sorted((ROOT / 'integrations/fail2ban').rglob('*.py')):
	lines = source.read_text().splitlines()
	assert len(lines) < 1000
	assert 'SPDX-License-Identifier: AGPL-3.0-only' in '\n'.join(lines[:2])
	for number, line in enumerate(lines, 1):
		assert len(line) < 160, (source, number, len(line))
		assert not line.startswith(' '), (source, number)
	print('Python tabs/length/SPDX:', source.relative_to(ROOT), len(lines), 'lines')
totals = [0, 0, 0, 0]
for module in ('intruvia-core', 'intruvia-rest', 'intruvia-web', 'intruvia-app'):
	for kind in ('surefire-reports', 'failsafe-reports'):
		for path in (ROOT / module / 'target' / kind).glob('TEST-*.xml'):
			report = ET.parse(path).getroot()
			values = [int(report.get(name, 0)) for name in ('tests', 'failures', 'errors', 'skipped')]
			totals = [left + right for left, right in zip(totals, values)]
assert totals == [75, 0, 0, 0], totals
print('Maven tests/failures/errors/skipped:', totals)
framework = Path('/home/eitch/src/git/atx-dev/strolch')
print('Framework source HEAD:', subprocess.check_output(['git', '-C', str(framework), 'rev-parse', 'HEAD'], text=True).strip())
print('Framework working-tree status:', repr(subprocess.check_output(['git', '-C', str(framework), 'status', '--short'], text=True)))
cache = Path('/home/eitch/.m2/repository/li/strolch')
jars = sorted((DIST / 'lib').glob('strolch-*.jar'))
assert len(jars) == 6
for jar in jars:
	assert jar.name.endswith('-2.8.0-SNAPSHOT.jar'), jar
	artifact = jar.name.removesuffix('-2.8.0-SNAPSHOT.jar')
	installed = cache / artifact / '2.8.0-SNAPSHOT' / jar.name
	assert jar.read_bytes() == installed.read_bytes(), jar
	print('Actual 2.8.0-SNAPSHOT equals installed cache:', jar.name, sha(jar))
print('Historical timestamped baseline is not asserted or relabelled; offline cache sufficed, no framework rebuild.')
archive = Path('/tmp/intruvia-018-fail2ban.tar.gz')
print('Fail2ban 1.1.0 upstream archive:', sha(archive))
print('Upstream URL: https://github.com/fail2ban/fail2ban/archive/refs/tags/1.1.0.tar.gz')
print('Python:', subprocess.check_output(['python3', '--version'], text=True).strip())
print('OpenSSL:', subprocess.check_output(['openssl', 'version'], text=True).strip())
print('PASS')
