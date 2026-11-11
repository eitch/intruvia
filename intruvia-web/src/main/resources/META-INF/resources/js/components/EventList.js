// SPDX-License-Identifier: AGPL-3.0-only
import {formatTime, locationText, hasCoordinates, unmappedReason} from '../utils/format.js';
import {eventDetails} from './eventDetails.js';

export default class EventList {
	constructor(root, showOnMap) { this.root = root; this.showOnMap = showOnMap; this.rows = new Map(); this.timers = new Map(); }
	render(events) {
		this.destroy();
		const fragment = document.createDocumentFragment();
		for (const event of [...events].reverse()) {
			const row = this.createRow(event);
			fragment.append(row);
		}
		this.root.replaceChildren(fragment);
	}
	createRow(event) {
		const row = document.createElement('li');
		row.dataset.eventId = event.id;
		this.rows.set(event.id, row);
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
		return row;
	}
	apply(event, evicted) {
		if (evicted) {
			this.rows.get(evicted.id)?.remove();
			this.rows.delete(evicted.id);
			clearTimeout(this.timers.get(evicted.id));
			this.timers.delete(evicted.id);
		}
		const row = this.createRow(event);
		row.classList.add('event-new');
		this.root.prepend(row);
		this.timers.set(event.id, setTimeout(() => {
			row.classList.remove('event-new');
			this.timers.delete(event.id);
		}, 3000));
	}
	destroy() {
		for (const timer of this.timers.values()) clearTimeout(timer);
		this.timers.clear();
		this.rows.clear();
		this.root.replaceChildren();
	}
}
