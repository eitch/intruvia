// SPDX-License-Identifier: AGPL-3.0-only
import * as api from '../api/viewer.js';
import EventMap from '../components/EventMap.js';
import EventList from '../components/EventList.js';
import {snapshotModel} from '../utils/snapshot.js';
import {formatNumber, hasCoordinates} from '../utils/format.js';

export default class ViewerPage {
	constructor(root, status, onSessionEnded) {
		this.root = root;
		this.status = status;
		this.onSessionEnded = onSessionEnded;
		this.controller = null;
		this.map = null;
		this.model = null;
		this.list = new EventList(root.querySelector('#events'), (event) => this.map?.show(event));
		this.refreshButton = root.querySelector('#refresh');
		this.refresh = () => this.load();
		this.refreshButton.addEventListener('click', this.refresh);
	}
	async load() {
		this.controller?.abort();
		const controller = new AbortController();
		this.controller = controller;
		this.refreshButton.disabled = true;
		this.status.textContent = 'Loading snapshot…';
		try {
			const [snapshot, boundaries] = await Promise.all([
				api.getSnapshot(controller.signal), this.map ? null : api.getWorldBoundaries(controller.signal)
			]);
			if (controller.signal.aborted) return;
			this.model = snapshotModel(snapshot);
			this.root.hidden = false;
			if (!this.map) this.map = new EventMap(this.root.querySelector('#map'), boundaries);
			this.map.render(this.model.items);
			this.list.render(this.model.items);
			const unmapped = this.model.items.filter((event) => !hasCoordinates(event)).length;
			this.root.querySelector('#window').textContent =
				`Rolling window: ${formatNumber(this.model.items.length)} of at most ${formatNumber(1000)} recent events · ` +
				`${formatNumber(unmapped)} unmapped. ${this.model.truncated ? 'Older events omitted. ' : ''}Counts describe this window only.`;
			this.root.querySelector('#empty').hidden = this.model.items.length !== 0;
			this.root.querySelector('#geo-notice').textContent = this.model.items.some((event) =>
				['UNAVAILABLE', 'ERROR'].includes(event.geo?.status)) ?
				'Some events have degraded GeoIP results. Their original enrichment is preserved.' : '';
			this.status.textContent = 'Snapshot loaded · Live updates are not connected. Refresh to load recent events.';
		} catch (error) {
			if (controller.signal.aborted) return;
			if (error.status === 401) { this.onSessionEnded(); return; }
			this.status.textContent = this.model ? 'Refresh failed. Showing the previous snapshot. Try again.' :
				'Unable to load the snapshot or local map. Try refreshing.';
			this.root.hidden = false;
		} finally {
			if (this.controller === controller) this.refreshButton.disabled = false;
		}
	}
	destroy() {
		this.controller?.abort();
		this.refreshButton.removeEventListener('click', this.refresh);
		this.map?.destroy();
		this.list.destroy();
		this.root.hidden = true;
	}
}
