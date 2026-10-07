# SPDX-License-Identifier: AGPL-3.0-only
"""Run from the repository root after task 012 clean verification."""
from pathlib import Path
from collections import Counter
import hashlib
import subprocess
import xml.etree.ElementTree as ET
import zipfile

root = Path.cwd()
cache = Path('/home/eitch/.m2/repository/li/strolch')
framework = Path('/home/eitch/src/git/atx-dev/strolch')
version = ET.parse(framework / 'pom.xml').find('{http://maven.apache.org/POM/4.0.0}version').text
assert version == '2.8.0-SNAPSHOT'
print('Actual Strolch version:', version)
print('Framework HEAD:', subprocess.check_output(['git', '-C', str(framework), 'rev-parse', 'HEAD'], text=True).strip())
for jar in sorted((root / 'intruvia-app/target/intruvia/lib').glob('strolch-*.jar')):
    module = jar.name.removesuffix('-' + version + '.jar')
    cached = cache / module / version / jar.name
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    assert digest == hashlib.sha256(cached.read_bytes()).hexdigest()
    print('Packaged/cache match:', jar.name, digest)
for module, package, name in [
    ('strolch-agent', 'li/strolch/search', 'ResourceSearch'),
    ('strolch-agent', 'li/strolch/search', 'StrolchSearch'),
    ('strolch-agent', 'li/strolch/search', 'SearchResult'),
    ('strolch-agent', 'li/strolch/service/api', 'AbstractService'),
    ('strolch-agent', 'li/strolch/persistence/api', 'AbstractTransaction'),
    ('strolch-agent', 'li/strolch/service/api', 'DefaultServiceHandler'),
]:
    with zipfile.ZipFile(cache / module / version / f'{module}-{version}.jar') as jar:
        bytecode = jar.read(f'{package}/{name}.class')
    target = framework / module / 'target/classes' / package / f'{name}.class'
    assert bytecode == target.read_bytes()
    source = framework / module / 'src/main/java' / package / f'{name}.java'
    print('Installed/target class match:', name, hashlib.sha256(bytecode).hexdigest())
    print('Source identity:', source, hashlib.sha256(source.read_bytes()).hexdigest())
print('Local snapshot only; no timestamped artifact relabeling/equivalence claimed.')
files = list((root / 'intruvia-core/src/main/java/li/intruvia/core/read').glob('*.java'))
files += list((root / 'intruvia-rest/src/main/java/li/intruvia/rest/read').glob('*.java'))
files += [root / path for path in [
    'intruvia-core/src/main/java/li/intruvia/core/persistence/EventStreamLock.java',
    'intruvia-core/src/main/java/li/intruvia/core/persistence/EventRepository.java',
    'intruvia-core/src/main/java/li/intruvia/core/ingest/IngestService.java',
    'intruvia-rest/src/main/java/li/intruvia/rest/IntruviaRestApplication.java',
    'intruvia-app/src/test/java/li/intruvia/app/EventReadIT.java',
    'intruvia-app/src/test/java/li/intruvia/app/ViewerSessionIT.java',
    'intruvia-app/src/test/java/li/intruvia/app/ApplicationIT.java',
]]
for path in files:
    for line_no, line in enumerate(path.read_text().splitlines(), 1):
        assert len(line) < 160, (path, line_no, len(line))
        assert not line.startswith(' ') or line.lstrip().startswith('*'), (path, line_no, 'space indentation')
print('Changed/new Java tabs and line lengths passed.')
expected = {('intruvia-core', 'surefire'): 20, ('intruvia-rest', 'surefire'): 12,
            ('intruvia-app', 'surefire'): 2, ('intruvia-app', 'failsafe'): 22}
for (module, kind), count in expected.items():
    totals = Counter()
    for path in (root / module / 'target' / (kind + '-reports')).glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for key in ('tests', 'failures', 'errors', 'skipped'):
            totals[key] += int(suite.attrib[key])
    assert totals['tests'] == count and not any(totals[k] for k in ('failures', 'errors', 'skipped')), totals
    print(module, kind, dict(totals))
for filename in ['viewer.properties.example', 'PrivilegeRoles.xml.example', 'StrolchConfiguration.xml']:
    source = root / 'intruvia-app/src/main/runtime/config' / filename
    packaged = root / 'intruvia-app/target/intruvia/runtime/config' / filename
    assert source.read_bytes() == packaged.read_bytes()
print('Viewer configuration, role and session component packaged identically.')
status = subprocess.check_output(['git', 'status', '--short'], text=True)
assert 'AD intruvia-core/src/main/java/li/intruvia/core/auth/MachineCredentials.java' in status
print('Unrelated staged/deleted MachineCredentials.java preserved.')
assert not subprocess.check_output(['git', 'diff', '--name-only', '--', 'intruvia-web'], text=True).strip()
print('No frontend changes: no new browser credential storage or UI behavior.')

import json
schema = json.loads((root / 'docs/api/v1.schema.json').read_text())
for name, fields in [('snapshot', ['items', 'cursor', 'minAfter', 'truncated']),
                     ('historyPage', ['items', 'nextAfter', 'hasMore', 'through'])]:
    definition = schema['$defs'][name]
    assert definition['required'] == fields
    assert definition['properties']['items']['maxItems'] == 1000
    assert definition['properties']['items']['items']['$ref'] == '#/$defs/event'
print('Read schema references/bounds checked; not a general JSON Schema validation run.')
with zipfile.ZipFile(root / 'intruvia-app/target/intruvia/lib/intruvia-core-0.0.1.jar') as jar:
    assert 'li/intruvia/core/read/EventReadService.class' in jar.namelist()
    assert 'li/intruvia/core/read/EventReadSearch.class' in jar.namelist()
with zipfile.ZipFile(root / 'intruvia-app/target/intruvia/lib/intruvia-rest-0.0.1.jar') as jar:
    assert 'li/intruvia/rest/read/EventsResource.class' in jar.namelist()
print('Read service/search/HTTP resource are in the assembled distribution.')
