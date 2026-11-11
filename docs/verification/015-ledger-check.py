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
assert counts == {'DONE': 15, 'TODO': 7}, counts
assert ledger['015'] == ('011, 012', 'DONE')
assert all(ledger[dependency][1] == 'DONE' for dependency in ledger['015'][0].split(', '))
previous = rows(subprocess.check_output(['git', 'show', 'HEAD:docs/INTRUVIA_BACKLOG_STATUS.md'], text=True))
assert [number for number in ledger if ledger[number] != previous[number]] == ['015']
eligible = [number for number, (dependencies, status) in ledger.items() if status == 'TODO'
            and all(ledger[dependency][1] == 'DONE' for dependency in dependencies.split(', '))]
assert min(eligible) == '016'
assert 'Updated: 2026-11-06' in text and 'no active task' in text.splitlines()[2]
assert '- Current task: **None**; task **015' in text
assert '- Next task: **016' in text
assert '- Completed: **15 / 22**. TODO: **7**. IN_PROGRESS: **0**. BLOCKED: **0**.' in text
print('PASS: 22 rows; DONE 15, TODO 7, IN_PROGRESS 0, BLOCKED 0; only task 015 changed; next 016; summary/date consistent.')
