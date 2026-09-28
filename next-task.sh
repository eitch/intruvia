#!/usr/bin/env bash
# Copyright (c) 2026 atexxi Systems AG
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
dry_run=false
case "${1:-}" in
	--dry-run) dry_run=true ;;
	-h|--help)
		printf 'Usage: %s [--dry-run]\nRun one Intruvia backlog task using Codex CLI.\n' "$0"
		exit 0 ;;
	'') ;;
	*) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
esac
if (( $# > 1 )); then
	printf 'Expected at most one argument.\n' >&2
	exit 2
fi

for document in AGENTS.md docs/INTRUVIA_SPECIFICATION.md docs/INTRUVIA_BACKLOG.md docs/INTRUVIA_BACKLOG_STATUS.md; do
	if [[ ! -r "$project_dir/$document" ]]; then
		printf 'Required file is not readable: %s\n' "$project_dir/$document" >&2
		exit 1
	fi
done

prompt=$(cat <<'PROMPT'
Execute exactly one Intruvia implementation backlog task in this workspace.

First read AGENTS.md, the Strolch AGENTS.md it references, and all applicable
guidelines (including the Vanilla JavaScript guidelines). If required guidelines
are unavailable, stop and report the missing path.
Read docs/INTRUVIA_SPECIFICATION.md, docs/INTRUVIA_BACKLOG.md, and
docs/INTRUVIA_BACKLOG_STATUS.md before choosing work.

Resume the single IN_PROGRESS task if present. If more than one is IN_PROGRESS,
report the inconsistent ledger and stop. Otherwise choose the lowest-numbered
TODO task whose dependencies are all DONE. Consult the current/next task summary
but verify it against the ledger. Do not silently retry a BLOCKED task. If no task
is eligible, report completion or the blockers and stop without implementing work.

Set the selected task IN_PROGRESS before implementation. Complete its scope and
acceptance checks, following the specification and project guidelines. Preserve
unrelated user changes. Do not commit, push, deploy, or start another backlog task.
Do not expand scope or introduce unapproved dependencies/toolchains. If a required
decision, permission, or dependency is unavailable, record the blocker and stop.

Update the status ledger, counts, current/next task, date, and execution log.
Record exact verification commands/results and file/artifact references. Mark
DONE only if all acceptance criteria passed; never claim unrun checks succeeded.
Record blockers and failures honestly, keeping incomplete work incomplete.
Finish with the task number, changes, verification results, status, and next task.
PROMPT
)

if [[ -n "${CODEX_BIN:-}" ]]; then
	codex_bin=$(command -v -- "$CODEX_BIN") || {
		printf 'CODEX_BIN is not an executable: %s\n' "$CODEX_BIN" >&2
		exit 1
	}
elif command -v codex >/dev/null 2>&1; then
	codex_bin=$(command -v codex)
elif [[ -x /usr/lib/chatgpt/resources/codex ]]; then
	codex_bin=/usr/lib/chatgpt/resources/codex
else
	printf 'Codex CLI not found. Set CODEX_BIN to its full executable path.\n' >&2
	exit 1
fi

if "$dry_run"; then
	printf 'Workspace: %s\nCodex executable: %s\n\n%s\n' "$project_dir" "$codex_bin" "$prompt"
	exit 0
fi

for executable in flock; do
	if ! command -v "$executable" >/dev/null 2>&1; then
		printf 'Required command is unavailable: %s\n' "$executable" >&2
		exit 1
	fi
done

# Keep this file in place: removing it could let concurrent runs lock different inodes.
exec 9>"$project_dir/.next-task.lock"
if ! flock -n 9; then
	printf 'Another next-task.sh run is already active in this workspace.\n' >&2
	exit 1
fi

cd -- "$project_dir"
printf '%s\n' "$prompt" | "$codex_bin" exec --cd "$project_dir" --sandbox workspace-write --skip-git-repo-check -
