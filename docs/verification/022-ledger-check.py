# SPDX-License-Identifier: AGPL-3.0-only
from pathlib import Path
from collections import Counter
import re
import subprocess

path = Path('docs/INTRUVIA_BACKLOG_STATUS.md')
text = path.read_text()
pattern = r'^\| (\d{3}) \| ([^|]+) \| ([^|]+) \| (TODO|IN_PROGRESS|BLOCKED|DONE) \| (.*) \|$'
rows = re.findall(pattern, text, re.M)
old = re.findall(pattern, subprocess.check_output(['git', 'show', 'HEAD:' + str(path)], text=True), re.M)
assert len(rows) == len(old) == 22
assert [row[0] for row in rows] == [f'{n:03}' for n in range(1, 23)]
assert [(a[0], a[3], b[3]) for a, b in zip(old, rows) if a[3] != b[3]] == [('022', 'TODO', 'DONE')]
assert Counter(row[3] for row in rows) == {'DONE': 22}
for row in rows:
	links = re.findall(r'\]\(([^)]+)\)', row[4])
	assert links, row[0]
	for link in links:
		assert (Path('docs') / link).is_file(), (row[0], link)
assert '- Current task: **None**' in text and '- Next task: **None**' in text
assert 'Completed: **22 / 22**. TODO: **0**. IN_PROGRESS: **0**. BLOCKED: **0**.' in text
assert 'Updated: 2026-11-12' in text
assert not subprocess.check_output(['git', 'diff', 'HEAD', '--name-only', '--', '*pom.xml', 'intruvia-*/src', 'integrations'], text=True)
print('PASS: all22 DONE with existing evidence; only022 TODO -> DONE; counts/current/next/date consistent')
print('PASS: no production source/POM/integration changes; no subsequent backlog task')
