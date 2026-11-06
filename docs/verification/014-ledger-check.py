# SPDX-License-Identifier: AGPL-3.0-only
from pathlib import Path
from collections import Counter
import subprocess

text = Path('docs/INTRUVIA_BACKLOG_STATUS.md').read_text()
def rows(document):
    return {parts[1].strip(): (parts[3].strip(), parts[4].strip())
            for line in document.splitlines() if line.startswith('| ')
            and (parts := line.split('|'))[1].strip().isdigit()}
ledger = rows(text)
assert len(ledger) == 22
counts = Counter(status for dependencies, status in ledger.values())
assert counts == {'DONE': 14, 'TODO': 8}, counts
assert ledger['014'] == ('010, 011, 012, 013', 'DONE')
assert all(ledger[dependency][1] == 'DONE' for dependency in ledger['014'][0].split(', '))
previous = rows(subprocess.check_output(['git', 'show', 'HEAD:docs/INTRUVIA_BACKLOG_STATUS.md'], text=True))
assert [number for number in ledger if ledger[number] != previous[number]] == ['014']
eligible = [number for number, (dependencies, status) in ledger.items() if status == 'TODO'
            and all(ledger[dependency][1] == 'DONE' for dependency in dependencies.split(', '))]
assert min(eligible) == '015'
assert 'Updated: 2026-11-06' in text and 'no active task' in text.splitlines()[2]
assert '- Current task: **None**; task **014' in text
assert '- Next task: **015' in text
assert '- Completed: **14 / 22**. TODO: **8**. IN_PROGRESS: **0**. BLOCKED: **0**.' in text
print('PASS: 22 rows; DONE 14, TODO 8, IN_PROGRESS 0, BLOCKED 0; only task 014 changed; next 015; summary/date consistent.')
