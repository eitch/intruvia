#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only

INTRUVIA_IMAGE_REPOSITORY='repo.strolch.li/intruvia'
INTRUVIA_RELEASE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if ! INTRUVIA_RELEASE_VERSION=$(python3 - "${INTRUVIA_RELEASE_ROOT}/pom.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

try:
    pom = ET.parse(sys.argv[1]).getroot()
    version_element = pom.find("{http://maven.apache.org/POM/4.0.0}version")
    version = version_element.text.strip() if version_element is not None and version_element.text else ""
except (OSError, ET.ParseError):
    version = ""

print(version)
PY
); then
	printf 'Unable to read the release version from the root Maven POM\n' >&2
	return 1 2>/dev/null || exit 1
fi

intruvia_image_tags_for_version() {
	python3 - "$1" <<'PY'
import re
import sys

version = sys.argv[1]
match = re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?", version)
if match is None or len(version) > 128:
    raise SystemExit(1)

major, minor, _patch, qualifier = match.groups()
suffix = f"-{qualifier}" if qualifier else ""
tags = (f"{major}{suffix}", f"{major}.{minor}{suffix}", version)
if not re.search(r"-SNAPSHOT$", version, re.IGNORECASE):
    tags += ("latest",)
if any(len(tag) > 128 or not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]*", tag) for tag in tags):
    raise SystemExit(1)
print("\n".join(tags))
PY
}

if ! INTRUVIA_IMAGE_TAG_OUTPUT=$(intruvia_image_tags_for_version "$INTRUVIA_RELEASE_VERSION"); then
	printf 'Unable to derive Docker tags from the root Maven version\n' >&2
	return 1 2>/dev/null || exit 1
fi
mapfile -t INTRUVIA_IMAGE_TAGS <<< "$INTRUVIA_IMAGE_TAG_OUTPUT"

if [[ ${INTRUVIA_RELEASE_VERSION,,} == *-snapshot ]]; then
	expected_image_tag_count=3
else
	expected_image_tag_count=4
fi
if ((${#INTRUVIA_IMAGE_TAGS[@]} != expected_image_tag_count)); then
	printf 'Unable to derive the expected Docker tags from the root Maven version\n' >&2
	return 1 2>/dev/null || exit 1
fi
INTRUVIA_IMAGE_REFERENCES=()
for image_tag in "${INTRUVIA_IMAGE_TAGS[@]}"; do
	INTRUVIA_IMAGE_REFERENCES+=("${INTRUVIA_IMAGE_REPOSITORY}:${image_tag}")
done
INTRUVIA_IMAGE_REFERENCE=${INTRUVIA_IMAGE_REFERENCES[2]}
unset INTRUVIA_IMAGE_TAG_OUTPUT image_tag
unset expected_image_tag_count
unset INTRUVIA_RELEASE_ROOT