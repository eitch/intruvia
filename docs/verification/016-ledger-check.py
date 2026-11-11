# SPDX-License-Identifier: AGPL-3.0-only
from pathlib import Path
from collections import Counter
import re
import subprocess

path = 'docs/INTRUVIA_BACKLOG_STATUS.md'
text = Path(path).read_text()
def rows(content):
    return {match[0]: (match[1], match[2]) for match in re.findall(
        r'^\| (\d{3}) \| [^|]+ \| ([^|]+) \| (TODO|IN_PROGRESS|DONE|BLOCKED) \|', content, re.M)}
current = rows(text)
original = rows(subprocess.check_output(['git', 'show', 'HEAD:' + path], text=True))
assert len(current) == 22
assert [task for task in current if current[task][1] != original[task][1]] == ['016']
assert Counter(status for _, status in current.values()) == {'DONE': 16, 'TODO': 6}
assert current['016'][1] == 'DONE'
for dependency in current['016'][0].split(','):
    assert current[dependency.strip()][1] == 'DONE'
eligible = [task for task, (deps, status) in current.items() if status == 'TODO' and
            all(current[dependency.strip()][1] == 'DONE' for dependency in deps.split(','))]
assert min(eligible) == '017'
assert 'Updated: 2026-11-11' in text
assert '- Completed: **16 / 22**. TODO: **6**. IN_PROGRESS: **0**. BLOCKED: **0**.' in text
assert '- Current task: **None**' in text
assert '- Next task: **017' in text
print('PASS: 22 rows; only 016 changed status; DONE 16/TODO 6/IN_PROGRESS 0/BLOCKED 0; dependencies, summary/date and next eligible 017.')
