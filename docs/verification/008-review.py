# SPDX-License-Identifier: AGPL-3.0-only
"""Run from repository root after the task 008 clean verification."""
from pathlib import Path
from collections import Counter
import hashlib
import subprocess
import xml.etree.ElementTree as ET

root = Path.cwd()
cache = Path('/home/eitch/.m2/repository')
framework = Path('/home/eitch/src/git/atx-dev/strolch')
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
version = ET.parse(framework / 'pom.xml').find('m:version', ns).text
assert version == '2.8.0-SNAPSHOT'
print('Framework source version:', version)
print('Framework source HEAD:', subprocess.check_output(['git', '-C', str(framework), 'rev-parse', 'HEAD'], text=True).strip())
for jar in sorted((root / 'intruvia-app/target/intruvia/lib').glob('strolch-*.jar')):
    artifact = jar.name.removesuffix('-' + version + '.jar')
    cached = cache / 'li/strolch' / artifact / version / jar.name
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    assert digest == hashlib.sha256(cached.read_bytes()).hexdigest()
    print('Packaged/cache match:', jar.name, digest)
print('No timestamped-baseline equivalence claimed; no relabeling or rebuild needed.')
for p in sorted((root / 'intruvia-core/src').glob('**/geo/*.java')):
    for number, line in enumerate(p.read_text().splitlines(), 1):
        assert len(line) < 160, (p, number, len(line))
        assert not line.startswith(' ') or line.lstrip().startswith('*'), (p, number, 'space indentation')
print('GeoIP Java length/tab review passed.')
for module, kind in [('intruvia-core', 'surefire'), ('intruvia-rest', 'surefire'),
                     ('intruvia-app', 'surefire'), ('intruvia-app', 'failsafe')]:
    totals = Counter()
    for p in sorted((root / module / 'target' / (kind + '-reports')).glob('TEST-*.xml')):
        suite = ET.parse(p).getroot()
        for key in ('tests', 'failures', 'errors', 'skipped'):
            totals[key] += int(suite.attrib[key])
    assert totals['tests'] > 0 and not any(totals[k] for k in ('failures', 'errors', 'skipped'))
    print(module, kind, dict(totals))
assert not list((root / 'intruvia-core/src').rglob('*.mmdb'))
print('No MMDB fixture checked into source tree.')
status = subprocess.check_output(['git', 'status', '--short'], text=True)
assert 'AD intruvia-core/src/main/java/li/intruvia/core/auth/MachineCredentials.java' in status
print('Pre-existing staged/deleted MachineCredentials.java state preserved.')
