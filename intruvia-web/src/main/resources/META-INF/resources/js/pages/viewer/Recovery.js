// SPDX-License-Identifier: AGPL-3.0-only
import {openStream} from '../../api/stream.js';
import {getSession} from '../../api/viewer.js';

// Owns socket, retry/probe timers and requests. Browser transport ping/pong is server-owned.
export default class Recovery {
	constructor({cursor, receive, resnapshot, status, sessionEnded}) {
		this.cursor = cursor;
		this.receive = receive;
		this.resnapshot = resnapshot;
		this.status = status;
		this.sessionEnded = sessionEnded;
		this.stopped = false;
		this.attempt = 0;
		this.generation = 0;
		this.closeSocket = null;
		this.retry = null;
		this.probe = null;
		this.controller = null;
		this.deadline = null;
		this.helloDeadline = null;
	}
	start() {
		if (this.stopped) return;
		const generation = ++this.generation;
		this.status('Connecting live updates…');
		try {
			this.closeSocket = openStream(this.cursor(), (frame) => {
				if (this.stopped || generation !== this.generation) return;
				if (frame.version !== 1) throw new Error('Unsupported stream version.');
				if (frame.type === 'resync_required') { this.resync(); return; }
				if (frame.type === 'hello') {
					if (![frame.head, frame.minAfter].every((value) => typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value))) {
						throw new Error('Invalid stream bounds.');
					}
					if (BigInt(this.cursor()) < BigInt(frame.minAfter) || BigInt(this.cursor()) > BigInt(frame.head)) {
						this.resync(); return;
					}
					clearTimeout(this.helloDeadline);
					this.status('Live · Showing recent events.');
					return;
				}
				this.receive(frame);
				// Reset only after progress, so repeatedly failing upgrades/hellos keep backing off.
				this.attempt = 0;
			}, (invalid) => {
				if (this.stopped || generation !== this.generation) return;
				if (invalid) this.resync();
				else this.disconnected();
			});
			this.helloDeadline = setTimeout(() => this.disconnected(), 10000);
			this.probe = setTimeout(() => this.checkSession(generation), 15000);
		} catch { this.disconnected(); }
	}
	async checkSession(generation) {
		const controller = new AbortController();
		this.controller = controller;
		this.deadline = setTimeout(() => controller.abort(), 10000);
		try {
			await getSession(controller.signal);
			if (this.stopped || generation !== this.generation) return;
			this.probe = setTimeout(() => this.checkSession(generation), 15000);
		} catch (error) {
			if (this.stopped || generation !== this.generation) return;
			if (error.status === 401 || error.status === 403) { this.stop(); this.sessionEnded(); }
			else this.disconnected();
		} finally {
			if (this.controller === controller) {
				clearTimeout(this.deadline);
				this.controller = null;
			}
		}
	}
	disconnected() {
		this.clearConnection();
		if (this.stopped) return;
		// Equal jitter: first retry 1–2 s, capped at 15–30 s. One retry at a time.
		const ceiling = Math.min(30000, 2000 * 2 ** Math.min(this.attempt++, 4));
		const delay = ceiling / 2 + Math.random() * ceiling / 2;
		this.status('Reconnecting · Showing previously received events.');
		this.retry = setTimeout(async () => {
			this.retry = null;
			const generation = this.generation;
			const controller = new AbortController();
			this.controller = controller;
			this.deadline = setTimeout(() => controller.abort(), 10000);
			try {
				await getSession(controller.signal);
				if (!this.stopped && generation === this.generation) this.start();
			} catch (error) {
				if (this.stopped || generation !== this.generation) return;
				if (error.status === 401 || error.status === 403) { this.stop(); this.sessionEnded(); }
				else this.disconnected();
			} finally {
				if (this.controller === controller) {
					clearTimeout(this.deadline);
					this.controller = null;
				}
			}
		}, delay);
	}
	resync() {
		this.stop();
		this.resnapshot();
	}
	clearConnection() {
		++this.generation;
		this.closeSocket?.();
		this.closeSocket = null;
		clearTimeout(this.helloDeadline);
		clearTimeout(this.retry);
		clearTimeout(this.probe);
		clearTimeout(this.deadline);
		this.controller?.abort();
		this.controller = null;
	}
	stop() {
		this.stopped = true;
		this.clearConnection();
	}
}
