#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only

INTRUVIA_IMAGE_REPOSITORY='repo.strolch.li/intruvia'
INTRUVIA_RELEASE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if ! INTRUVIA_RELEASE_VERSION=$(python3 - "${INTRUVIA_RELEASE_ROOT}/pom.xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

try:
    pom = ET.parse(sys.argv[1]).getroot()
    version_element = pom.find("{http://maven.apache.org/POM/4.0.0}version")
    version = version_element.text.strip() if version_element is not None and version_element.text else ""
except (OSError, ET.ParseError):
    version = ""

if len(version) > 128 or not re.fullmatch(r"v?[0-9][A-Za-z0-9_.-]*", version):
    sys.exit(1)

print(version)
PY
); then
	printf 'Unable to read a Docker-safe release version from the root Maven POM\n' >&2
	return 1 2>/dev/null || exit 1
fi
INTRUVIA_IMAGE_REFERENCE="${INTRUVIA_IMAGE_REPOSITORY}:${INTRUVIA_RELEASE_VERSION}"
unset INTRUVIA_RELEASE_ROOT