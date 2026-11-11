// SPDX-License-Identifier: AGPL-3.0-only
// Uses an already installed Playwright; no project Node dependencies or frontend build.
const {chromium} = require(process.env.PLAYWRIGHT_MODULE);
const {spawn} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
	const root = process.cwd();
	const output = path.join(root, 'docs/verification');
	const fixtureLog = fs.createWriteStream(path.join(output, '016-browser-fixture.txt'));
	const fixture = spawn(path.join(process.env.JAVA_HOME, 'bin/java'), ['-cp',
		'target/test-classes:target/classes:target/intruvia/lib/*:' + process.env.JUNIT_JAR,
		'li.intruvia.app.ViewerBrowserFixture'], {cwd: path.join(root, 'intruvia-app'), stdio: ['pipe', 'pipe', 'pipe']});
	fixture.stdout.pipe(fixtureLog);
	fixture.stderr.pipe(fixtureLog);
	let browser;
	try {
		const origin = await new Promise((resolve, reject) => {
			let text = '';
			const timeout = setTimeout(() => reject(new Error('Fixture startup timed out')), 30000);
			fixture.stdout.on('data', (data) => {
				text += data;
				const match = text.match(/BROWSER_FIXTURE=(http:\/\/127.0.0.1:\d+)/);
				if (match) { clearTimeout(timeout); resolve(match[1]); }
			});
			fixture.on('exit', (code) => { clearTimeout(timeout); reject(new Error(`Fixture exited: ${code}`)); });
		});
		browser = await chromium.launch({executablePath: '/usr/bin/chromium', headless: true, args: ['--no-sandbox']});
		const context = await browser.newContext({viewport: {width: 1440, height: 1000}});
		const page = await context.newPage();
		const errors = [], external = [];
		page.on('pageerror', (error) => errors.push(error.message));
		page.on('request', (request) => { if (!request.url().startsWith(origin)) external.push(request.url()); });
		// Observe receipt before the application's handler; record the following animation-frame opportunity.
		await page.addInitScript(() => {
			window.receipts = new Map(); window.latencies = []; window.frames = []; window.highlightStarts = new Map(); window.durations = new Map();
			const NativeSocket = window.WebSocket;
			window.WebSocket = class extends NativeSocket {
				constructor(...args) {
					super(...args); window.testSocket = this;
					this.addEventListener('message', (message) => {
						const frame = JSON.parse(message.data);
						if (frame.type !== 'event.created' || !frame.event) return;
						window.frames.push(frame);
						window.receipts.set(frame.event.id, performance.now());
					});
				}
			};
			new MutationObserver((records) => {
				for (const record of records) {
					const row = record.target;
					if (record.type !== 'attributes' || row.tagName !== 'LI') continue;
					const id = row.dataset.eventId;
					if (row.classList.contains('event-new') && !window.highlightStarts.has(id)) window.highlightStarts.set(id, performance.now());
					if (!row.classList.contains('event-new') && window.highlightStarts.has(id)) {
						window.durations.set(id, performance.now() - window.highlightStarts.get(id));
					}
				}
				for (const row of document.querySelectorAll('#events li.event-new')) {
					const id = row.dataset.eventId, receipt = window.receipts.get(id);
					if (!window.highlightStarts.has(id)) window.highlightStarts.set(id, performance.now());
					if (receipt === undefined) continue;
					window.receipts.delete(id);
					requestAnimationFrame(() => window.latencies.push(performance.now() - receipt));
				}
			}).observe(document, {subtree: true, childList: true, attributes: true, attributeFilter: ['class']});
		});
		await page.goto(origin);
		await page.locator('[name=username]').fill('viewer');
		await page.locator('[name=password]').fill('viewer-test-password');
		await page.getByRole('button', {name: 'Sign in', exact: true}).click();
		await page.getByText('Live · Showing recent events.', {exact: true}).waitFor();
		assert.equal(await page.locator('#events > li').count(), 1000);
		assert.equal(await page.locator('.event-halo,.event-new').count(), 0);
		assert.equal(await page.locator('.overlap-count').innerText(), '167');
		// Move away with real map controls; arrivals must leave the viewport alone.
		await page.locator('#map').dragTo(page.locator('header'));
		const panePosition = await page.locator('.leaflet-map-pane').getAttribute('style');
		fixture.stdin.write('mapped\n');
		await page.waitForFunction(() => window.frames.length === 1);
		const first = await page.evaluate(() => window.frames[0]);
		await page.locator(`[data-event-id="${first.event.id}"].event-new`).waitFor();
		assert.equal(await page.locator('.event-halo').count(), 1);
		assert.equal(await page.locator('.leaflet-map-pane').getAttribute('style'), panePosition);
		const mapBox = await page.locator('#map').boundingBox();
		const ringBox = await page.locator('.event-halo').boundingBox();
		assert.ok(ringBox.y + ringBox.height < mapBox.y || ringBox.y > mapBox.y + mapBox.height ||
			ringBox.x + ringBox.width < mapBox.x || ringBox.x > mapBox.x + mapBox.width, 'arrival should be off-screen');
		assert.equal(await page.locator('#events > li').count(), 1000);
		assert.equal(await page.locator('.overlap-count').innerText(), '167'); // oldest mapped event evicted
		await page.locator('#events > li').first().getByRole('button', {name: 'Show on map'}).focus();
		await page.keyboard.press('Enter');
		await page.locator('.leaflet-popup-content').waitFor();
		assert.match(await page.locator('.leaflet-popup-content').innerText(), /live-fixture-1006/);
		fixture.stdin.write('mapped\nunmapped\n');
		await page.waitForFunction(() => window.frames.length === 3);
		assert.equal(await page.locator('.event-halo').count(), 2);
		assert.equal(new Set(await page.locator('.event-halo').evaluateAll((nodes) => nodes.map((node) => node.dataset.eventId))).size, 2);
		assert.equal(await page.locator('#events > li.event-new').count(), 3);
		assert.equal(await page.locator('.overlap-count').innerText(), '168');
		assert.match(await page.locator('#events > li').first().innerText(), /Unmapped: Non-public address/);
		assert.match(await page.locator('#window').innerText(), /832 unmapped/);
		await page.screenshot({path: path.join(output, '016-overlap.png'), fullPage: true});
		// Duplicate the actual received frame at the browser message boundary.
		await page.evaluate(() => {
			const frame = window.frames[0];
			window.testSocket.dispatchEvent(new MessageEvent('message', {data: JSON.stringify(frame)}));
		});
		assert.equal(await page.locator('.event-halo').count(), 2);
		assert.equal(await page.locator('#events > li.event-new').count(), 3);
		await page.waitForFunction(() => document.querySelectorAll('.event-halo,.event-new').length === 0);
		await page.evaluate(() => window.testSocket.dispatchEvent(new MessageEvent('message', {data: JSON.stringify(window.frames[0])})));
		assert.equal(await page.locator('.event-halo,.event-new').count(), 0);
		const duration = await page.evaluate((id) => window.durations.get(id), first.event.id);
		assert.ok(duration >= 2900 && duration <= 3400, `three-second indication lasted ${duration}ms`);
		await page.emulateMedia({reducedMotion: 'reduce'});
		fixture.stdin.write('mapped\n');
		await page.locator('.event-halo').waitFor();
		assert.equal(await page.locator('.event-halo').evaluate((node) => getComputedStyle(node).animationName), 'none');
		await page.screenshot({path: path.join(output, '016-reduced-motion.png'), fullPage: true});
		await page.waitForFunction(() => document.querySelectorAll('.event-halo,.event-new').length === 0);
		await page.emulateMedia({reducedMotion: 'no-preference'});
		// Foreground receipt-to-next-frame measurement, 30 durable arrivals at 10/s.
		await page.evaluate(() => { window.latencies = []; });
		for (let index = 0; index < 30; index++) {
			fixture.stdin.write('mapped\n');
			await new Promise((resolve) => setTimeout(resolve, 100));
		}
		await page.waitForFunction(() => window.latencies.length === 30);
		const latencies = await page.evaluate(() => window.latencies);
		latencies.sort((a, b) => a - b);
		const p95 = latencies[Math.ceil(latencies.length * .95) - 1];
		assert.ok(p95 <= 100, `receipt-to-highlight p95 ${p95}ms exceeds 100ms`);
		assert.equal(await page.locator('#events > li').count(), 1000);
		await page.getByRole('button', {name: 'Refresh snapshot'}).click();
		await page.getByText('Live · Showing recent events.', {exact: true}).waitFor();
		assert.equal(await page.locator('.event-halo,.event-new').count(), 0);
		await page.setViewportSize({width: 390, height: 844});
		fixture.stdin.write('unmapped\n');
		await page.locator('#events > li.event-new').waitFor();
		assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
		// Pure model edge cases share the production module, including full-width sequence precision.
		await page.evaluate(async () => {
			const {applyFrame} = await import('/js/utils/live.js');
			const event = {...window.frames[0].event, sequence: '9007199254740993', id: 'precision'};
			const change = applyFrame({items: [], cursor: '9007199254740992', truncated: false},
				{version: 1, type: 'event.created', sequence: event.sequence, event});
			if (change.model.cursor !== event.sequence) throw Error('Sequence precision lost');
			if (applyFrame(change.model, {version: 1, type: 'event.created', sequence: event.sequence, event}) !== null) throw Error('Duplicate');
		});
		await page.evaluate(() => window.testSocket.dispatchEvent(new MessageEvent('message', {
			data: JSON.stringify({version: 2, type: 'event.created'})
		})));
		await page.getByText('Some older live updates are no longer available.', {exact: true}).waitFor();
		await page.getByText('Live · Showing recent events.', {exact: true}).waitFor();
		await page.evaluate(() => window.testSocket.close());
		await page.getByText('Reconnecting · Showing previously received events.', {exact: true}).waitFor();
		await page.getByText('Live · Showing recent events.', {exact: true}).waitFor();
		await page.getByRole('button', {name: 'Log out', exact: true}).click();
		await page.locator('#login:not([hidden])').waitFor();
		assert.equal(await page.locator('#events > li,.event-halo').count(), 0);
		assert.equal(await page.evaluate(() => window.testSocket.readyState), 3);
		assert.deepEqual(errors, []); assert.deepEqual(external, []);
		console.log(JSON.stringify({result: 'PASS', browser: await browser.version(), sampleCount: latencies.length,
			latenciesMs: latencies, p95Ms: p95, firstHighlightDurationMs: duration, measurement: 'WebSocket message receipt to next requestAnimationFrame with highlighted DOM',
			checks: ['real session/snapshot/WebSocket durable polling', 'no snapshot pulses', 'independent overlapping rings/count/identities',
				'keyboard show-on-map and unchanged viewport on arrival', 'unmapped list highlight/count', '1000-event eviction',
				'no repulse during or after three seconds', 'static reduced-motion halo', 'refresh and logout cleanup',
				'full-width sequences', 'invalid-frame snapshot recovery and automatic reconnect', 'mobile', 'no external requests or script errors']}, null, 2));
	} finally {
		await browser?.close();
		fixture.stdin.end();
		await new Promise((resolve) => {
			const timeout = setTimeout(() => { fixture.kill('SIGTERM'); resolve(); }, 15000);
			fixture.on('exit', () => { clearTimeout(timeout); resolve(); });
		});
		fixtureLog.end();
	}
})().catch((error) => { console.error(error); process.exitCode = 1; });
