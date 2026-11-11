// SPDX-License-Identifier: AGPL-3.0-only
import * as api from '../api/viewer.js';
import Recovery from './viewer/Recovery.js';
import {applyFrame} from '../utils/live.js';
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
		this.recovery = null;
		this.retry = null;
		this.loadAttempt = 0;
		this.destroyed = false;
		this.hiddenCount = 0;
		this.notice = root.querySelector('#recovery-notice');
		this.visibility = () => {
			if (document.hidden) {
				this.hiddenCount = 0;
				this.root.querySelector('#away-notice').textContent = '';
			}
			else if (this.hiddenCount) this.root.querySelector('#away-notice').textContent = `${formatNumber(this.hiddenCount)} new events received while away.`;
		};
		document.addEventListener('visibilitychange', this.visibility);
		this.list = new EventList(root.querySelector('#events'), (event) => this.map?.show(event));
		this.refreshButton = root.querySelector('#refresh');
		this.refresh = () => this.load();
		this.refreshButton.addEventListener('click', this.refresh);
	}
	async load(isRecovery = false) {
		if (this.destroyed) return;
		clearTimeout(this.retry);
		this.recovery?.stop();
		this.recovery = null;
		if (isRecovery) this.notice.textContent = 'Some older live updates are no longer available.';
		this.controller?.abort();
		const controller = new AbortController();
		this.controller = controller;
		let timedOut = false;
		const deadline = setTimeout(() => { timedOut = true; controller.abort(); }, 10000);
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
			this.renderSummary();
			this.status.textContent = 'Connecting live updates…';
			this.loadAttempt = 0;
			this.recovery = new Recovery({
				cursor: () => this.model.cursor, receive: (frame) => this.receive(frame),
				resnapshot: () => {
					this.status.textContent = 'Resynchronizing · Reloading recent events.';
					this.notice.textContent = 'Some older live updates are no longer available.';
					this.retry = setTimeout(() => this.load(true), 1000 + Math.random() * 1000);
				},
				status: (message) => { this.status.textContent = message; }, sessionEnded: this.onSessionEnded
			});
			this.recovery.start();
		} catch (error) {
			if ((controller.signal.aborted && !timedOut) || this.destroyed || this.controller !== controller) return;
			if (error.status === 401 || error.status === 403) { this.onSessionEnded(); return; }
			this.status.textContent = this.model ? 'Refresh failed. Showing the previous snapshot. Try again.' :
				'Unable to load the snapshot or local map. Try refreshing.';
			this.root.hidden = false;
			if (isRecovery) {
				this.status.textContent = 'Offline · Retrying snapshot recovery. Previously received events remain visible.';
				const ceiling = Math.min(30000, 2000 * 2 ** Math.min(this.loadAttempt++, 4));
				this.retry = setTimeout(() => this.load(true), ceiling / 2 + Math.random() * ceiling / 2);
			}
		} finally {
			clearTimeout(deadline);
			if (this.controller === controller) this.refreshButton.disabled = false;
		}
	}
	receive(frame) {
		if (frame.type !== 'event.created') throw new Error('Stream requires a new snapshot.');
		const change = applyFrame(this.model, frame);
		if (!change) return;
		this.map.apply(change.event, change.evicted);
		this.list.apply(change.event, change.evicted);
		this.model = change.model;
		if (document.hidden) this.hiddenCount++;
		this.renderSummary();
	}
	renderSummary() {
		const unmapped = this.model.items.filter((event) => !hasCoordinates(event)).length;
		this.root.querySelector('#window').textContent =
			`Rolling window: ${formatNumber(this.model.items.length)} of at most ${formatNumber(1000)} recent events · ` +
			`${formatNumber(unmapped)} unmapped. ${this.model.truncated ? 'Older events omitted. ' : ''}Counts describe this window only.`;
		this.root.querySelector('#empty').hidden = this.model.items.length !== 0;
		this.root.querySelector('#geo-notice').textContent = this.model.items.some((event) =>
			['UNAVAILABLE', 'ERROR'].includes(event.geo?.status)) ?
			'Some events have degraded GeoIP results. Their original enrichment is preserved.' : '';
	}

	destroy() {
		this.destroyed = true;
		clearTimeout(this.retry);
		this.recovery?.stop();
		document.removeEventListener('visibilitychange', this.visibility);
		this.notice.textContent = '';
		this.root.querySelector('#away-notice').textContent = '';
		this.controller?.abort();
		this.refreshButton.removeEventListener('click', this.refresh);
		this.map?.destroy();
		this.list.destroy();
		this.root.hidden = true;
	}
}
