// SPDX-License-Identifier: AGPL-3.0-only

export function fail2banInstallCommand(origin) {
	const url = new URL(origin);
	if (url.protocol !== 'https:' || url.origin !== origin) return null;
	const quote = (value) => `'${value.replaceAll("'", "'\\''")}'`;
	const download = quote(`${origin}/api/v1/fail2ban/install.py`);
	return `(set +x; umask 077; unset TOKEN; read -r -s -p 'Bearer token: ' TOKEN && printf '\\n' && ` +
		`tmp=$(mktemp) && trap 'rm -f -- "$tmp"' EXIT && ` +
		`status=$(printf 'Authorization: Bearer %s\\n' "$TOKEN" | curl -q --proto '=https' --fail --silent --show-error ` +
		`--max-time 60 --header @- --output "$tmp" --write-out '%{http_code}' ${download}) && [ "$status" = 200 ] && ` +
		`printf '%s\\n' "$TOKEN" | sudo python3 "$tmp" ${quote(origin)}; result=$?; unset TOKEN; exit "$result")`;
}