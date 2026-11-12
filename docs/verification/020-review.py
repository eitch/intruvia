# SPDX-License-Identifier: AGPL-3.0-only
from pathlib import Path
from hashlib import sha256
from zipfile import ZipFile
import subprocess
import xml.etree.ElementTree as ET

root = Path.cwd()
dist = root / 'intruvia-app/target/intruvia'
report_files = []
for module in ('intruvia-core', 'intruvia-rest', 'intruvia-app'):
	report_files += list((root / module / 'target/surefire-reports').glob('TEST-*.xml'))
	report_files += list((root / module / 'target/failsafe-reports').glob('TEST-*.xml'))
totals = [0, 0, 0, 0]
for report in report_files:
	suite = ET.parse(report).getroot()
	for index, key in enumerate(('tests', 'failures', 'errors', 'skipped')):
		totals[index] += int(suite.attrib.get(key, 0))
assert totals == [87, 0, 0, 0], totals
print('JUnit reports: tests/failures/errors/skipped', totals)
for module in ('intruvia-core', 'intruvia-rest', 'intruvia-app'):
	jar = dist / (f'{module}-0.0.1.jar' if module == 'intruvia-app' else f'lib/{module}-0.0.1.jar')
	with ZipFile(jar) as archive:
		for file in (root / module / 'target/classes').rglob('*'):
			if file.is_file():
				assert archive.read(file.relative_to(root / module / 'target/classes').as_posix()) == file.read_bytes(), file
	print('Packaged class/resource bytes equal target/classes:', module)
for file in (root / 'intruvia-app/src/main/runtime').rglob('*'):
	if file.is_file():
		assert file.read_bytes() == (dist / 'runtime' / file.relative_to(root / 'intruvia-app/src/main/runtime')).read_bytes()
print('All packaged runtime files equal source; no personal GeoIP path in template')
assert '<databasePath></databasePath>' in (dist / 'runtime/config/StrolchConfiguration.xml').read_text()
with ZipFile(dist / 'intruvia-app-0.0.1.jar') as archive:
	assert archive.read('logback.xml') == (root / 'intruvia-app/src/main/resources/logback.xml').read_bytes()
framework = []
for jar in sorted((dist / 'lib').glob('strolch-*.jar')):
	cache = Path('/home/eitch/.m2/repository/li/strolch') / jar.name.removesuffix('-2.8.0-SNAPSHOT.jar') / '2.8.0-SNAPSHOT' / jar.name
	assert cache.is_file() and cache.read_bytes() == jar.read_bytes(), jar
	with ZipFile(jar) as archive:
		versions = [n for n in archive.namelist() if n.startswith('META-INF/maven/li.strolch/') and n.endswith('/pom.properties')]
		assert len(versions) == 1
		assert 'version=2.8.0-SNAPSHOT' in archive.read(versions[0]).decode()
	framework.append(jar)
	print('Framework/cache byte identity:', jar.name, sha256(jar.read_bytes()).hexdigest())
assert len(framework) == 6
source = Path('/home/eitch/src/git/atx-dev/strolch')
assert subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain'], text=True) == ''
print('Clean framework source HEAD:', subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip())
print('Actual framework version: 2.8.0-SNAPSHOT; no timestamp-baseline equivalence claimed')
changed = subprocess.check_output(['git', 'diff', '--name-only'], text=True).splitlines()
changed += subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], text=True).splitlines()
for name in changed:
	if name.endswith('.java'):
		lines = Path(name).read_text().splitlines()
		assert len(lines) < 1000, name
		assert all(len(line) <= 160 and (not line.startswith(' ') or line.startswith(' *')) for line in lines), name
		if name not in subprocess.check_output(['git', 'ls-files'], text=True).splitlines():
			assert lines[0] == '// SPDX-License-Identifier: AGPL-3.0-only', name
print('Changed Java files: tabs, <=160 columns, <1000 lines; new source SPDX headers checked')
for path, files in [('020-artifacts.sha256', [dist / 'intruvia-app-0.0.1.jar', *sorted((dist / 'lib').glob('intruvia-*.jar')),
					  dist / 'runtime/config/StrolchConfiguration.xml', dist / 'runtime/config/PrivilegeRoles.xml.example']),
					('020-strolch.sha256', framework)]:
	(root / 'docs/verification' / path).write_text(''.join(f'{sha256(file.read_bytes()).hexdigest()}  {file.relative_to(root)}\n' for file in files))
print('Application/runtime and framework SHA-256 manifests generated')

with ZipFile(dist / 'lib/intruvia-web-0.0.1.jar') as archive:
	for file in (root / 'intruvia-web/src/main/resources').rglob('*'):
		if file.is_file():
			assert archive.read(file.relative_to(root / 'intruvia-web/src/main/resources').as_posix()) == file.read_bytes(), file
print('All packaged frontend bytes equal source')
for name in ('020-capacity.cjs', '020-run-browser.sh', '020-run-regressions.sh'):
	lines = (root / 'docs/verification' / name).read_text().splitlines()
	assert len(lines) < 500 and all(len(line) < 160 and not line.startswith(' ') for line in lines), name
print('Optional harness size/tabs/line lengths pass')
