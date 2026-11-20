// SPDX-License-Identifier: AGPL-3.0-only
// Uses the existing real-service fixture and locally installed Playwright only.
const {chromium} = require(process.env.PLAYWRIGHT_MODULE);
const {spawn, spawnSync} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
	const root = process.cwd();
	const fixture = spawn(path.join(process.env.JAVA_HOME, 'bin/java'), ['-cp',
		'target/test-classes:target/classes:target/intruvia/lib/*:' + process.env.JUNIT_JAR,
		'li.intruvia.app.AdminBrowserFixture'], {cwd: path.join(root, 'intruvia-app'), stdio: ['pipe', 'pipe', 'pipe']});
	let browser;
	let fixtureErrors = '';
	fixture.stderr.on('data', (data) => { fixtureErrors += data; });
	try {
		const origin = await new Promise((resolve, reject) => {
			let text = '';
			const timeout = setTimeout(() => reject(new Error('Admin browser fixture startup timed out\n' + fixtureErrors)), 30000);
			fixture.stdout.on('data', (data) => {
				text += data;
				const match = text.match(/ADMIN_BROWSER_FIXTURE=(http:\/\/127\.0\.0\.1:\d+)/);
				if (match) { clearTimeout(timeout); resolve(match[1]); }
			});
			fixture.on('exit', (code) => {
				clearTimeout(timeout);
				reject(new Error(`Admin browser fixture exited: ${code}\n${fixtureErrors.slice(-8000)}`));
			});
		});
		browser = await chromium.launch({executablePath: '/usr/bin/chromium', headless: true, args: ['--no-sandbox']});
		const page = await browser.newPage({viewport: {width: 1440, height: 1000}});
		page.setDefaultTimeout(15000);
		const errors = [], external = [], mutations = [];
		page.on('pageerror', (error) => errors.push(error.message));
		page.on('request', (request) => {
			if (!request.url().startsWith(origin)) external.push(request.url());
			if (['POST', 'PUT', 'DELETE'].includes(request.method()))
				mutations.push({method: request.method(), csrf: request.headers()['x-intruvia-csrf']});
		});
		page.on('dialog', (dialog) => { void dialog.accept(); });
		async function signIn(username, password) {
			await page.locator('#login:not([hidden])').waitFor();
			await page.locator('#login [name=username]').fill(username);
			await page.locator('#login [name=password]').fill(password);
			await page.getByRole('button', {name: 'Sign in', exact: true}).click();
			await page.locator('#account:not([hidden])').waitFor();
			assert.equal(await page.locator('#username').innerText(), username);
		}
		const user = () => page.locator('#admin-users > li').filter({hasText: 'browser-viewer'});
		const machine = () => page.locator('#admin-machines > li').filter({hasText: 'browser-machine'});
		await page.goto(origin);
		await signIn('admin', 'admin-test-password');
		await page.locator('#manage:not([hidden])').waitFor();
		await page.getByRole('button', {name: 'Manage access', exact: true}).click();
		await page.locator('#admin:not([hidden])').waitFor();
		await page.getByText('Changes take effect immediately. Ingestion tokens are shown only when created.',
			{exact: true}).waitFor();

		await page.locator('#create-user-form [name=username]').fill('browser-viewer');
		await page.locator('#create-user-form [name=firstName]').fill('Browser');
		await page.locator('#create-user-form [name=lastName]').fill('Viewer');
		await page.locator('#create-user-form [name=password]').fill('browser-initial-password');
		await page.getByRole('button', {name: 'Create account', exact: true}).click();
		await user().waitFor();
		await user().getByLabel('New password for browser-viewer').fill('browser-updated-password');
		await user().getByRole('button', {name: 'Set password', exact: true}).click();
		await page.getByText('Password updated for browser-viewer.', {exact: true}).waitFor();
		await user().getByRole('button', {name: 'Disable account', exact: true}).click();
		await page.getByText('Account browser-viewer updated.', {exact: true}).waitFor();
		await page.waitForFunction(() => [...document.querySelectorAll('#admin-users > li')]
			.some((item) => item.textContent.includes('browser-viewer') && item.textContent.includes('DISABLED')));
		await user().getByRole('button', {name: 'Enable account', exact: true}).click();
		await page.getByText('Account browser-viewer updated.', {exact: true}).waitFor();
		await page.waitForFunction(() => [...document.querySelectorAll('#admin-users > li')]
			.some((item) => item.textContent.includes('browser-viewer') && item.textContent.includes('ENABLED')));

		await page.locator('#create-machine-form [name=username]').fill('browser-machine');
		await page.locator('#create-machine-form [name=instanceId]').fill('browser-server');
		await page.locator('#create-machine-form [name=tokenName]').fill('fail2ban');
		await page.locator('#create-machine-form [name=validDays]').fill('30');
		await page.getByRole('button', {name: 'Add machine', exact: true}).click();
		await machine().waitFor();
		await page.getByText('Machine browser-machine is ready. Copy its token now; it cannot be shown again.',
			{exact: true}).waitFor();
		const firstToken = await page.locator('#issued-token').inputValue();
		if (!firstToken.includes(':') || firstToken.length < 40) throw new Error('Machine token was not shown in the one-time field');
		if (!(await page.locator('#install-command-container').isHidden()) ||
			!(await page.locator('#install-unavailable').isVisible()))
			throw new Error('HTTP administration must not offer an insecure installer command');
		if (!(await machine().innerText()).includes('event:ingest'))
			throw new Error('Machine token list does not show the restricted ingestion scope');

		// Exercise the HTTPS-only UI against the local HTTP fixture without altering server networking.
		await page.route('**/js/pages/AdminPage.js', async (route) => {
			const response = await route.fetch();
			const body = (await response.text()).replace('fail2banInstallCommand(window.location.origin)',
				"fail2banInstallCommand('https://intruvia.example.org')");
			await route.fulfill({response, body});
		});
		await page.addInitScript(() => {
			Object.defineProperty(navigator, 'clipboard', {value: {writeText: async (value) => { window.copiedText = value; }}});
		});
		await page.reload();
		await page.locator('#manage:not([hidden])').waitFor();
		await page.getByRole('button', {name: 'Manage access', exact: true}).click();
		await machine().waitFor();

		await machine().getByLabel('Instance ID for browser-machine').fill('browser-server-updated');
		await machine().getByRole('button', {name: 'Save machine identity', exact: true}).click();
		await page.getByText('Identity updated for browser-machine; ingestion uses it immediately.', {exact: true}).waitFor();
		if (!(await machine().innerText()).includes('browser-server-updated'))
			throw new Error('Machine identity was not updated in the management list');

		await machine().getByLabel('New token name for browser-machine').fill('rotation');
		await machine().getByLabel('New token validity in days for browser-machine').fill('30');
		await machine().getByRole('button', {name: 'Create another token', exact: true}).click();
		await page.getByText('New token for browser-machine created. Copy it now; it cannot be shown again.',
			{exact: true}).waitFor();
		const rotatedToken = await page.locator('#issued-token').inputValue();
		if (!rotatedToken.includes(':') || rotatedToken.length < 40 || rotatedToken === firstToken)
			throw new Error('Token rotation did not issue a distinct one-time secret');
		const tokenCheck = spawnSync('python3', ['-c',
			"import runpy,sys; runpy.run_path('integrations/fail2ban/download-install.py')['read_token'](sys.stdin)"],
			{cwd: root, input: rotatedToken + '\n', encoding: 'utf8'});
		if (tokenCheck.status !== 0) throw new Error('Installer rejected a real issued machine token');
		const download = await page.request.get(`${origin}/api/v1/fail2ban/install.py`,
			{headers: {Authorization: `Bearer ${rotatedToken}`}});
		assert.equal(download.status(), 200);
		assert.equal(download.headers()['cache-control'], 'no-store');
		const script = await download.text();
		assert.ok(script.startsWith('#!/usr/bin/env python3') && !script.includes('__INTRUVIA_') && !script.includes(rotatedToken));
		const compile = spawnSync('python3', ['-c', "import sys; compile(sys.stdin.read(), '<download>', 'exec')"],
			{input: script, encoding: 'utf8'});
		if (compile.status !== 0) throw new Error('Downloaded installer is not valid Python');
		const deniedDownload = await page.request.get(`${origin}/api/v1/fail2ban/install.py`,
			{headers: {Authorization: 'Bearer invalid'}});
		assert.equal(deniedDownload.status(), 401);
		const installCommand = await page.locator('#install-command').inputValue();
		assert.ok(await page.locator('#install-command-container').isVisible());
		assert.ok(installCommand.includes('https://intruvia.example.org/api/v1/fail2ban/install.py'));
		assert.ok(!installCommand.includes(firstToken) && !installCommand.includes(rotatedToken));
		await page.getByRole('button', {name: 'Copy installation command', exact: true}).click();
		assert.equal(await page.evaluate(() => window.copiedText), installCommand);
		const visibleMachine = await machine().innerText();
		if (visibleMachine.includes(firstToken) || visibleMachine.includes(rotatedToken))
			throw new Error('A plaintext token was included in the machine list');

		await machine().locator('ul > li').filter({hasText: 'fail2ban'})
			.getByRole('button', {name: 'Revoke token', exact: true}).click();
		await page.getByText('Token fail2ban revoked.', {exact: true}).waitFor();
		await machine().locator('ul > li').filter({hasText: 'rotation'})
			.getByRole('button', {name: 'Revoke token', exact: true}).click();
		await page.getByText('Token rotation revoked.', {exact: true}).waitFor();
		await machine().getByRole('button', {name: 'Remove machine', exact: true}).click();
		await page.getByText('Machine browser-machine removed.', {exact: true}).waitFor();
		if (await machine().count() !== 0) throw new Error('Machine removal did not update the management list');

		await page.getByRole('button', {name: 'Back to events', exact: true}).click();
		await page.locator('#viewer:not([hidden])').waitFor();
		if (!(await page.locator('#issued-secret').isHidden()) || await page.locator('#issued-token').inputValue() !== '')
			throw new Error('The one-time token remained visible after leaving administration');
		await page.getByRole('button', {name: 'Log out', exact: true}).click();
		await page.locator('#login:not([hidden])').waitFor();
		const viewerAdminRequest = page.waitForResponse((response) =>
			response.url().endsWith('/api/v1/admin/users') && response.status() === 403);
		await signIn('browser-viewer', 'browser-updated-password');
		await viewerAdminRequest;
		if (!(await page.locator('#manage').isHidden())) throw new Error('Viewer account unexpectedly received the access-management control');
		await page.getByRole('button', {name: 'Log out', exact: true}).click();
		await page.locator('#login:not([hidden])').waitFor();
		await signIn('admin', 'admin-test-password');
		await page.locator('#manage:not([hidden])').waitFor();
		await page.getByRole('button', {name: 'Manage access', exact: true}).click();
		await page.locator('#admin:not([hidden])').waitFor();
		await user().waitFor();
		await user().getByRole('button', {name: 'Delete account', exact: true}).click();
		await page.getByText('Account browser-viewer deleted.', {exact: true}).waitFor();
		if (await user().count() !== 0) throw new Error('Viewer deletion did not update the management list');
		assert.deepEqual(errors, []);
		assert.deepEqual(external, []);
		assert.ok(mutations.length >= 9);
		assert.ok(mutations.every((request) => request.csrf === '1'));

		const report = JSON.stringify({browser: browser.version(), result: 'PASS', checks: [
			'Admin login and browser-only access management; viewer role cannot access the UI',
			'Create viewer, change password, disable, enable, sign in with updated password, and delete',
			'Create machine and scoped token, update live instance identity, rotate and revoke tokens, remove machine',
			'Copy HTTPS installation command, refuse HTTP, download self-contained installer with real scoped PAT',
			'Token shown only in the one-time field and cleared when leaving administration',
			'CSRF header on every browser mutation; no JavaScript errors or external requests'
		]}, null, 2) + '\n';
		if (!process.env.INTRUVIA_BROWSER_NO_REPORT) fs.writeFileSync(path.join(root, 'docs/verification/023-admin-browser.txt'), report);
		console.log(report);
	} finally {
		await browser?.close();
		fixture.stdin.end();
		await new Promise((resolve) => {
			const timeout = setTimeout(() => { fixture.kill('SIGTERM'); resolve(); }, 15000);
			fixture.on('exit', () => { clearTimeout(timeout); resolve(); });
		});
	}
})().catch((error) => { console.error(error); process.exitCode = 1; });