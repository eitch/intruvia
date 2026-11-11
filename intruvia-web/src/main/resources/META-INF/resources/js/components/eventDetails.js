// SPDX-License-Identifier: AGPL-3.0-only
import {formatTime, formatNumber, locationText, hasCoordinates, unmappedReason} from '../utils/format.js';

export function eventDetails(event) {
	const details = document.createElement('dl');
	const geo = event.geo || {};
	const fields = [
		['IP', event.subject?.ip || 'Unknown'], ['Server', event.source?.instanceId || 'Unknown'],
		['Jail', event.attributes?.fail2ban?.jail || 'Unknown'], ['Occurred', formatTime(event.occurredAt)],
		['Received', formatTime(event.receivedAt)], ['Location', locationText(geo)],
		['Map status', hasCoordinates(event) ? 'Mapped' : unmappedReason(event)]
	];
	if (Number.isFinite(geo.accuracyRadiusKm)) fields.push(['Accuracy radius', `${formatNumber(geo.accuracyRadiusKm)} km`]);
	for (const [label, value] of fields) {
		const term = document.createElement('dt');
		term.textContent = label;
		const description = document.createElement('dd');
		description.textContent = value;
		details.append(term, description);
	}
	return details;
}
