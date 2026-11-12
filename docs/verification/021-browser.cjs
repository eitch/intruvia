// SPDX-License-Identifier: AGPL-3.0-only
// Existing optional browser tooling only; invoked by 021-rehearsal.py.
const {chromium} = require(process.env.PLAYWRIGHT_MODULE);
const fs = require('node:fs');
const assert = require('node:assert/strict');
(async () => {
	const settings = JSON.parse(fs.readFileSync(process.env.REHEARSAL_SETTINGS));
	const trusted = await chromium.launch({executablePath: '/usr/bin/chromium', headless: true,
		args: ['--no-sandbox', '--ignore-certificate-errors-spki-list=' + settings.spki]});
	try {
		const context = await trusted.newContext();
		const page = await context.newPage();
		page.setDefaultTimeout(20000);
		const errors = [], external = [];
		page.on('pageerror', (error) => errors.push(error.message));
		page.on('request', (request) => { if (!request.url().startsWith(settings.origin)) external.push(request.url()); });
		await page.addInitScript((ahead) => {
			const Native = window.WebSocket;
			window.rehearsalFrames = [];
			let first = true;
			window.WebSocket = class extends Native {
				constructor(url) {
					if (first) { url = String(url).replace(/after=\d+/, 'after=' + ahead); first = false; }
					super(url);
					this.addEventListener('message', (message) => window.rehearsalFrames.push(JSON.parse(message.data)));
				}
			};
		}, settings.ahead);
		await page.goto(settings.origin);
		await page.locator('[name=username]').fill('viewer');
		await page.locator('[name=password]').fill(settings.password);
		await page.getByRole('button', {name: 'Sign in', exact: true}).click();
		await page.getByText('Some older live updates are no longer available', {exact: false}).waitFor();
		await page.getByText('Live · Showing recent events.', {exact: true}).waitFor();
		assert.equal(await page.locator('#events > li').count(), 1);
		const cookies = await context.cookies();
		assert.ok(cookies.some((cookie) => cookie.secure && cookie.httpOnly && cookie.sameSite === 'Strict' && cookie.path === '/api/v1'));
		const state = await page.evaluate(() => ({frames: window.rehearsalFrames, local: {...localStorage}}));
		assert.ok(state.frames.some((frame) => frame.type === 'resync_required'));
		assert.ok(state.frames.some((frame) => frame.type === 'hello' && frame.head === '1'));
		assert.deepEqual(state.local, {});
		assert.deepEqual(errors, []); assert.deepEqual(external, []);
		await page.screenshot({path: 'docs/verification/021-restored-browser.png', fullPage: true});
		console.log('Chromium ' + trusted.version() + ': trusted HTTPS login, Secure cookie, WSS ahead cursor 2 -> resync_required -> snapshot head 1 -> live; ' +
				'visible gap notice, 1 row, no page errors/external requests/localStorage. PASS');
	} finally { await trusted.close(); }
})().catch((error) => { console.error(error); process.exitCode = 1; });
