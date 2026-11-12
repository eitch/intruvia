#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail

if [[ $# != 2 ]]; then
	printf 'Usage: %s <image-reference> <assembled-distribution-directory>\n' "$0" >&2
	exit 2
fi
root=$(cd "$(dirname "$0")/.." && pwd)
image=$1
distribution=$(cd "$2" && pwd)
shopt -s nullglob
application=("$distribution"/intruvia-app-*.jar)
libraries=("$distribution"/lib/*.jar)
if [[ ${#application[@]} != 1 || ${#libraries[@]} == 0 ]]; then
	printf 'The assembled distribution must contain one application JAR and runtime dependency JARs\n' >&2
	exit 1
fi
context=$(mktemp -d "$root/intruvia-app/target/deployment-image.XXXXXXXX")
trap 'rm -rf -- "$context"' EXIT
install -m 0644 "$root/deployment/Dockerfile" "$context/Dockerfile"
install -m 0644 "${application[0]}" "$context/intruvia-app.jar"
mkdir -m 0755 "$context/lib"
for library in "${libraries[@]}"; do
	install -m 0644 "$library" "$context/lib/"
done
strolch=("$context"/lib/strolch-*.jar)
if [[ ${#strolch[@]} == 0 ]]; then
	printf 'The assembled distribution contains no Strolch runtime JARs\n' >&2
	exit 1
fi
sha256sum "${strolch[@]}"
docker build --tag "$image" "$context"