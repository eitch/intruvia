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
	const fixtureLog = fs.createWriteStream(path.join(output, '015-browser-fixture.txt'));
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
		const errors = [], external = [], mutations = [];
		page.on('pageerror', (error) => errors.push(error.message));
		page.on('request', (request) => {
			if (!request.url().startsWith(origin)) external.push(request.url());
			if (request.method() !== 'GET') mutations.push({method: request.method(), csrf: request.headers()['x-intruvia-csrf']});
		});
		await page.goto(origin);
		await page.locator('#login:not([hidden])').waitFor();
		await page.screenshot({path: path.join(output, '015-login.png')});
		await page.locator('[name=username]').fill('viewer');
		await page.locator('[name=password]').fill('wrong');
		await page.getByRole('button', {name: 'Sign in', exact: true}).click();
		await page.getByText('Sign-in failed. Check your credentials.').waitFor();
		await page.locator('[name=password]').fill('viewer-test-password');
		await page.getByRole('button', {name: 'Sign in', exact: true}).click();
		await page.waitForFunction(() => document.querySelectorAll('#events > li').length === 1000);
		assert.match(await page.locator('#window').innerText(), /1,000.*1,000/);
		assert.match(await page.locator('#window').innerText(), /Older events omitted/);
		assert.equal(await page.locator('#username').innerText(), 'viewer');
		assert.equal(await page.locator('[name=password]').inputValue(), '');
		assert.ok(await page.locator('#map svg path').count() > 170);
		assert.equal(await page.locator('#map .leaflet-interactive').count(), 167);
		assert.match(await page.locator('#geo-notice').innerText(), /degraded GeoIP/);
		for (const reason of ['Non-public address', 'No location record', 'GeoIP lookup failed',
			'GeoIP database unavailable', 'No coordinates in location record']) {
			assert.ok(await page.locator('#events').getByText(`Unmapped: ${reason}`, {exact: true}).count() > 0);
		}
		assert.match(await page.locator('#status').innerText(), /Live updates are not connected/);
		assert.equal(await page.evaluate(() => document.getAnimations().length), 0);
		await page.getByRole('button', {name: 'Show on map', exact: true}).first().focus();
		await page.keyboard.press('Enter');
		await page.locator('.leaflet-popup-content').waitFor();
		assert.match(await page.locator('.leaflet-popup-content').innerText(), /<img src=x onerror=window.pwned=1>/);
		assert.match(await page.locator('.leaflet-popup-content').innerText(), /25 km/);
		assert.equal(await page.locator('.leaflet-popup-content img').count(), 0);
		assert.equal(await page.evaluate(() => window.pwned), undefined);
		assert.equal(await page.evaluate(() => localStorage.length), 0);
		await page.waitForFunction(() => getComputedStyle(document.querySelector('.leaflet-popup')).opacity === '1');
		await page.screenshot({path: path.join(output, '015-snapshot.png'), fullPage: true});
		await page.reload();
		await page.waitForFunction(() => document.querySelectorAll('#events > li').length === 1000);
		await page.route('**/api/v1/events/snapshot?limit=1000', (route) => route.fulfill({status: 503, json: {}}));
		await page.getByRole('button', {name: 'Refresh snapshot'}).click();
		await page.getByText('Refresh failed. Showing the previous snapshot. Try again.').waitFor();
		assert.equal(await page.locator('#events > li').count(), 1000);
		await page.unroute('**/api/v1/events/snapshot?limit=1000');
		await page.route('**/api/v1/events/snapshot?limit=1000', (route) => route.fulfill({json: {
			items: [], cursor: '1005', minAfter: '0', truncated: false
		}}));
		await page.getByRole('button', {name: 'Refresh snapshot'}).click();
		await page.locator('#empty:not([hidden])').waitFor();
		assert.equal(await page.locator('#events > li').count(), 0);
		assert.equal(await page.locator('#map .leaflet-interactive').count(), 0);
		await page.screenshot({path: path.join(output, '015-empty.png')});
		await page.unroute('**/api/v1/events/snapshot?limit=1000');
		await page.getByRole('button', {name: 'Log out', exact: true}).click();
		await page.locator('#login:not([hidden])').waitFor();
		assert.equal(await page.locator('#events > li').count(), 0);
		assert.equal(await page.locator('#map svg').count(), 0);
		// Real HTTP permissions, not client role simulation.
		await page.locator('[name=username]').fill('denied');
		await page.locator('[name=password]').fill('viewer-test-password');
		await page.getByRole('button', {name: 'Sign in', exact: true}).click();
		await page.getByText(/Access denied/).waitFor();
		assert.ok(await page.locator('#viewer').isHidden());
		await page.locator('[name=username]').fill('viewer');
		await page.getByRole('button', {name: 'Sign in', exact: true}).click();
		await page.waitForFunction(() => document.querySelectorAll('#events > li').length === 1000);
		await page.setViewportSize({width: 390, height: 844});
		await page.reload();
		await page.waitForFunction(() => document.querySelectorAll('#events > li').length === 1000);
		await page.screenshot({path: path.join(output, '015-mobile.png'), fullPage: true});
		assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
		assert.equal(await page.locator('#map .leaflet-interactive').count(), 167);
		// Expired-cookie response returns the viewer to login and clears protected data.
		await context.clearCookies();
		await page.getByRole('button', {name: 'Refresh snapshot'}).click();
		await page.locator('#login:not([hidden])').waitFor();
		assert.equal(await page.locator('#events > li').count(), 0);
		assert.deepEqual(errors, []);
		assert.deepEqual(external, []);
		assert.ok(mutations.every((request) => request.csrf === '1'));
		console.log(JSON.stringify({browser: await browser.version(), result: 'PASS', events: 1000, markers: 167,
			checks: ['real login/session reload/logout/denied role/expired cookie', 'wrong password',
				'bounded snapshot, partial and all unmapped reasons, zero coordinates, details popup keyboard action',
				'inert HTML-like text, no browser credentials stored, no initial animations',
				'refresh error preserves data, empty rerender clears markers, repeated map creation',
				'mobile layout', 'no external network requests or JavaScript errors', 'CSRF headers'],
			screenshots: ['015-login.png', '015-snapshot.png', '015-empty.png', '015-mobile.png']}, null, 2));
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
