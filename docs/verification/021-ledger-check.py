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
assert [(a[0], a[3], b[3]) for a, b in zip(old, rows) if a[3] != b[3]] == [('021', 'TODO', 'DONE')]
counts = Counter(row[3] for row in rows)
assert counts == {'DONE': 21, 'TODO': 1}
statuses = {row[0]: row[3] for row in rows}
eligible = [row[0] for row in rows if row[3] == 'TODO' and
			all(statuses[dep] == 'DONE' for dep in re.findall(r'\d{3}', row[2]))]
assert eligible[0] == '022'
assert 'Updated: 2026-11-12' in text
assert '- Current task: **None**; task **021' in text
assert '- Next task: **022' in text
assert 'Completed: **21 / 22**. TODO: **1**. IN_PROGRESS: **0**. BLOCKED: **0**.' in text
print('PASS: 22 rows; only 021 TODO -> DONE; DONE 21/TODO 1/IN_PROGRESS 0/BLOCKED 0')
print('PASS: current/date/counts and lowest eligible next task 022 agree; no other task started')
