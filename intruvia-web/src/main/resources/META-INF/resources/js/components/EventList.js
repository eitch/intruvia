// SPDX-License-Identifier: AGPL-3.0-only
import {formatTime, locationText, hasCoordinates, unmappedReason} from '../utils/format.js';
import {eventDetails} from './eventDetails.js';

export default class EventList {
	constructor(root, showOnMap) { this.root = root; this.showOnMap = showOnMap; }
	render(events) {
		const fragment = document.createDocumentFragment();
		for (const event of [...events].reverse()) {
			const row = document.createElement('li');
			const title = document.createElement('strong');
			title.textContent = `${event.subject?.ip || 'Unknown IP'} · ${event.source?.instanceId || 'Unknown server'}`;
			const summary = document.createElement('p');
			summary.textContent = `${formatTime(event.occurredAt)} · ${locationText(event.geo)}`;
			const details = document.createElement('details');
			const toggle = document.createElement('summary');
			toggle.textContent = 'Event details';
			details.append(toggle, eventDetails(event));
			row.append(title, summary);
			if (hasCoordinates(event)) {
				const button = document.createElement('button');
				button.type = 'button';
				button.textContent = 'Show on map';
				button.addEventListener('click', () => this.showOnMap(event));
				row.append(button);
			} else {
				const reason = document.createElement('p');
				reason.textContent = `Unmapped: ${unmappedReason(event)}`;
				row.append(reason);
			}
			row.append(details);
			fragment.append(row);
		}
		this.root.replaceChildren(fragment);
	}
	destroy() { this.root.replaceChildren(); }
}
