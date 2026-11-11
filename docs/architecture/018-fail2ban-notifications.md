# 018 — Fail2ban notification contract

The standalone Unix Python 3 helper and additive Python action live in
`integrations/fail2ban/`; Maven copies the runtime files into the distribution's
same relative directory, without tests or bytecode. No new application dependency,
frontend tooling, offline queue or token verifier is introduced.

A Python action is intentional: Fail2ban loads `Action` through its supported
Python-action API. This avoids interpolating jail/event text into a shell
`actionban` command. `ban(aInfo)` invokes the helper through a subprocess argv
list; the jail name comes from the actual Jail object. `start`, `stop`, `unban`
are inherited no-ops, `reban` is explicitly a no-op, and `norestored=True` avoids
reporting restored bans as new observations. The pre-existing firewall action
must be retained and precede this action. No installation script edits host jails.

The helper validates producer values, creates one UUID/UTC timestamp, and encodes
one immutable JSON byte sequence before sending. It reads a bounded, private,
service-user-owned regular PAT file with O_NOFOLLOW and descriptor-based checks.
Default root-run Fail2ban therefore requires a root-owned 0600/0400 file. Parent
directories and executable/trust files must be protected administratively.
Framework-generated PAT format remains `tokenId:tokenValue`; only its header-safe
syntax is checked here. Strolch performs authentication and identity resolution.

HTTPSConnection uses a default verifying SSLContext, optional private-CA PEM,
finite socket timeout, no redirects and no environment proxies. Only the fixed
API path is accepted. The retry loop handles 429/5xx and transport failures;
certificate verification errors and all other HTTP statuses are terminal.
Retry-After seconds/dates impose a minimum delay; waits beyond the deadline fail
immediately. At most three attempts reuse exactly the same body. A 15-second Unix
SIGALRM bounds the invocation after argument parsing, including PAT loading,
TLS setup, blocking DNS/header trickling and sleep; this API runs only in
the standalone helper main thread. The action adds a 16-second subprocess limit.
The action is synchronous and can delay its jail thread up to that bound; keep it
last. Failures log only a fixed warning and do not throw into firewall execution.
The CLI returns 1 on failure; action dispatch deliberately absorbs that failure.

There is no delivery guarantee after bounded retries and no durable queue.
Lost acknowledgements can be retried safely within one invocation by server
receipt deduplication. New invocations have new IDs. Old firewall state is not
reported on startup, and no unban or remote command is implemented.

Verification uses a temporary TLS server for retry/status, byte identity, safe
encoding, trust/hostname rejection, file protection and real-time deadline checks.
The real Fail2ban 1.1.0 client/server smoke parses the shipped additive template,
starts a disposable polling jail with a harmless marker action, triggers IPv6
ban/unban plus a 403 failure, and checks that the existing marker and ban remain.
It also loads the action through the real framework with an adversarial jail name.
No host firewall commands, production endpoint, real PAT or service changes occur.
Exact commands, upstream hash, package/cache identities, results and limits are in
[acceptance](../verification/018-acceptance.txt).
