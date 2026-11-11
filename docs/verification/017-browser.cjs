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
	const fixtureLog = fs.createWriteStream(path.join(output, '017-browser-fixture.txt'));
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
		page.setDefaultTimeout(15000);
		const errors = [], external = [];
		page.on('pageerror', (error) => errors.push(error.message));
		page.on('request', (request) => { if (!request.url().startsWith(origin)) external.push(request.url()); });
		await page.addInitScript(() => {
			window.sockets = []; window.frames = []; window.dropEvents = false; window.dropHello = false;
			const NativeSocket = window.WebSocket;
			window.WebSocket = class extends NativeSocket {
				constructor(...args) {
					super(...args); window.testSocket = this; window.sockets.push(this.url);
					this.addEventListener('message', (message) => {
						const frame = JSON.parse(message.data);
						window.frames.push(frame);
						if ((window.dropEvents && frame.type === 'event.created') || (window.dropHello && frame.type === 'hello')) {
							message.stopImmediatePropagation();
						}
					});
				}
			};
		});
		let acknowledgements = '';
		fixture.stdout.on('data', (data) => { acknowledgements += data; });
		async function command(value, expected) {
			fixture.stdin.write(value + '\n');
			const deadline = Date.now() + 10000;
			while (!acknowledgements.includes('FIXTURE_DONE=' + expected)) {
				if (Date.now() > deadline) throw Error('Fixture command timed out: ' + value);
				await new Promise((resolve) => setTimeout(resolve, 20));
			}
		}
		const live = () => page.getByText('Live · Showing recent events.', {exact: true}).waitFor();
		const row = (sequence) => page.locator('#events > li').filter({hasText: 'live-fixture-' + sequence});
		async function signIn() {
			await page.locator('[name=username]').fill('viewer');
			await page.locator('[name=password]').fill('viewer-test-password');
			await page.getByRole('button', {name: 'Sign in', exact: true}).click();
			await live();
		}
		// Focused timer tests use the production recovery coordinator with virtual timer registration.
		await page.goto(origin);
		const delays = await page.evaluate(async () => {
			const {default: Recovery} = await import('/js/pages/viewer/Recovery.js');
			const nativeSet = window.setTimeout, nativeClear = window.clearTimeout;
			const pending = new Map(), delays = [];
			let id = 0;
			window.setTimeout = (callback, delay) => { delays.push(delay); pending.set(++id, callback); return id; };
			window.clearTimeout = (timer) => pending.delete(timer);
			try {
				const recovery = new Recovery({cursor: () => '0', receive() {}, resnapshot() {}, status() {}, sessionEnded() {}});
				for (let attempt = 0; attempt < 8; attempt++) {
					recovery.disconnected();
					if (pending.size !== 1) throw Error('Multiple pending retries');
				}
				recovery.stop();
				if (pending.size !== 0) throw Error('Retry timer leaked after stop');
				return delays;
			} finally { window.setTimeout = nativeSet; window.clearTimeout = nativeClear; }
		});
		for (let index = 0; index < delays.length; index++) {
			const ceiling = Math.min(30000, 2000 * 2 ** Math.min(index, 4));
			assert.ok(delays[index] >= ceiling / 2 && delays[index] <= ceiling);
		}
		// Capture a real snapshot then commit before the browser can open its stream.
		await page.route('**/api/v1/events/snapshot?limit=1000', async (route) => {
			const response = await route.fetch();
			await command('mapped', '1006');
			await route.fulfill({response});
			await page.unroute('**/api/v1/events/snapshot?limit=1000');
		});
		await page.goto(origin);
		await signIn();
		await row(1006).waitFor();
		assert.equal(await row(1006).getAttribute('class'), 'event-new');
		assert.match((await page.evaluate(() => window.sockets))[0], /after=1005$/);
		// Disconnect before a durable commit: replay must recover the missing event.
		await page.evaluate(() => window.testSocket.close());
		await page.getByText('Reconnecting · Showing previously received events.', {exact: true}).waitFor();
		await command('unmapped', '1007');
		await row(1007).waitFor(); await live();
		assert.match((await page.evaluate(() => window.sockets)).at(-1), /after=1006$/);
		// Commit/frame arrives but is deliberately lost before application; cursor must not advance.
		await page.evaluate(() => { window.dropEvents = true; });
		await command('mapped', '1008');
		await page.waitForFunction(() => window.frames.some((frame) => frame.sequence === '1008'));
		assert.equal(await row(1008).count(), 0);
		await page.evaluate(() => { window.dropEvents = false; window.testSocket.close(); });
		await row(1008).waitFor(); await live();
		assert.match((await page.evaluate(() => window.sockets)).at(-1), /after=1007$/);
		await page.waitForFunction(() => document.querySelectorAll('.event-new,.event-halo').length === 0);
		await page.evaluate(() => {
			const frame = window.frames.find((frame) => frame.sequence === '1008');
			window.testSocket.dispatchEvent(new MessageEvent('message', {data: JSON.stringify(frame)}));
		});
		assert.equal(await page.locator('.event-new,.event-halo').count(), 0);
		// Exercise the visibility contract in headless Chromium; server delivery remains real.
		await page.evaluate(() => {
			Object.defineProperty(document, 'hidden', {configurable: true, value: true});
			document.dispatchEvent(new Event('visibilitychange'));
		});
		await command('mapped', '1009'); await row(1009).waitFor();
		await command('unmapped', '1010'); await row(1010).waitFor();
		await page.evaluate(() => {
			Object.defineProperty(document, 'hidden', {configurable: true, value: false});
			document.dispatchEvent(new Event('visibilitychange'));
		});
		assert.equal(await page.locator('#away-notice').innerText(), '2 new events received while away.');
		const idleSockets = await page.evaluate(() => window.sockets.length);
		await page.waitForTimeout(26000);
		assert.equal(await page.evaluate(() => window.sockets.length), idleSockets, 'healthy idle socket must survive session probes');
		// A connected socket with a lost hello must not stay Connecting forever.
		let stalledBefore = await page.evaluate(() => window.sockets.length);
		await page.evaluate(() => { window.dropHello = true; window.testSocket.close(); });
		await page.waitForFunction((count) => window.sockets.length === count + 1, stalledBefore);
		await page.getByText('Connecting live updates…', {exact: true}).waitFor();
		await page.getByText('Reconnecting · Showing previously received events.', {exact: true}).waitFor();
		await page.evaluate(() => { window.dropHello = false; });
		await live();
		assert.ok(await page.evaluate((count) => window.sockets.length >= count + 2, stalledBefore));
		// Ahead cursor (restore of an older database): real server rejects modified snapshot cursor.
		await page.route('**/api/v1/events/snapshot?limit=1000', async (route) => {
			const response = await route.fetch(), json = await response.json();
			await route.fulfill({json: {...json, cursor: '999999'}});
			await page.unroute('**/api/v1/events/snapshot?limit=1000');
		});
		let before = await page.evaluate(() => window.sockets.length);
		await page.getByRole('button', {name: 'Refresh snapshot'}).click();
		await page.getByText('Some older live updates are no longer available.', {exact: true}).waitFor();
		await page.waitForFunction((count) => window.sockets.length >= count + 2, before);
		await live();
		assert.equal(await page.locator('.event-new,.event-halo').count(), 0);
		// Real atomic prefix pruning after disconnection, plus one failed recovery snapshot.
		await page.evaluate(() => { window.dropEvents = true; });
		await command('mapped', '1011');
		await page.waitForFunction(() => window.frames.some((frame) => frame.sequence === '1011'));
		await page.evaluate(() => { window.dropEvents = false; window.testSocket.close(); });
		await command('prune', 'prune');
		let snapshotAttempts = 0;
		await page.route('**/api/v1/events/snapshot?limit=1000', async (route) => {
			snapshotAttempts++;
			if (snapshotAttempts === 1) await route.fulfill({status: 503, json: {}});
			else await route.continue();
		});
		await page.getByText('Offline · Retrying snapshot recovery. Previously received events remain visible.', {exact: true}).waitFor();
		assert.equal(await page.locator('#events > li').count(), 1000);
		await live();
		assert.ok(snapshotAttempts >= 2);
		assert.equal(await page.locator('#events > li').count(), 0);
		assert.ok(await page.locator('#empty').isVisible());
		await page.unroute('**/api/v1/events/snapshot?limit=1000');
		await command('mapped', '1012'); await row(1012).waitFor();
		await page.screenshot({path: path.join(output, '017-recovered.png'), fullPage: true});
		// Logout while a reconnect is pending must cancel its timer and all future requests.
		await page.evaluate(() => window.testSocket.close());
		await page.getByText('Reconnecting · Showing previously received events.', {exact: true}).waitFor();
		await page.getByRole('button', {name: 'Log out', exact: true}).click();
		await page.locator('#login:not([hidden])').waitFor();
		before = await page.evaluate(() => window.sockets.length);
		await page.waitForTimeout(2500);
		assert.equal(await page.evaluate(() => window.sockets.length), before);
		assert.equal(await page.locator('#events > li,.event-halo').count(), 0);
		await signIn();
		// Cookie loss on an otherwise valid idle socket is detected by the session probe.
		await context.clearCookies();
		await page.locator('#login:not([hidden])').waitFor({timeout: 25000});
		assert.match(await page.locator('#status').innerText(), /Your session ended/);
		await signIn();
		// Fixed maximum lifetime, advanced in the real server validator, no browser response mock.
		await command('expire', 'expire');
		await page.locator('#login:not([hidden])').waitFor();
		assert.match(await page.locator('#status').innerText(), /Your session ended/);
		before = await page.evaluate(() => window.sockets.length);
		await page.waitForTimeout(2500);
		assert.equal(await page.evaluate(() => window.sockets.length), before);
		assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0);
		assert.deepEqual(errors, []); assert.deepEqual(external, []);
		console.log(JSON.stringify({result: 'PASS', browser: await browser.version(), snapshotAttempts, retryDelaysMs: delays,
			sockets: await page.evaluate(() => window.sockets), checks: [
				'real snapshot-to-stream commit gap', 'disconnect before commit', 'commit lost before apply replays from applied cursor',
				'duplicates never repulse', 'hidden visibility contract retains model/count on return',
				'ahead cursor real-server resync and visible snapshot notice', 'real prefix pruning resync',
				'failed recovery snapshot preserves old model then automatically retries', 'snapshot no pulses and empty recovery',
				'healthy idle socket survives probes', 'lost hello deadline reconnects', 'bounded jitter and single pending timer',
				'logout cancels pending reconnect', 'idle cookie loss session probe', 'real session maximum-lifetime expiry navigates to login',
				'no cursor/credentials in browser storage', 'no script errors or external requests']}, null, 2));
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
