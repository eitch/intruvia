// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import {execFileSync, spawn, spawnSync} from 'node:child_process';
import * as fs from 'node:fs';
import * as https from 'node:https';
import * as path from 'node:path';
import {test} from 'node:test';
import {fail2banInstallCommand} from '../main/resources/META-INF/resources/js/pages/fail2banInstall.js';

const origin = 'https://intruvia.example.org';

test('only secure origins produce a token-free command matching the documentation', () => {
	const command = fail2banInstallCommand(origin);
	assert.equal(fail2banInstallCommand('http://localhost'), null);
	assert.equal(fail2banInstallCommand('https://intruvia.example.org/path'), null);
	assert.ok(!command.includes('sample-token'));
	assert.ok(command.includes(`${origin}/api/v1/fail2ban/install.py`));
	assert.ok(fs.readFileSync('integrations/fail2ban/README.md', 'utf8').includes(command));
	assert.equal(spawnSync('bash', ['-n'], {input: command}).status, 0);
});

test('command passes token on stdin, propagates failures and removes its private download', () => {
	fs.mkdirSync('target', {recursive: true});
	const dir = fs.mkdtempSync(path.resolve('target/fail2ban-command-'));
	try {
		const bin = path.join(dir, 'bin');
		const tmp = path.join(dir, 'tmp');
		fs.mkdirSync(bin);
		fs.mkdirSync(tmp);
		fs.writeFileSync(path.join(bin, 'curl'), `#!/bin/bash
printf '%s\n' "$@" > "$TEST_DIR/curl-args"
IFS= read -r header
printf '%s\n' "$header" > "$TEST_DIR/header"
while [ "$#" -gt 0 ]; do if [ "$1" = --output ]; then shift; output=$1; fi; shift; done
printf 'downloaded script\n' > "$output"
stat -c '%a' "$output" > "$TEST_DIR/mode"
printf '%s' "$CURL_STATUS"
exit "${'${CURL_EXIT:-0}'}"
`, {mode: 0o755});
		fs.writeFileSync(path.join(bin, 'sudo'), `#!/bin/bash
printf '%s\n' "$@" > "$TEST_DIR/sudo-args"
IFS= read -r token
printf '%s\n' "$token" > "$TEST_DIR/token"
exit "${'${SUDO_EXIT:-0}'}"
`, {mode: 0o755});
		const env = {...process.env, PATH: `${bin}:${process.env.PATH}`, TMPDIR: tmp, TEST_DIR: dir};
		const command = fail2banInstallCommand(origin);
		const run = (extra) => spawnSync('bash', ['-c', command], {input: 'sample-token:secret\n', encoding: 'utf8', env: {...env, ...extra}});
		assert.equal(run({CURL_STATUS: '200'}).status, 0);
		assert.equal(fs.readFileSync(path.join(dir, 'header'), 'utf8'), 'Authorization: Bearer sample-token:secret\n');
		assert.equal(fs.readFileSync(path.join(dir, 'token'), 'utf8'), 'sample-token:secret\n');
		assert.equal(fs.readFileSync(path.join(dir, 'mode'), 'utf8').trim(), '600');
		assert.ok(!fs.readFileSync(path.join(dir, 'curl-args'), 'utf8').includes('sample-token'));
		assert.ok(!fs.readFileSync(path.join(dir, 'sudo-args'), 'utf8').includes('sample-token'));
		assert.deepEqual(fs.readdirSync(tmp), []);
		fs.unlinkSync(path.join(dir, 'sudo-args'));
		assert.notEqual(run({CURL_STATUS: '302'}).status, 0);
		assert.equal(fs.existsSync(path.join(dir, 'sudo-args')), false);
		assert.notEqual(run({CURL_STATUS: '401', CURL_EXIT: '22'}).status, 0);
		assert.equal(fs.existsSync(path.join(dir, 'sudo-args')), false);
		assert.equal(run({CURL_STATUS: '200', SUDO_EXIT: '17'}).status, 17);
		assert.deepEqual(fs.readdirSync(tmp), []);
	} finally {
		fs.rmSync(dir, {recursive: true, force: true});
	}
});

test('real curl sends the token over verified TLS and runs the downloaded Python', async () => {
	fs.mkdirSync('target', {recursive: true});
	const dir = fs.mkdtempSync(path.resolve('target/fail2ban-tls-'));
	const bin = path.join(dir, 'bin');
	fs.mkdirSync(bin);
	let server;
	try {
		const key = path.join(dir, 'key.pem');
		const cert = path.join(dir, 'cert.pem');
		execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
			'-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost', '-keyout', key, '-out', cert],
			{stdio: 'ignore'});
		fs.writeFileSync(path.join(bin, 'sudo'), '#!/bin/sh\nexec "$@"\n', {mode: 0o755});
		const received = [];
		server = https.createServer({key: fs.readFileSync(key), cert: fs.readFileSync(cert)}, (request, response) => {
			received.push({url: request.url, authorization: request.headers.authorization});
			response.writeHead(200, {'Content-Type': 'text/x-python'});
			response.end("import sys\nassert sys.stdin.read() == 'example-id:example-secret\\n'\n");
		});
		await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
		const url = `https://localhost:${server.address().port}`;
		const env = {...process.env, PATH: `${bin}:${process.env.PATH}`, TMPDIR: dir,
			CURL_CA_BUNDLE: cert, NO_PROXY: 'localhost', no_proxy: 'localhost'};
		const run = (extra) => new Promise((resolve) => {
			const child = spawn('bash', ['-c', fail2banInstallCommand(url)], {env: {...env, ...extra}, stdio: ['pipe', 'pipe', 'pipe']});
			let errors = '';
			child.stderr.on('data', (data) => { errors += data; });
			child.on('close', (status) => resolve({status, errors}));
			child.stdin.end('example-id:example-secret\n');
		});
		const success = await run({});
		assert.equal(success.status, 0, success.errors);
		assert.deepEqual(received, [{url: '/api/v1/fail2ban/install.py', authorization: 'Bearer example-id:example-secret'}]);
		const untrusted = await run({CURL_CA_BUNDLE: '/dev/null'});
		assert.notEqual(untrusted.status, 0);
		assert.equal(received.length, 1);
	} finally {
		if (server) await new Promise((resolve) => server.close(resolve));
		fs.rmSync(dir, {recursive: true, force: true});
	}
});