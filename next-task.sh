#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
dry_run=false
retry_blocked=false
for argument in "$@"; do
	case "$argument" in
		--dry-run) dry_run=true ;;
		--retry-blocked) retry_blocked=true ;;
		-h|--help)
			printf 'Usage: %s [--dry-run] [--retry-blocked]\nRun one Intruvia backlog task using Codex CLI.\n' "$0"
			exit 0 ;;
		*) printf 'Unknown argument: %s\n' "$argument" >&2; exit 2 ;;
	esac
done

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

Before declaring Maven artifact resolution blocked, inspect the local Maven repository
and the framework source referenced by AGENTS.md. An installed 2.8.0-SNAPSHOT does not
satisfy an exact timestamped snapshot coordinate. Follow README.md's local Strolch
build instructions: use -Dstrolch.version=2.8.0-SNAPSHOT and offline mode when cached
dependencies suffice, or build/install the required framework reactor modules. Record
the actual version, checksums and verification results; never relabel local artifacts
as the timestamped baseline. Exhaust these local recovery paths before recording a blocker.

Update the status ledger, counts, current/next task, date, and execution log.
Record exact verification commands/results and file/artifact references. Mark
DONE only if all acceptance criteria passed; never claim unrun checks succeeded.
Record blockers and failures honestly, keeping incomplete work incomplete.
Finish with the task number, changes, verification results, status, and next task.
PROMPT
)

if "$retry_blocked"; then
	prompt+=$'\n\nExplicit retry authorization: instead of selecting a TODO task, select the lowest-numbered BLOCKED task with all dependencies DONE. Stop if another task is IN_PROGRESS. Recheck its recorded blocker; if resolved, set it IN_PROGRESS and complete that task only. If unresolved, record the new evidence and leave it BLOCKED. If no blocked task is eligible, report that and stop.'
fi

maven_repository="${MAVEN_REPOSITORY:-$HOME/.m2/repository}"
if [[ "$maven_repository" != /* ]]; then
	printf 'MAVEN_REPOSITORY must be an absolute path.\n' >&2
	exit 2
fi

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
	printf 'Network access: enabled\nWritable Maven repository: %s\n' "$maven_repository"
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

mkdir -p -- "$maven_repository"
cd -- "$project_dir"
printf '%s\n' "$prompt" | "$codex_bin" exec --cd "$project_dir" --sandbox workspace-write \
	-c sandbox_workspace_write.network_access=true --add-dir "$maven_repository" --skip-git-repo-check -
