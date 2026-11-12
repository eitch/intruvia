// SPDX-License-Identifier: AGPL-3.0-only
// Optional verification with the existing Playwright installation, no Node build/dependencies.
const {chromium} = require(process.env.PLAYWRIGHT_MODULE);
const {spawn} = require('node:child_process');
const fs = require('node:fs');
const assert = require('node:assert/strict');
(async () => {
	const fixture = spawn(process.env.JAVA_HOME + '/bin/java', ['-Xmx8g', '-cp',
		'target/test-classes:target/intruvia/intruvia-app-0.0.1.jar:target/intruvia/lib/*:' + process.env.JUNIT_JAR,
		'li.intruvia.app.CapacityBrowserFixture', process.env.CAPACITY_MMDB], {cwd: 'intruvia-app', stdio: ['pipe', 'pipe', 'pipe']});
	const log = fs.createWriteStream('docs/verification/020-capacity-fixture.txt');
	fixture.stdout.pipe(log); fixture.stderr.pipe(log);
	let output = '', browser;
	fixture.stdout.on('data', (data) => { output += data; });
	async function waitFor(check, timeout) {
		const deadline = Date.now() + timeout;
		while (!check()) {
			if (fixture.exitCode !== null) throw Error('Fixture exited: ' + fixture.exitCode);
			if (Date.now() > deadline) throw Error('Fixture timeout; tail: ' + output.slice(-1000));
			await new Promise((resolve) => setTimeout(resolve, 200));
		}
	}
	try {
		await waitFor(() => output.includes('CAPACITY_FIXTURE='), 180000);
		const origin = output.match(/CAPACITY_FIXTURE=(http:\/\/127.0.0.1:\d+)/)[1];
		browser = await chromium.launch({executablePath: '/usr/bin/chromium', headless: true,
			args: ['--no-sandbox', '--disable-background-timer-throttling', '--disable-renderer-backgrounding', '--disable-backgrounding-occluded-windows']});
		console.log('Browser:', browser.version());
		const pages = [], errors = [], external = [];
		const sustained = Number(process.env.CAPACITY_EVENTS || 3000), expected = sustained + 100;
		for (let index = 0; index < 2; index++) {
			const context = await browser.newContext({viewport: {width: 1440, height: 1000}});
			const page = await context.newPage(); pages.push(page);
			page.on('pageerror', (error) => errors.push(error.message));
			page.on('request', (request) => { if (!request.url().startsWith(origin)) external.push(request.url()); });
			await page.addInitScript(() => {
				window.measurements = []; window.sequences = new Set(); window.measureFailures = [];
				const NativeSocket = window.WebSocket;
				window.WebSocket = class extends NativeSocket {
					constructor(...args) {
						super(...args);
						this.addEventListener('message', (message) => {
							const received = Date.now();
							const frame = JSON.parse(message.data);
							if (frame.type !== 'event.created') return;
							if (window.sequences.has(frame.sequence)) { window.measureFailures.push('duplicate ' + frame.sequence); return; }
							window.sequences.add(frame.sequence);
							requestAnimationFrame(() => {
								const row = document.querySelector(`#events > li[data-event-id="${frame.event.id}"]`);
								const halo = document.querySelector(`.event-halo[data-event-id="${frame.event.id}"]`);
								if (!row?.classList.contains('event-new') || !halo) window.measureFailures.push('missing highlight ' + frame.sequence);
								window.measurements.push({sequence: frame.sequence, instance: frame.event.source.instanceId,
									occurredAt: frame.event.occurredAt, receivedAt: frame.event.receivedAt,
									endToEnd: Date.now() - Date.parse(frame.event.occurredAt),
									serverToFrame: Date.now() - Date.parse(frame.event.receivedAt), receiptToFrame: Date.now() - received});
							});
						});
					}
				};
			});
			await page.goto(origin);
			await page.locator('[name=username]').fill('producer');
			await page.locator('[name=password]').fill('test-password');
			await page.getByRole('button', {name: 'Sign in', exact: true}).click();
			await page.getByText('Live · Showing recent events.', {exact: true}).waitFor();
			assert.equal(await page.locator('#events > li').count(), 1000);
			assert.equal(await page.locator('.event-new,.event-halo').count(), 0);
		}
		console.log(`Starting ${sustained} arrivals scheduled at 10/s, then concurrent 100-event burst; 100000 seeded retained events.`);
		const started = Date.now(); fixture.stdin.write('load\n');
		await waitFor(() => output.includes('LOAD_DONE'), 420000);
		await new Promise((resolve) => setTimeout(resolve, 3000));
		console.log('Elapsed milliseconds:', Date.now() - started);
		console.log(output.slice(output.indexOf('COUNTS=')));
		const results = [];
		const percentile = (items) => [...items].sort((a, b) => a - b)[Math.ceil(items.length * 0.95) - 1];
		for (const [index, page] of pages.entries()) {
			const result = await page.evaluate(() => ({items: window.measurements, failures: window.measureFailures,
				hidden: document.hidden, rows: document.querySelectorAll('#events > li').length}));
			// Preserve raw measurements before evaluating the gate, including failed runs.



			const summary = {browser: index + 1, samples: result.items.length,
				endToEndP95: percentile(result.items.map((item) => item.endToEnd)),
				receiptP95: percentile(result.items.map((item) => item.receiptToFrame)),
				serverP95: percentile(result.items.map((item) => item.serverToFrame)),
				sustainedP95: percentile(result.items.slice(0, sustained).map((item) => item.endToEnd)),
				burstP95: percentile(result.items.slice(sustained).map((item) => item.endToEnd)),
				sustainedSendSpanMs: Math.max(...result.items.slice(0, sustained).map((item) => Date.parse(item.occurredAt))) -
						Math.min(...result.items.slice(0, sustained).map((item) => Date.parse(item.occurredAt)))};
			console.log(JSON.stringify(summary)); results.push({summary, ...result});
			await page.screenshot({path: `docs/verification/020-capacity-${index + 1}.png`});
		}
		fs.writeFileSync('docs/verification/020-capacity-measurements.json', JSON.stringify(results, null, 2) + '\n');
		assert.deepEqual(errors, []); assert.deepEqual(external, []);
		for (const result of results) {
			assert.deepEqual(result.failures, []); assert.equal(result.rows, 1000); assert.equal(result.hidden, false);
			assert.equal(result.items.length, expected);
			assert.equal(new Set(result.items.map((item) => item.sequence)).size, expected);
			assert.deepEqual([...new Set(result.items.map((item) => item.instance))].sort(), ['server-a', 'server-b']);
			for (let offset = 0; offset < result.items.length; offset++) assert.equal(result.items[offset].sequence, String(100001 + offset));
			assert.ok(result.summary.endToEndP95 <= 1000); assert.ok(result.summary.receiptP95 <= 100);
		}
		assert.ok(!output.includes('HTTP_FAILURE='));
		assert.ok(output.includes(`COUNTS=100000,${expected},`));
		console.log('PASS: ordered unique delivery, two identities/two browsers, bounded rows/storage and p95 targets.');
	} finally {
		if (browser) await browser.close();
		if (!output.includes('LOAD_DONE') && fixture.exitCode === null) fixture.kill('SIGTERM');
		fixture.stdin.end();
		await new Promise((resolve) => { if (fixture.exitCode !== null) resolve(); else fixture.once('exit', resolve); });
		log.end();
	}
})().catch((error) => { console.error(error); process.exitCode = 1; });
