#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail
export LC_ALL=C

if [[ $# != 3 ]]; then
	printf 'Usage: %s <release-version> <versioned-image-reference> <output-directory>\n' "$0" >&2
	exit 2
fi

version=$1
image=$2
if [[ ! $version =~ ^v?[0-9][A-Za-z0-9_.-]*$ || ${#version} -gt 128 ]]; then
	printf 'Invalid release version\n' >&2
	exit 2
fi

# Validate the repository independently of the tag, so a registry port is not a tag.
name=$image
digest=
if [[ $name == *@* ]]; then
	digest=${name#*@}
	name=${name%%@*}
	if [[ ! $digest =~ ^sha256:[a-f0-9]{64}$ ]]; then
		printf 'Invalid image digest\n' >&2
		exit 2
	fi
fi
last=${name##*/}
tag=
if [[ $last == *:* ]]; then
	tag=${last#*:}
	name=${name%:*}
	if [[ ! $tag =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ || ! $tag =~ [0-9] || ${tag,,} == latest ]]; then
		printf 'Image tag must be an explicit version, not latest\n' >&2
		exit 2
	fi
fi
if [[ -z $digest && -z $tag ]] || [[ ${#name} -gt 255 || -z $name || $name == /* || $name == */ || $name == *//* ]]; then
	printf 'Image reference must have a repository and version tag or digest\n' >&2
	exit 2
fi
IFS=/ read -r -a components <<< "$name"
for index in "${!components[@]}"; do
	component=${components[$index]}
	if (( index == 0 && ${#components[@]} > 1 )) && [[ $component == *.* || $component == *:* || $component == localhost ]]; then
		if [[ ! $component =~ ^[a-zA-Z0-9]+([.-][a-zA-Z0-9]+)*(:[0-9]+)?$ ]]; then
			printf 'Invalid image registry\n' >&2
			exit 2
		fi
	elif [[ ! $component =~ ^[a-z0-9]+(([._]|__|[-]+)[a-z0-9]+)*$ ]]; then
		printf 'Invalid image repository\n' >&2
		exit 2
	fi
done

root=$(cd "$(dirname "$0")/.." && pwd)
output=$(cd "$3" && pwd)
archive=intruvia-deployment-${version}.tar.gz
checksum=$archive.sha256
if [[ -e $output/$archive || -L $output/$archive || -e $output/$checksum || -L $output/$checksum ]]; then
	printf 'Release artifact already exists; refusing to overwrite\n' >&2
	exit 1
fi

stage=$(mktemp -d "$output/.intruvia-deployment.XXXXXXXX")
published_archive=false
published_checksum=false
cleanup() {
	if [[ $published_archive == true && $published_checksum == false && $output/$archive -ef $stage/$archive ]]; then
		rm -- "$output/$archive"
	fi
	rm -rf -- "$stage"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
bundle=$stage/bundle
mkdir -m 0755 "$bundle" "$bundle/db" "$bundle/docs" "$bundle/docs/deployment" \
		"$bundle/runtime-templates" "$bundle/runtime-templates/config" "$bundle/integrations" "$bundle/integrations/fail2ban"
mkdir -m 0700 "$bundle/secrets"

copy_file() {
	local source=$1 destination=$2
	if [[ ! -f $source || -L $source ]]; then
		printf 'Missing or unsafe bundle input: %s\n' "$source" >&2
		exit 1
	fi
	cp -p -- "$source" "$destination"
}

# Release owners must review every byte, including comments, before updating
# these SHA-256 baselines for an intentional template change.
matches_baseline() {
	local file=$1 expected=$2 actual
	actual=$(sha256sum < "$file")
	[[ $actual == "$expected  -" ]]
}

for directory in deployment deployment/db intruvia-app/src/main/runtime intruvia-app/src/main/runtime/config \
		docs docs/deployment integrations integrations/fail2ban; do
	if [[ ! -d $root/$directory || -L $root/$directory ]]; then
		printf 'Missing or unsafe bundle input directory: %s\n' "$directory" >&2
		exit 1
	fi
done

copy_file "$root/deployment/compose.yaml" "$bundle/compose.yaml"
copy_file "$root/deployment/Caddyfile" "$bundle/Caddyfile"
copy_file "$root/deployment/db/init.sh" "$bundle/db/init.sh"
copy_file "$root/deployment/.env.example" "$stage/env.example"

# Accept only the reviewed template keys. Passwords and the public origin must be
# empty. The exact-byte baseline below also prevents credentials in comments.
awk '
	/^[[:space:]]*(#|$)/ { next }
	{
		if ($0 !~ /^[A-Z][A-Z0-9_]*=/) exit 1
		key = substr($0, 1, index($0, "=") - 1)
		value = substr($0, index($0, "=") + 1)
		if (++seen[key] != 1) exit 1
		if (key == "INTRUVIA_IMAGE") {
			if (value != "") exit 1
		} else if (key == "INTRUVIA_PUBLIC_ORIGIN" || key == "POSTGRES_ADMIN_PASSWORD" || key == "INTRUVIA_DB_PASSWORD") {
			if (value != "") exit 1
		} else if (key == "POSTGRES_ADMIN_USERNAME" || key == "INTRUVIA_DB_NAME" || key == "INTRUVIA_DB_USERNAME") {
			if (value !~ /^[a-z][a-z0-9_]*$/) exit 1
		} else exit 1
	}
	END {
		if (seen["INTRUVIA_IMAGE"] != 1 || seen["INTRUVIA_PUBLIC_ORIGIN"] != 1 || seen["POSTGRES_ADMIN_PASSWORD"] != 1 ||
				seen["INTRUVIA_DB_PASSWORD"] != 1 || seen["POSTGRES_ADMIN_USERNAME"] != 1 ||
				seen["INTRUVIA_DB_NAME"] != 1 || seen["INTRUVIA_DB_USERNAME"] != 1) exit 1
	}
' "$stage/env.example" || { printf 'Unsafe or unexpected deployment environment template\n' >&2; exit 1; }
matches_baseline "$stage/env.example" bd9cdb7c984420b2c729dc1780fbd6e061f744f6f11deafd10c72d75908a5a53 || {
	printf 'Unsafe or unexpected deployment environment template\n' >&2
	exit 1
}
sed "s|^INTRUVIA_IMAGE=$|INTRUVIA_IMAGE=$image|" "$stage/env.example" > "$bundle/.env"
chmod 0600 "$bundle/.env"

for file in StrolchConfiguration.xml PrivilegeConfig.xml.example PrivilegeRoles.xml.example PrivilegeUsers.xml.example \
		ingestion.properties.example; do
	copy_file "$root/intruvia-app/src/main/runtime/config/$file" "$bundle/runtime-templates/config/$file"
	case $file in
		StrolchConfiguration.xml) baseline=cbe0c4ae846372998b838251699c3f46600c5bc57e12e867a08fa141be06a4e5 ;;
		PrivilegeConfig.xml.example) baseline=e7a5e7bed622b06ff8165cc9150b0fd122f45e11b3d8489d3a4cacbcf35b4549 ;;
		PrivilegeRoles.xml.example) baseline=2be9c1b7bdc137b4c6332c0fbc8db782333759d546554b9af478dc94d15a4be0 ;;
		PrivilegeUsers.xml.example) baseline=3b76ded939ac3a624f0b9fef65c9f66d031c597e23a4455923e017bf51bc20f5 ;;
		ingestion.properties.example) baseline=567c91635a6dee400719e5e1a1c4c558e8375ec87f9f83774661c96987a5c958 ;;
		*) printf 'Missing reviewed runtime template baseline: %s\n' "$file" >&2; exit 1 ;;
	esac
	matches_baseline "$bundle/runtime-templates/config/$file" "$baseline" || {
		printf 'Unreviewed runtime template: %s\n' "$file" >&2
		exit 1
	}
done
copy_file "$root/LICENSE" "$bundle/LICENSE"
copy_file "$root/intruvia-app/src/main/runtime/LICENSE-Strolch" "$bundle/LICENSE-Strolch"
copy_file "$root/README.md" "$bundle/README.md"
copy_file "$root/docs/DEPLOYMENT.md" "$bundle/docs/DEPLOYMENT.md"
copy_file "$root/docs/deployment/compose-initialization-recovery.md" "$bundle/docs/deployment/compose-initialization-recovery.md"
for file in README.md intruvia.py intruvia_notify.py jail.local.example; do
	copy_file "$root/integrations/fail2ban/$file" "$bundle/integrations/fail2ban/$file"
done

tar --sort=name --numeric-owner --owner=0 --group=0 -czf "$stage/$archive" -C "$bundle" .
(cd "$stage" && sha256sum "$archive" > "$checksum")
# Hard links publish complete files atomically and fail rather than clobbering.
ln -- "$stage/$archive" "$output/$archive"
published_archive=true
ln -- "$stage/$checksum" "$output/$checksum"
published_checksum=true
printf 'Created %s and %s\n' "$output/$archive" "$output/$checksum"