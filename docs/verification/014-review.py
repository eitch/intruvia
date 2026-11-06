# SPDX-License-Identifier: AGPL-3.0-only
"""Review packaged identities and the actual Maven reports; run from repository root."""
from pathlib import Path
import hashlib
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


framework = Path('/home/eitch/src/git/atx-dev/strolch')
cache = Path('/home/eitch/.m2/repository/li/strolch')
print('Framework HEAD:', subprocess.check_output(['git', '-C', str(framework), 'rev-parse', 'HEAD'], text=True).strip())
print('Framework status:', subprocess.check_output(['git', '-C', str(framework), 'status', '--short'], text=True).strip() or 'clean')
print('Requested version override: 2.8.0-SNAPSHOT; NOT historical timestamped baseline 2.8.0-20260927.120822-2')
for binary in sorted(Path('intruvia-app/target/intruvia/lib').glob('strolch-*.jar')):
    module = binary.name.removesuffix('-2.8.0-SNAPSHOT.jar')
    binary_hash = digest(binary.read_bytes())
    matches = [p for p in (cache / module / '2.8.0-SNAPSHOT').glob('*.jar')
               if not p.name.endswith('-sources.jar') and digest(p.read_bytes()) == binary_hash]
    assert matches, binary
    print('Packaged SHA256:', binary_hash, binary)
    print('Matching cache binaries:', ', '.join(str(p) for p in matches))

for module, entry in [
    ('strolch-agent', 'li/strolch/runtime/sessions/DefaultStrolchSessionHandler.java'),
    ('strolch-agent', 'li/strolch/service/api/AbstractService.java'),
    ('strolch-agent', 'li/strolch/service/api/DefaultServiceHandler.java'),
    ('strolch-privilege', 'li/strolch/privilege/handler/DefaultPrivilegeHandler.java')
]:
    source = framework / module / 'src/main/java' / entry
    published = cache / module / '2.8.0-SNAPSHOT' / f'{module}-2.8.0-20261105.152255-22-sources.jar'
    with zipfile.ZipFile(published) as archive:
        assert archive.read(entry) == source.read_bytes(), source
    print('Checkout equals published source:', digest(source.read_bytes()), source)

reports = []
for module in ['intruvia-core', 'intruvia-rest', 'intruvia-app']:
    for directory in ['surefire-reports', 'failsafe-reports']:
        for report in sorted(Path(module, 'target', directory).glob('TEST-*.xml')):
            suite = ET.parse(report).getroot()
            counts = tuple(int(suite.get(key)) for key in ['tests', 'failures', 'errors', 'skipped'])
            assert counts[1:] == (0, 0, 0), (report, counts)
            print('Test report:', report, counts)
            reports.append(counts[0])
assert sum(reports) == 75, sum(reports)
print('Total: 75 tests, zero failures/errors/skips (40 unit/HTTP, 35 integration).')

changed = subprocess.check_output(['git', 'diff', '--name-only'], text=True).splitlines()
new = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], text=True).splitlines()
for filename in changed + new:
    if filename.endswith('.java'):
        path = Path(filename)
        for number, line in enumerate(path.read_text().splitlines(), 1):
            assert len(line) <= 160, (path, number, len(line))
            assert not line.startswith(' '), (path, number, 'space indentation')
        assert path.read_text().startswith('// SPDX-License-Identifier: AGPL-3.0-only'), path
assert not any(p.startswith('intruvia-web/') for p in changed + new)
print('Changed Java tabs/line lengths/SPDX and unchanged frontend: PASS')
config = Path('intruvia-app/src/main/runtime/config/StrolchConfiguration.xml').read_text()
before = subprocess.check_output(['git', 'show', 'HEAD:intruvia-app/src/main/runtime/config/StrolchConfiguration.xml'], text=True)
import re
assert re.search(r'<databasePath>.*?</databasePath>', config).group() == re.search(r'<databasePath>.*?</databasePath>', before).group()
print('Existing operator GeoIP path preserved: PASS')
with zipfile.ZipFile('intruvia-app/target/intruvia/lib/intruvia-rest-0.0.1.jar') as archive:
    assert 'li/intruvia/rest/stream/EventStreamServlet.class' in archive.namelist()
    assert 'li/intruvia/rest/stream/EventStreamComponent.class' in archive.namelist()
packaged = Path('intruvia-app/target/intruvia/runtime/config/StrolchConfiguration.xml').read_text()
assert '<name>EventStream</name>' in packaged and '<name>StreamNotifications</name>' in packaged
assert Path('intruvia-app/target/intruvia/lib/jetty-ee10-websocket-jakarta-server-12.0.16.jar').is_file()
print('Endpoint/dispatcher, pinned transport and component configuration packaged: PASS')
subprocess.run(['git', 'diff', '--check'], check=True)
print('git diff --check: PASS')
