# Fail2ban notification integration

**Best effort only: events can be lost during an outage after at most three
attempts within 15 seconds. There is no persistent offline queue.** Notification
failure leaves the existing ban action effective. Notifications may delay the
jail's next action by up to 16 seconds; this integration is synchronous.

Requires Unix/Linux, Python 3 standard library and Fail2ban's Python-action API
(verified with Fail2ban 1.1.0). No pip packages are required. `intruvia.py` is the
additive action template; its `ban()` callback is the Python equivalent of
`actionban`. It does not use a shell or substitute `<matches>` or other values
into a shell command. Start, stop, unban, reban and restored bans do not notify.

## Install on each reporting server

First provision an ingestion-only Strolch technical user/PAT and a stable server
mapping on Intruvia as described in the root README. Use one user per reporting
server; do not add an instance ID to the notification payload.

From this directory (also shipped under `intruvia/integrations/fail2ban/`), install:

```sh
sudo install -d -o root -g root -m 0755 /usr/local/lib/intruvia
sudo install -o root -g root -m 0644 intruvia_notify.py /usr/local/lib/intruvia/intruvia_notify.py
sudo install -o root -g root -m 0644 intruvia.py /etc/fail2ban/action.d/intruvia.py
```

Use a protected provisioning channel to create `/etc/fail2ban/intruvia.pat`, owned
by root with mode `0600` (or `0400`). Its only line must be the issued
`tokenId:tokenValue`, without the `Bearer ` prefix. An optional final newline is
accepted. Never paste the token into a command argument, URL, jail configuration,
log or source control. File size is capped at 4096 bytes. Symlinks, nonregular
files, group/other access and a different owner are rejected. Root is the default
Fail2ban service user; if running under another dedicated service account, that
account must own the file. Keep the file's parent directories, action, helper and
Python interpreter protected from untrusted writes. No secret file is shipped.

Inspect the jail's current effective `action` list. Merge the example into that
jail in `/etc/fail2ban/jail.local` or `jail.d/intruvia.local`, retaining every
existing action and appending `intruvia.py`. For a jail currently using
`%(action_)s`, the result is:

```ini
[sshd]
action = %(action_)s
         intruvia.py[endpoint="https://intruvia.example.org/api/v1/fail2ban/events"]
```

If the current jail uses `%(action_mwl)s` or custom actions, preserve that exact
list instead of replacing it with `%(action_)s`. Put the notification last so the
existing firewall action runs first. Do not replace `banaction`. The example does
not enable a jail or change a filter, firewall rule, ban time or unban behavior.

Set the real HTTPS ingestion endpoint. System trust roots verify certificates
and hostnames. For a private CA add `ca_file="/etc/fail2ban/intruvia-ca.pem"` to
the bracketed action options. Keep that PEM trust file protected. HTTP, redirects,
URL credentials, queries and fragments are rejected; there is no insecure-TLS
switch. The helper connects directly, ignoring proxy environment variables.
The endpoint must have exactly `/api/v1/fail2ban/events` as its path.

Optional action parameters: `token_file`, `ca_file`, `helper`, `python`. Defaults
are `/etc/fail2ban/intruvia.pat`, system trust, `/usr/local/lib/intruvia/intruvia_notify.py`
and `/usr/bin/python3`. These are trusted administrator settings, never event data.

Validate before reloading the existing service:

```sh
sudo fail2ban-client -t
sudo fail2ban-client reload
sudo fail2ban-client get sshd actions
```

Confirm the existing action and `intruvia` are both listed. Review Fail2ban's
local log for the fixed warning `Intruvia notification failed; event may be lost`.
No payload, IP, PAT, endpoint or remote response body is included in this warning.
Use a controlled test ban only in a disposable jail; do not ban a real administrator.
Rotate by atomically replacing the private PAT file with the new scoped token
for the same technical user, then revoke the old token. The next invocation reads
the new file without a Fail2ban restart.

## Delivery behavior

Each invocation generates one UUID and UTC timestamp and JSON-encodes the IP,
jail and optional nonnegative failure count. The action passes values in an argv
list using `--option=value`, so leading dashes, quotes, Unicode and shell-looking
text remain data. No log-match content is transmitted. The helper reuses the
identical UTF-8 body and Authorization header across retries. `200` and `201`
mean success; `429`, `5xx` and network failures retry at most three total attempts.
Other responses, including other `4xx` and redirects, stop immediately. TLS
certificate verification failure is terminal.

Backoff is jittered (0.25–0.75 seconds then 0.5–1.5 seconds). A valid Retry-After
integer or HTTP date is a minimum wait; if it exceeds the remaining budget, the
helper stops instead of retrying early. Connections/reads time out after at most
four seconds each; a Unix real-time alarm caps the whole invocation after argument parsing at
15 seconds, including PAT loading, TLS setup, DNS and trickled headers. The action kills/reaps a helper
still running after 16 seconds. Failures do not raise from the action. A fresh
ban invocation has a fresh ID; resubmitting it manually is a distinct event.

## Verification

From the repository root:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s integrations/fail2ban/tests -v
PYTHONDONTWRITEBYTECODE=1 python3 integrations/fail2ban/tests/action_smoke.py /path/to/fail2ban-1.1.0
```

Tests use only the standard library, an installed OpenSSL executable for temporary
TLS certificates, and the real unpacked Fail2ban source for the smoke check. The
smoke runs a temporary unprivileged polling jail/socket and a harmless marker
action; it does not change the host firewall or running Fail2ban service. See
[task design and evidence](../../docs/architecture/018-fail2ban-notifications.md).
