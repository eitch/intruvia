# SPDX-License-Identifier: AGPL-3.0-only
"""Isolated guide rehearsal. Existing Docker, Java, OpenSSL, Python and optional Playwright only.
No production services/configuration. Temp secrets/backups removed in finally; logs are sanitized.
Run after packaging from the repository root. JAVA_HOME, NODE_BIN, PLAYWRIGHT_MODULE required.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path.cwd()
JAVA = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
BASE = Path(tempfile.mkdtemp(prefix='intruvia-021-'))
DIST = BASE / 'release'
RUNTIME = BASE / 'runtime'
PG = 'intruvia-021-pg-' + str(os.getpid())
PROXY = 'intruvia-021-proxy-' + str(os.getpid())
app = None
logs = []

def run(args, **kwargs):
	result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)
	if result.returncode:
		detail = result.stderr.decode(errors='replace')
		for name in ('password', 'old', 'new'):
			value = globals().get(name)
			if value:
				detail = detail.replace(value, '[REDACTED]')
		raise AssertionError(f'{Path(args[0]).name} exit {result.returncode}: {detail}')
	return result.stdout

def port():
	with socket.socket() as sock:
		sock.bind(('127.0.0.1', 0))
		return sock.getsockname()[1]

def private(path, value):
	path.write_text(value)
	path.chmod(0o600)

def psql(sql, database='postgres'):
	return run(['docker', 'exec', '-i', PG, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', database, '-At'],
			   input=sql.encode()).decode().strip()

def tool(*args, success=True):
	result = subprocess.run([JAVA, '--class-path', 'lib/*', 'docs/deployment/PrivilegeTool.java', str(RUNTIME / 'config'), *map(str, args)],
							cwd=DIST, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
	if (result.returncode == 0) != success:
		raise AssertionError('PrivilegeTool unexpected exit: ' + result.stderr.decode()[:300])
	for output in (result.stdout, result.stderr):
		assert password.encode() not in output
	return result

def stop():
	global app
	if app:
		app.terminate()
		assert app.wait(timeout=120) == 143, 'Expected Java SIGTERM exit 143'
		app = None

def start(database):
	global app
	env = dict(os.environ, DB_URL=f'jdbc:postgresql://127.0.0.1:{dbport}/{database}', DB_USERNAME='intruvia', DB_PASSWORD=password,
			   DB_POOL_maximumPoolSize='10', DB_POOL_connectionTimeout='30000', DB_POOL_validationTimeout='5000')
	log = BASE / ('application-' + str(len(logs)) + '.log')
	logs.append(log)
	with log.open('wb') as output:
		app = subprocess.Popen([JAVA, '-Dintruvia.log.dir=' + str(BASE / 'logs'), '-jar', str(DIST / 'intruvia-app-0.0.1.jar'),
								str(RUNTIME), str(appport)], env=env, cwd=BASE, stdout=output, stderr=output)
	for _ in range(120):
		if app.poll() is not None:
			raise AssertionError('Application failed startup; inspect protected temporary log before cleanup')
		try:
			with urllib.request.urlopen(f'http://127.0.0.1:{appport}/health/ready', timeout=1) as response:
				assert response.status == 200
				return
		except (OSError, urllib.error.URLError):
			time.sleep(0.25)
	raise AssertionError('Readiness timeout')

def request(route, body=None, token=None, expected=200):
	headers = {}
	if token:
		headers['Authorization'] = 'Bearer ' + token
	if body is not None:
		headers['Content-Type'] = 'application/json'
	req = urllib.request.Request(origin + route, data=json.dumps(body).encode() if body is not None else None, headers=headers)
	try:
		response = urllib.request.urlopen(req, context=tls, timeout=40)
	except urllib.error.HTTPError as error:
		response = error
	with response:
		assert response.status == expected, (route, response.status, expected)
		data = response.read()
		return json.loads(data) if data else None

def fingerprint(database):
	# Whole-table fingerprints include event, receipt, stream state, migration metadata and audit data.
	tables = psql("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;", database).splitlines()
	return {table: psql(f'SELECT count(*) || \':\' || md5(coalesce(string_agg(rowtext, E\'\\n\' ORDER BY rowtext), \'\')) '
					   f'FROM (SELECT row_to_json(t)::text AS rowtext FROM "{table}" t) q;', database) for table in tables}

try:
	shutil.copytree(ROOT / 'intruvia-app/target/intruvia', DIST)
	shutil.copytree(DIST / 'runtime', RUNTIME)
	config = RUNTIME / 'config'
	for name in ('PrivilegeConfig', 'PrivilegeRoles', 'PrivilegeUsers'):
		shutil.copyfile(config / (name + '.xml.example'), config / (name + '.xml'))
	prv = config / 'PrivilegeConfig.xml'
	prv.write_text(prv.read_text().replace('CHANGE-ME', secrets.token_hex(32), 1).replace('CHANGE-ME', secrets.token_hex(32), 1))
	password = secrets.token_urlsafe(32)
	pw = BASE / 'password'
	private(pw, password)
	hashed = BASE / 'hash'
	tool('hash', pw, hashed)
	roles = config / 'PrivilegeRoles.xml'
	roles.write_text(roles.read_text().replace('</Roles>', '''
<Role name="Producer"><Privilege name="event:ingest" policy="DefaultPrivilege"><Allow>event:ingest</Allow></Privilege></Role>
<Role name="TokenOperator">
<Privilege name="PrivilegePersonalAccessToken" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege>
<Privilege name="PrivilegePersonalAccessTokenUser" policy="UserAccessPrivilege"><AllAllowed>true</AllAllowed></Privilege>
<Privilege name="PrivilegeAction" policy="DefaultPrivilege"><Allow>Persist</Allow></Privilege></Role></Roles>'''))
	users = config / 'PrivilegeUsers.xml'
	additions = ''.join(f'<User userId="{name}" username="{name}" password="{hashed.read_text().strip()}">'
						f'<Firstname>Rehearsal</Firstname><Lastname>User</Lastname><Locale>en-GB</Locale><State>ENABLED</State>'
						f'<Roles><Role>{role}</Role></Roles></User>'
						for name, role in [('viewer', 'Viewer'), ('producer-a', 'Producer'), ('token-admin', 'TokenOperator')])
	users.write_text(users.read_text().replace('</Users>', additions + '</Users>'))
	appport, tlsport = port(), port()
	origin = f'https://localhost:{tlsport}'
	private(config / 'viewer.properties', 'origin=' + origin + '\n')
	private(config / 'machine-identities.conf', 'producer-a=server-a\n')
	for path in config.iterdir():
		if path.is_file():
			path.chmod(0o600)
	for path in [RUNTIME, config, DIST]:
		path.chmod(0o700)
	from datetime import datetime, timedelta, timezone
	now = datetime.now(timezone.utc)
	startat, endat = [(now + delta).isoformat() for delta in (timedelta(minutes=-1), timedelta(days=30))]
	oldpath, newpath = BASE / 'old.pat', BASE / 'new.pat'
	tool('issue', 'token-admin', pw, 'producer-a', 'initial', startat, endat, oldpath)
	tool('issue', 'token-admin', pw, 'producer-a', 'rotation', startat, endat, newpath)
	old, new = oldpath.read_text().strip(), newpath.read_text().strip()
	assert old != new and newpath.stat().st_mode & 0o777 == 0o600
	tool('issue', 'token-admin', pw, 'producer-a', 'reject-existing', startat, endat, newpath, success=False)
	print('Fresh packaged runtime: configured Strolch hash, Viewer/Producer/TokenOperator; scoped overlapping PATs'
			' persisted, output 0600, overwrite rejected. PASS', flush=True)
	run(['docker', 'run', '-d', '--rm', '--name', PG, '-p', '127.0.0.1::5432', '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', 'postgres:18-bookworm'])
	for _ in range(60):
		if subprocess.run(['docker', 'exec', PG, 'pg_isready', '-U', 'postgres'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
			break
		time.sleep(0.5)
	dbport = run(['docker', 'port', PG, '5432/tcp']).decode().strip().rsplit(':', 1)[1]
	psql(f"CREATE ROLE intruvia LOGIN PASSWORD '{password}';\nCREATE DATABASE intruvia OWNER intruvia;")
	conf = config / 'StrolchConfiguration.xml'
	conf.write_text(conf.read_text().replace('<allowSchemaCreation>false', '<allowSchemaCreation>true'))
	start('intruvia')
	stop()
	conf.write_text(conf.read_text().replace('<allowSchemaCreation>true', '<allowSchemaCreation>false'))
	start('intruvia')
	print('First start creates PostgreSQL schema/model; clean restart with creation/migration/drop false, non-superuser'
			' DB owner; readiness UP with absent MMDB. PASS', flush=True)
	# Disposable self-signed localhost certificate; TLS verification remains enabled in API requests.
	key, cert = BASE / 'key.pem', BASE / 'cert.pem'
	run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(key), '-out', str(cert), '-days', '1',
		 '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'])
	nginx = (ROOT / 'docs/deployment/nginx.conf.example').read_text().replace('listen 443 ssl;', f'listen 127.0.0.1:{tlsport} ssl;')
	nginx = (nginx.replace('intruvia.example.org', 'localhost')
			 .replace('/etc/intruvia/tls/fullchain.pem', str(cert)).replace('/etc/intruvia/tls/key.pem', str(key)))
	nginx = nginx.replace('127.0.0.1:8080', f'127.0.0.1:{appport}')
	(BASE / 'nginx.conf').write_text(nginx)
	proxyargs = ['--network', 'host', '-v', f'{BASE}:{BASE}:ro']
	run(['docker', 'run', '--rm', *proxyargs, 'nginx:stable-bookworm', 'nginx', '-t', '-c', str(BASE / 'nginx.conf')])
	run(['docker', 'run', '-d', '--rm', '--name', PROXY, *proxyargs, 'nginx:stable-bookworm', 'nginx', '-g', 'daemon off;', '-c', str(BASE / 'nginx.conf')])
	tls = ssl.create_default_context(cafile=str(cert))
	for _ in range(30):
		try:
			request('/')
		except json.JSONDecodeError:
			break
		except OSError:
			time.sleep(0.2)
	event = dict(schemaVersion=1, eventId=str(uuid.uuid4()), occurredAt=now.isoformat(), action='ban', ip='2001:db8::42', jail='rehearsal')
	first = request('/api/v1/fail2ban/events', event, old, 201)
	duplicate = request('/api/v1/fail2ban/events', event, new)
	assert first['sequence'] == duplicate['sequence'] == '1' and first['id'] == duplicate['id'] and duplicate['duplicate']
	stop()
	tool('revoke', 'token-admin', pw, old.split(':')[0])
	start('intruvia')
	request('/api/v1/fail2ban/events', event, old, 401)
	assert request('/api/v1/fail2ban/events', event, new)['id'] == first['id']
	print('Verified TLS proxy ingestion 201; rotation same identity produces 200 duplicate; offline revocation persists'
			' across restart and old PAT returns 401. PASS', flush=True)
	stop()
	before = fingerprint('intruvia')
	dump = run(['docker', 'exec', PG, 'pg_dump', '-U', 'intruvia', '-d', 'intruvia', '--format=custom', '--no-owner', '--no-acl'])
	(BASE / 'database.dump').write_bytes(dump)
	with tarfile.open(BASE / 'runtime.tar.gz', 'w:gz') as archive:
		archive.add(RUNTIME, arcname='runtime')
	manifest = {name: hashlib.sha256((BASE / name).read_bytes()).hexdigest() for name in ['database.dump', 'runtime.tar.gz']}
	start('intruvia')
	later = dict(event, eventId=str(uuid.uuid4()))
	assert request('/api/v1/fail2ban/events', later, new, 201)['sequence'] == '2'
	stop()
	psql('CREATE DATABASE intruvia_restored OWNER intruvia;')
	run(['docker', 'exec', '-i', PG, 'pg_restore', '-U', 'intruvia', '--exit-on-error', '--single-transaction', '--no-owner', '--no-acl',
		 '-d', 'intruvia_restored'], input=dump)
	assert fingerprint('intruvia_restored') == before
	shutil.rmtree(RUNTIME)
	with tarfile.open(BASE / 'runtime.tar.gz') as archive:
		archive.extractall(BASE, filter='data')
	assert all(hashlib.sha256((BASE / name).read_bytes()).hexdigest() == digest for name, digest in manifest.items())
	start('intruvia_restored')
	retry = request('/api/v1/fail2ban/events', event, new)
	assert retry['duplicate'] and retry['id'] == first['id'] and retry['sequence'] == '1'
	request('/api/v1/fail2ban/events', dict(event, jail='changed'), new, 409)
	request('/api/v1/fail2ban/events', event, old, 401)
	print('Full custom pg_dump + runtime archive restored to NEW database with all schema flags false; ALL public'
			' tables count/content fingerprints equal. PASS', flush=True)
	print('Restored event ID/sequence and receipt duplicate preserved; changed payload 409; revoked PAT stays 401;'
			' newer head 2 rolled back to 1. PASS', flush=True)
	print('Restored public table fingerprints (synthetic data only): ' + json.dumps(before, sort_keys=True), flush=True)
	pub = run(['openssl', 'x509', '-in', str(cert), '-pubkey', '-noout'])
	der = run(['openssl', 'pkey', '-pubin', '-outform', 'DER'], input=pub)
	settings = BASE / 'settings.json'
	private(settings, json.dumps(dict(origin=origin, password=password, ahead='2', spki=base64.b64encode(hashlib.sha256(der).digest()).decode())))
	browserenv = dict(os.environ, REHEARSAL_SETTINGS=str(settings),
					  XDG_CONFIG_HOME=str(BASE / 'browser-config'), XDG_CACHE_HOME=str(BASE / 'browser-cache'))
	result = run([os.environ['NODE_BIN'], 'docs/verification/021-browser.cjs'], env=browserenv)
	print(result.decode().strip(), flush=True)
	stop()
	assert all(password not in log.read_text() and old not in log.read_text() and new not in log.read_text() for log in logs)
	print('Application logs exclude rehearsal password and PATs. Shutdown succeeded at every maintenance boundary. PASS', flush=True)
	print(run(['docker', 'image', 'inspect', 'nginx:stable-bookworm', 'postgres:18-bookworm',
			   '--format', '{{.RepoTags}} {{.Id}} {{json .RepoDigests}}']).decode().strip())
finally:
	if app:
		app.terminate()
		try:
			app.wait(timeout=120)
		except subprocess.TimeoutExpired:
			app.kill()
			app.wait()
	for container in [PROXY, PG]:
		subprocess.run(['docker', 'rm', '-f', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
	shutil.rmtree(BASE)
	print('Removed disposable containers, runtime, passwords, PATs, certificate keys and backups.', flush=True)
