# SPDX-License-Identifier: AGPL-3.0-only
from pathlib import Path
from collections import Counter
import re
import subprocess

path = Path('docs/INTRUVIA_BACKLOG_STATUS.md')
text = path.read_text()
pattern = r'^\| (\d{3}) \| ([^|]+) \| ([^|]+) \| (TODO|IN_PROGRESS|BLOCKED|DONE) \|'
rows = re.findall(pattern, text, re.M)
old = re.findall(pattern, subprocess.check_output(['git', 'show', 'HEAD:' + str(path)], text=True), re.M)
assert len(rows) == len(old) == 22
assert [row[0] for row in rows] == [f'{n:03}' for n in range(1, 23)]
assert [(a[0], a[3], b[3]) for a, b in zip(old, rows) if a[3] != b[3]] == [('019', 'TODO', 'DONE')]
counts = Counter(row[3] for row in rows)
assert counts == {'DONE': 19, 'TODO': 3}
statuses = {row[0]: row[3] for row in rows}
eligible = [row[0] for row in rows if row[3] == 'TODO' and
			all(statuses[dep] == 'DONE' for dep in re.findall(r'\d{3}', row[2]))]
assert eligible[0] == '020'
assert 'Updated: 2026-11-12' in text
assert '- Current task: **None**; task **019' in text
assert '- Next task: **020' in text
assert 'Completed: **19 / 22**. TODO: **3**. IN_PROGRESS: **0**. BLOCKED: **0**.' in text
print('PASS: 22 rows; only 019 TODO -> DONE; DONE 19/TODO 3/IN_PROGRESS 0/BLOCKED 0')
print('PASS: current/date/counts and lowest eligible next task 020 agree; no other task started')
