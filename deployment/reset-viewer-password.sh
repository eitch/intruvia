#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only
set -eu

if [ "$#" -ne 0 ]; then
	printf 'Usage: intruvia-reset-viewer-password\n' >&2
	exit 2
fi

exec java -cp '/opt/intruvia/intruvia-app.jar:/opt/intruvia/lib/*' \
	li.intruvia.app.DeploymentEntrypoint /var/lib/intruvia/runtime /opt/intruvia/templates 8080 offline reset-viewer-password