#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail

with_tests=false
if [[ $# == 1 && $1 == --with-tests ]]; then
	with_tests=true
elif [[ $# != 0 ]]; then
	printf 'Usage: %s [--with-tests]\n' "$0" >&2
	exit 2
fi
root=$(cd "$(dirname "$0")" && pwd)
source "$root/scripts/release-config.sh"

if [[ $with_tests == true ]]; then
	"$root/scripts/verify-postgresql.sh"
else
	mvn -B -f "$root/pom.xml" clean package -DskipTests
fi
"$root/scripts/build-deployment-image.sh" "$INTRUVIA_IMAGE_REFERENCE" "$root/intruvia-app/target/intruvia"
docker push "$INTRUVIA_IMAGE_REFERENCE"
printf 'Pushed %s\n' "$INTRUVIA_IMAGE_REFERENCE"