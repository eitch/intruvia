#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only

import os
import re
import stat
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


MODULES = ("intruvia-core", "intruvia-rest", "intruvia-web", "intruvia-app")
NAMESPACE = "http://maven.apache.org/POM/4.0.0"
VERSION_PATTERN = re.compile(r"[0-9][A-Za-z0-9._+-]*\Z")


def fail(message):
	print(f"ERROR: {message}", file=sys.stderr)
	sys.exit(1)


def xml_tag(name):
	return f"{{{NAMESPACE}}}{name}"


def inspect_reactor(root):
	paths = [root / "pom.xml"]
	try:
		root_tree = ET.parse(paths[0]).getroot()
		modules = [module.text.strip() for module in root_tree.findall(f"{xml_tag('modules')}/{xml_tag('module')}")]
		if modules != list(MODULES):
			fail(f"Expected the four Intruvia reactor modules, found {modules}")
		root_version = root_tree.findtext(xml_tag("version"), default="").strip()
		if not root_version:
			fail("Root POM has no project version")
		for module in MODULES:
			path = root / module / "pom.xml"
			module_tree = ET.parse(path).getroot()
			parent = module_tree.find(xml_tag("parent"))
			if parent is None:
				fail(f"{path} has no parent POM")
			if parent.findtext(xml_tag("groupId"), default="").strip() != "li.intruvia":
				fail(f"{path} has an unexpected parent groupId")
			if parent.findtext(xml_tag("artifactId"), default="").strip() != "intruvia":
				fail(f"{path} has an unexpected parent artifactId")
			parent_version = parent.findtext(xml_tag("version"), default="").strip()
			if parent_version != root_version:
				fail(f"{path} parent version {parent_version!r} does not match root {root_version!r}")
			paths.append(path)
	except (OSError, ET.ParseError) as error:
		fail(f"Unable to inspect Maven reactor: {error}")
	return root_version, paths


def replace_version(path, content, expected, target, is_root):
	if is_root:
		pattern = re.compile(
			r"(?s)(<project\b[^>]*>.*?<packaging>\s*pom\s*</packaging>.*?)(<version>)([^<]*)(</version>)"
		)
		match = pattern.search(content)
	else:
		parent = re.search(r"(?s)<parent\b[^>]*>.*?</parent>", content)
		if parent is None:
			fail(f"{path} has no parent block")
		version = re.search(r"<version>([^<]*)</version>", parent.group(0))
		if version is None:
			fail(f"{path} parent block has no version")
		start = parent.start() + version.start(1)
		end = parent.start() + version.end(1)
		if content[start:end].strip() != expected:
			fail(f"{path} version changed unexpectedly; expected {expected!r}")
		return content[:start] + target + content[end:]
	if match is None:
		fail(f"Unable to locate the root project version in {path}")
	if match.group(3).strip() != expected:
		fail(f"{path} version changed unexpectedly; expected {expected!r}")
	return content[:match.start(3)] + target + content[match.end(3):]


def write_atomically(path, content):
	mode = stat.S_IMODE(path.stat().st_mode)
	temporary = None
	try:
		with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as file:
			temporary = Path(file.name)
			file.write(content)
			file.flush()
			os.fsync(file.fileno())
		os.chmod(temporary, mode)
		os.replace(temporary, path)
	except OSError as error:
		if temporary is not None:
			temporary.unlink(missing_ok=True)
		fail(f"Unable to update {path}: {error}")


def main():
	if len(sys.argv) not in (3, 5):
		fail("Usage: update-maven-reactor-version.py check ROOT | set ROOT EXPECTED_VERSION TARGET_VERSION")
	mode = sys.argv[1]
	root = Path(sys.argv[2]).resolve()
	current, paths = inspect_reactor(root)
	if mode == "check" and len(sys.argv) == 3:
		print(current)
		return
	if mode != "set" or len(sys.argv) != 5:
		fail("Usage: update-maven-reactor-version.py check ROOT | set ROOT EXPECTED_VERSION TARGET_VERSION")
	expected, target = sys.argv[3:5]
	if not VERSION_PATTERN.fullmatch(target):
		fail(f"Invalid Maven version: {target!r}")
	if current != expected:
		fail(f"Root POM version is {current!r}, not expected {expected!r}")
	updates = []
	for index, path in enumerate(paths):
		content = path.read_text(encoding="utf-8")
		updated = replace_version(path, content, expected, target, index == 0)
		try:
			ET.fromstring(updated)
		except ET.ParseError as error:
			fail(f"Version update would make {path} invalid XML: {error}")
		updates.append((path, updated))
	for path, content in updates:
		write_atomically(path, content)
	new_version, _ = inspect_reactor(root)
	if new_version != target:
		fail(f"Updated reactor reports {new_version!r}, not {target!r}")


if __name__ == "__main__":
	main()