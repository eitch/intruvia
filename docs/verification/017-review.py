# SPDX-License-Identifier: AGPL-3.0-only
from pathlib import Path
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
import zipfile

root = Path.cwd()
web = root / 'intruvia-web/src/main/resources/META-INF/resources'
packaged = root / 'intruvia-app/target/intruvia'
with zipfile.ZipFile(packaged / 'lib/intruvia-web-0.0.1.jar') as jar:
    for source in list((web / 'js').rglob('*.js')) + [web / 'viewer.css', web / 'index.html']:
        if '/vendor/' in str(source):
            continue
        assert jar.read('META-INF/resources/' + str(source.relative_to(web))) == source.read_bytes(), source
        lines = source.read_text().splitlines()
        assert len(lines) < 300, source
        assert all(len(line) < 160 for line in lines), source
        if source.suffix == '.js':
            assert all(not line.startswith(' ') for line in lines), source
        print('Packaged bytes and style OK:', source.relative_to(root), len(lines), 'lines')
for jar in sorted((packaged / 'lib').glob('strolch-*.jar')):
    name = jar.name.removesuffix('-2.8.0-SNAPSHOT.jar')
    installed = Path('/home/eitch/.m2/repository/li/strolch') / name / '2.8.0-SNAPSHOT' / jar.name
    assert jar.read_bytes() == installed.read_bytes(), jar
    print('Installed 2.8.0-SNAPSHOT match:', jar.name, hashlib.sha256(jar.read_bytes()).hexdigest())
totals = dict(tests=0, failures=0, errors=0, skipped=0)
for pattern in ['*/target/surefire-reports/TEST-*.xml', '*/target/failsafe-reports/TEST-*.xml']:
    for report in root.glob(pattern):
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.attrib[key])
assert totals == dict(tests=75, failures=0, errors=0, skipped=0), totals
print('Maven reports:', json.dumps(totals))
print('Framework HEAD:', subprocess.check_output(['git', '-C', '/home/eitch/src/git/atx-dev/strolch', 'rev-parse', 'HEAD'], text=True).strip())
print('Framework status:', subprocess.check_output(['git', '-C', '/home/eitch/src/git/atx-dev/strolch', 'status', '--short'], text=True).strip() or 'clean')
print('Hardware:', subprocess.check_output(['lscpu'], text=True))
print('OS:', subprocess.check_output(['uname', '-a'], text=True))

fixture = root / 'intruvia-app/src/test/java/li/intruvia/app/ViewerBrowserFixture.java'
assert all(len(line) < 160 for line in fixture.read_text().splitlines())
assert all(not line.startswith(' ') for line in fixture.read_text().splitlines())
print('Changed Java fixture style OK')
