#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail

if [[ $# != 1 || ! $1 =~ ^repo\.strolch\.li/.+:(v?[0-9][A-Za-z0-9_.-]*)$ ]]; then
	printf 'Usage: %s repo.strolch.li/<repository>:<version>\n' "$0" >&2
	exit 2
fi
root=$(cd "$(dirname "$0")" && pwd)
image=$1
"$root/scripts/build-deployment-image.sh" "$image" "$root/intruvia-app/target/intruvia"
docker push "$image"