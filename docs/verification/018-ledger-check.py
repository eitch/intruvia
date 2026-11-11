# SPDX-License-Identifier: AGPL-3.0-only
from collections import Counter
from pathlib import Path
import re
import subprocess

path = Path('docs/INTRUVIA_BACKLOG_STATUS.md')
text = path.read_text()
pattern = r'^\| (\d{3}) \| ([^|]+) \| ([^|]+) \| (TODO|IN_PROGRESS|BLOCKED|DONE) \|'
rows = re.findall(pattern, text, re.M)
baseline = re.findall(pattern, subprocess.check_output(['git', 'show', 'HEAD:' + str(path)], text=True), re.M)
assert len(rows) == len(baseline) == 22
states = {row[0]: row[3] for row in rows}
assert Counter(states.values()) == {'DONE': 18, 'TODO': 4}
assert [(old[0], old[3], new[3]) for old, new in zip(baseline, rows) if old[3] != new[3]] == [('018', 'TODO', 'DONE')]
assert states['010'] == 'DONE'
eligible = [row[0] for row in rows if row[3] == 'TODO' and all(states[dep] == 'DONE' for dep in re.findall(r'\d{3}', row[2]))]
assert eligible[0] == '019'
assert 'Updated: 2026-11-11' in text
assert '- Current task: **None**' in text
assert '- Next task: **019 — Complete runtime configuration and diagnostics (TODO)**' in text
assert 'Completed: **18 / 22**. TODO: **4**. IN_PROGRESS: **0**. BLOCKED: **0**.' in text
print('PASS: 22 rows; only 018 TODO -> DONE; dependency 010 DONE; DONE 18/TODO 4/IN_PROGRESS 0/BLOCKED 0.')
print('PASS: current none, next eligible 019, summary/date consistent; no other task started.')
