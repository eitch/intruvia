# SPDX-License-Identifier: AGPL-3.0-only
from pathlib import Path
from collections import Counter
import re

text = Path('docs/INTRUVIA_BACKLOG_STATUS.md').read_text()
rows = {}
for line in text.splitlines():
    if re.match(r'^\| \d{3} \|', line):
        parts = [part.strip() for part in line.split('|')]
        rows[parts[1]] = (parts[3], parts[4])
assert len(rows) == 22
assert Counter(status for _, status in rows.values()) == {'DONE': 12, 'TODO': 10}
assert rows['012'][1] == 'DONE'
assert all(rows[number][1] == 'DONE' for number in rows['012'][0].split(', '))
eligible = [number for number, (deps, status) in rows.items()
            if status == 'TODO' and all(rows[dep][1] == 'DONE' for dep in deps.split(', '))]
assert min(eligible) == '013'
assert 'Updated: 2026-10-07' in text
assert '- Current task: **None**' in text
assert '- Completed: **12 / 22**. TODO: **10**. IN_PROGRESS: **0**. BLOCKED: **0**.' in text
assert '- Next task: **013' in text
print('22 rows; DONE 12/TODO 10/IN_PROGRESS 0/BLOCKED 0; task 012 DONE; next eligible 013.')
print('Date, counts, current/next summary and dependencies match ledger. No other task started.')
