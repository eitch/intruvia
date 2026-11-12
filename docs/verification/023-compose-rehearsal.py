# SPDX-License-Identifier: AGPL-3.0-only
"""Exercise the packaged Compose deployment with disposable local resources."""
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import http.client
import io
import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import socket
import ssl
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[2]
DIST = ROOT / 'intruvia-app/target/intruvia'
ENV = {key: os.environ[key] for key in (
		'PATH', 'HOME', 'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG', 'DOCKER_CERT_PATH',
		'DOCKER_TLS_VERIFY', 'XDG_RUNTIME_DIR', 'LANG') if key in os.environ}
FILES = {
		'.env', 'Caddyfile', 'LICENSE', 'LICENSE-Strolch', 'README.md', 'compose.yaml', 'db/init.sh',
		'docs/DEPLOYMENT.md', 'docs/deployment/compose-initialization-recovery.md',
		'integrations/fail2ban/README.md', 'integrations/fail2ban/intruvia.py',
		'integrations/fail2ban/intruvia_notify.py', 'integrations/fail2ban/jail.local.example',
		'runtime-templates/config/StrolchConfiguration.xml', 'runtime-templates/config/PrivilegeConfig.xml.example',
		'runtime-templates/config/PrivilegeRoles.xml.example', 'runtime-templates/config/PrivilegeUsers.xml.example',
		'runtime-templates/config/ingestion.properties.example'}
DIRECTORIES = {
		'.', 'db', 'docs', 'docs/deployment', 'integrations', 'integrations/fail2ban',
		'runtime-templates', 'runtime-templates/config', 'secrets'}
REDACT = []


def run(args, timeout=600, input=None, cwd=ROOT):
	args = [str(arg) for arg in args]
	print('+ ' + shlex.join(args), flush=True)
	result = subprocess.run(args, cwd=cwd, env=ENV, input=input, capture_output=True, timeout=timeout)
	print(f'exit {result.returncode}', flush=True)
	if result.returncode:
		for data in (result.stdout, result.stderr):
			text = data.decode(errors='replace')
			for secret in REDACT:
				text = text.replace(secret, '[REDACTED]')
			print(text, end='', file=sys.stderr, flush=True)
		raise RuntimeError('command failed')
	return result.stdout


def digest(path):
	hash_value = hashlib.sha256()
	with path.open('rb') as source:
		for chunk in iter(lambda: source.read(1024 * 1024), b''):
			hash_value.update(chunk)
	return hash_value.hexdigest()


def require(condition, message):
	if not condition:
		raise RuntimeError(message)


def inspect_archive(archive, image, extracted):
	checksum = archive.with_name(archive.name + '.sha256').read_text()
	require(checksum == f'{digest(archive)}  {archive.name}\n', 'archive checksum mismatch')
	print(f'Archive SHA-256: {digest(archive)}', flush=True)
	with tarfile.open(archive, 'r:gz') as bundle:
		members = bundle.getmembers()
		names = [str(Path(member.name)) for member in members]
		require(len(names) == len(set(names)), 'duplicate archive paths')
		require({name for name, member in zip(names, members) if member.isfile()} == FILES, 'archive file allowlist mismatch')
		require({name for name, member in zip(names, members) if member.isdir()} == DIRECTORIES, 'archive directory allowlist mismatch')
		require(all((member.isfile() or member.isdir()) and member.uid == 0 and member.gid == 0 for member in members),
				'archive contains links, special files, or unexpected ownership')
		bundle.extractall(extracted, filter='data')
		for member in members:
			if member.isdir():
				(extracted / member.name).chmod(member.mode & 0o777)
	env_text = (extracted / '.env').read_text()
	require(env_text == (ROOT / 'deployment/.env.example').read_text().replace('INTRUVIA_IMAGE=\n', f'INTRUVIA_IMAGE={image}\n'),
			'bundled environment differs from the template beyond the image reference')
	require(all(f'{key}=' in env_text.splitlines() for key in ('POSTGRES_ADMIN_PASSWORD', 'INTRUVIA_DB_PASSWORD', 'INTRUVIA_PUBLIC_ORIGIN')),
			'bundled sensitive settings are not empty')
	require(stat.S_IMODE((extracted / '.env').stat().st_mode) == 0o600, 'bundled .env is not 0600')
	require(stat.S_IMODE((extracted / 'secrets').stat().st_mode) == 0o700 and not list((extracted / 'secrets').iterdir()),
			'bundled secrets directory is not empty and private')
	for relative in ('Caddyfile', 'compose.yaml', 'db/init.sh'):
		source = ROOT / 'deployment' / relative
		target = extracted / relative
		require(source.read_bytes() == target.read_bytes(), f'bundled {relative} differs from deployment source')
		require(stat.S_IMODE(source.stat().st_mode) == stat.S_IMODE(target.stat().st_mode), f'bundled {relative} mode differs')
	require((extracted / 'runtime-templates/config/PrivilegeConfig.xml.example').read_text().count('CHANGE-ME') >= 2,
			'privilege template placeholders missing')
	print('PASS: archive checksum, exact allowlist, empty secrets, placeholders, default Caddyfile and file modes', flush=True)


def build_derived(image, base, identifier):
	dockerfile = f'FROM {base}\nLABEL intruvia.rehearsal="{identifier}"\n'.encode()
	run(['docker', 'build', '--tag', image, '-'], input=dockerfile)
	image_id = json.loads(run(['docker', 'image', 'inspect', image], timeout=30))[0]['Id']
	print(f'Built {image}: {image_id}', flush=True)
	return image_id


def listener_port():
	with socket.socket() as sock:
		sock.bind(('127.0.0.1', 0))
		return sock.getsockname()[1]


class Rehearsal:
	def __init__(self, directory, project, port, image, db_image, proxy_image, passwords):
		self.directory = directory
		self.project = project
		self.port = port
		self.origin = f'https://localhost:{port}'
		self.viewer, self.operator, admin, database = passwords
		self.env_file = directory / '.env'
		self.env_file.write_text(f'INTRUVIA_IMAGE={image}\nINTRUVIA_PUBLIC_ORIGIN={self.origin}\n'
				f'POSTGRES_ADMIN_USERNAME=intruvia_admin\nPOSTGRES_ADMIN_PASSWORD={admin}\n'
				f'INTRUVIA_DB_NAME=intruvia\nINTRUVIA_DB_USERNAME=intruvia\nINTRUVIA_DB_PASSWORD={database}\n')
		self.env_file.chmod(0o600)
		self.override = directory / 'rehearsal-override.yaml'
		self.override.write_text(f'services:\n  db:\n    image: {db_image}\n  caddy:\n    image: {proxy_image}\n'
				f'    ports: !override\n      - "127.0.0.1:{port}:{port}"\n')
		self.prefix = ['docker', 'compose', '--project-name', project, '--env-file', str(self.env_file),
				'-f', str(directory / 'compose.yaml'), '-f', str(self.override)]
		self.tls = None

	def compose(self, *args, input=None, timeout=600):
		return run([*self.prefix, *args], input=input, timeout=timeout, cwd=self.directory)

	def prepare_secrets(self, image):
		directory = self.directory / 'secrets'
		for name, value in [('viewer-password', self.viewer), ('operator-password', self.operator)]:
			path = directory / name
			path.write_text(value)
			path.chmod(0o600)
		self.chown_secrets(image, '10001:10001')

	def chown_secrets(self, image, owner):
		run(['docker', 'run', '--rm', '--user', '0:0', '--entrypoint', 'chown',
				'--mount', f'type=bind,src={self.directory / "secrets"},dst=/secrets', image,
				'-R', owner, '/secrets'], timeout=60)

	def maintenance(self, *args, input=None):
		return self.compose('run', '--rm', '--no-deps', '-T', *args, input=input)

	def offline(self, *args):
		return self.maintenance('app', 'offline', *args)

	def secret(self, name):
		return self.maintenance('--entrypoint', 'cat', 'app', '/run/secrets/' + name).decode().strip()

	def runtime_archive(self):
		return self.maintenance('--entrypoint', 'tar', 'app', '-C', '/var/lib/intruvia', '-czf', '-', 'runtime')

	def wait(self):
		try:
			self.compose('up', '-d', '--wait', '--wait-timeout', '300', timeout=360)
		except RuntimeError:
			try:
				self.compose('stop', 'app', timeout=240)
				logs = self.compose('logs', '--no-color', '--tail', '80', 'app').decode(errors='replace')
				for secret in REDACT:
					logs = logs.replace(secret, '[REDACTED]')
				print('Sanitized application startup diagnostics:\n' + logs, file=sys.stderr, flush=True)
				status = self.maintenance('--entrypoint', 'sh', 'app', '-c',
						'for p in /var/lib/intruvia/runtime /var/lib/intruvia/runtime/.intruvia-* '
						'/run/secrets /run/secrets/viewer-password /opt/intruvia/templates '
						'/opt/intruvia/templates/config /opt/intruvia/templates/config/*; do '
						'if [ -e "$p" ]; then stat -c "%a %u:%g %n" "$p"; fi; done; '
						'ls -la /run/secrets /opt/intruvia /var/lib/intruvia/runtime')
				print('Startup file metadata:\n' + status.decode(), file=sys.stderr, flush=True)
			except RuntimeError:
				print('Unable to collect application startup diagnostics', file=sys.stderr, flush=True)
			raise

	def stop(self):
		self.compose('stop', 'caddy', 'app', timeout=240)

	def trust_ca(self):
		ca = self.compose('exec', '-T', 'caddy', 'cat', '/data/caddy/pki/authorities/local/root.crt')
		path = self.directory / 'local-ca.pem'
		path.write_bytes(ca)
		self.tls = ssl.create_default_context(cafile=str(path))

	def request(self, route, body=None, headers=None, expected=200):
		connection = http.client.HTTPSConnection('localhost', self.port, context=self.tls, timeout=40)
		request_headers = dict(headers or {})
		if body is not None:
			request_headers['Content-Type'] = 'application/json'
		try:
			connection.request('POST' if body is not None else 'GET', route,
					json.dumps(body).encode() if body is not None else None, request_headers)
			response = connection.getresponse()
			data = response.read()
			require(response.status == expected, f'{route}: expected {expected}, got {response.status}')
			return data, response.getheaders()
		finally:
			connection.close()

	def login(self):
		body, headers = self.request('/api/v1/session', {'username': 'viewer', 'password': self.viewer},
				{'Origin': self.origin, 'X-Intruvia-CSRF': '1'})
		require(json.loads(body)['username'] == 'viewer', 'viewer login failed')
		cookies = [value for name, value in headers if name.lower() == 'set-cookie']
		require(len(cookies) == 1 and all(flag in cookies[0] for flag in ('Secure', 'HttpOnly', 'SameSite=Strict', 'Path=/api/v1')),
				'session cookie flags missing')
		return cookies[0].split(';', 1)[0]

	def websocket(self, cookie):
		key = base64.b64encode(secrets.token_bytes(16)).decode()
		with socket.create_connection(('localhost', self.port), timeout=15) as raw:
			with self.tls.wrap_socket(raw, server_hostname='localhost') as sock:
				request = (f'GET /api/v1/events/stream?after=0 HTTP/1.1\r\nHost: localhost:{self.port}\r\n'
						f'Origin: {self.origin}\r\nCookie: {cookie}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
						f'Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n')
				sock.sendall(request.encode())
				stream = sock.makefile('rb')
				status = stream.readline()
				headers = {}
				while True:
					line = stream.readline()
					if line == b'\r\n':
						break
					require(line and b':' in line, 'invalid WebSocket handshake')
					name, value = line.decode().split(':', 1)
					headers[name.lower()] = value.strip()
				expected = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
				require(b' 101 ' in status and headers.get('sec-websocket-accept') == expected, 'WSS upgrade failed')
				first = stream.read(2)
				require(len(first) == 2 and first[0] & 15 == 1 and not first[1] & 128, 'invalid WSS frame')
				length = first[1] & 127
				if length == 126:
					length = int.from_bytes(stream.read(2), 'big')
				elif length == 127:
					length = int.from_bytes(stream.read(8), 'big')
				require(length <= 65536 and json.loads(stream.read(length))['type'] == 'hello', 'WSS hello missing')
		print('PASS: verified local CA HTTPS viewer login, secure cookie and WSS 101/hello', flush=True)

	def ingest(self, event, token, expected):
		data, _ = self.request('/api/v1/fail2ban/events', event, {'Authorization': 'Bearer ' + token}, expected)
		return json.loads(data)


def provision(rehearsal):
	rehearsal.offline('hash', '/run/secrets/operator-password', '/run/secrets/operator.hash')
	hashed = rehearsal.secret('operator.hash')
	REDACT.append(hashed)
	archive = rehearsal.runtime_archive()
	with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as source:
		roles = source.extractfile('runtime/config/PrivilegeRoles.xml').read().decode()
		users = source.extractfile('runtime/config/PrivilegeUsers.xml').read().decode()
	roles = roles.replace('</Roles>', '''
<Role name="Producer"><Privilege name="event:ingest" policy="DefaultPrivilege"><Allow>event:ingest</Allow></Privilege></Role>
<Role name="TokenOperator"><Privilege name="PrivilegePersonalAccessToken" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege>
<Privilege name="PrivilegePersonalAccessTokenUser" policy="UserAccessPrivilege"><AllAllowed>true</AllAllowed></Privilege>
<Privilege name="PrivilegeAction" policy="DefaultPrivilege"><Allow>Persist</Allow></Privilege></Role></Roles>''')
	additions = ''.join(f'<User userId="{name}" username="{name}" password="{hashed}">'
			f'<Firstname>Rehearsal</Firstname><Lastname>User</Lastname><Locale>en-GB</Locale><State>ENABLED</State>'
			f'<Roles><Role>{role}</Role></Roles></User>' for name, role in [('producer-a', 'Producer'), ('token-admin', 'TokenOperator')])
	users = users.replace('</Users>', additions + '</Users>')
	buffer = io.BytesIO()
	with tarfile.open(fileobj=buffer, mode='w') as bundle:
		for name, text in [('PrivilegeRoles.xml', roles), ('PrivilegeUsers.xml', users), ('machine-identities.conf', 'producer-a=server-a\n')]:
			content = text.encode()
			info = tarfile.TarInfo(name)
			info.mode = 0o600
			info.size = len(content)
			bundle.addfile(info, io.BytesIO(content))
	rehearsal.maintenance('--entrypoint', 'tar', 'app', '-C', '/var/lib/intruvia/runtime/config', '--no-same-owner', '-xf', '-', input=buffer.getvalue())
	now = datetime.now(timezone.utc)
	start, end = [(now + delta).isoformat() for delta in (timedelta(minutes=-1), timedelta(days=1))]
	for name in ('old', 'new'):
		rehearsal.offline('issue', 'token-admin', '/run/secrets/operator-password', 'producer-a', name, start, end, f'/run/secrets/{name}.pat')
		token = rehearsal.secret(name + '.pat')
		REDACT.append(token)
	print('PASS: provisioned operator/producer and issued two PATs while application stopped', flush=True)
	return rehearsal.secret('old.pat'), rehearsal.secret('new.pat')


def psql(rehearsal, sql):
	command = 'export PGPASSWORD="$INTRUVIA_DB_PASSWORD"; exec psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$INTRUVIA_DB_USERNAME" -d "$POSTGRES_DB" -At'
	return rehearsal.compose('exec', '-T', 'db', 'sh', '-c', command, input=sql.encode()).decode().strip()


def fingerprint(rehearsal):
	tables = psql(rehearsal, "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;").splitlines()
	require(bool(tables), 'database has no public tables')
	result = {}
	for table in tables:
		quoted = '"' + table.replace('"', '""') + '"'
		result[table] = psql(rehearsal, f"SELECT count(*) || ':' || md5(coalesce(string_agg(rowtext, E'\\n' ORDER BY rowtext), '')) "
				f'FROM (SELECT row_to_json(t)::text AS rowtext FROM {quoted} t) q;')
	return result


def archive_member_hash(archive, name):
	with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as bundle:
		return hashlib.sha256(bundle.extractfile(name).read()).hexdigest()


def main():
	os.umask(0o077)
	identifier = uuid.uuid4().hex
	image = f'intruvia-023-{identifier}:1.0'
	container = f'intruvia-023-audit-{identifier}'
	version = '1.0-' + identifier
	work = None
	image_may_exist = False
	container_created = False
	preexisting_ids = set()
	cleanup_errors = []
	images = []
	rehearsals = []
	try:
		applications = sorted(DIST.glob('intruvia-app-*.jar'))
		libraries = sorted((DIST / 'lib').glob('*.jar'))
		require(len(applications) == 1 and libraries and list((DIST / 'lib').glob('strolch-*.jar')),
				'prebuilt distribution is missing its application or runtime libraries')
		with zipfile.ZipFile(applications[0]) as jar:
			names = set(jar.namelist())
			require({'li/intruvia/app/DeploymentEntrypoint.class', 'PrivilegeTool.class'} <= names,
					'prebuilt application JAR lacks container entrypoint or offline privilege tool')
		print(f'Prebuilt application: {applications[0]} sha256:{digest(applications[0])}; {len(libraries)} libraries', flush=True)
		run(['docker', 'info', '--format', '{{.ServerVersion}}'], timeout=30)
		preexisting_ids = set(run(['docker', 'image', 'ls', '--all', '--no-trunc', '--quiet'], timeout=30).decode().splitlines())
		require(not run(['docker', 'image', 'ls', '--quiet', image], timeout=30).strip(), 'generated image tag already exists')
		require(not run(['docker', 'container', 'ls', '--all', '--quiet', '--filter', f'name=^/{container}$'], timeout=30).strip(),
				'generated container name already exists')
		work = Path(tempfile.mkdtemp(prefix='intruvia-023-', dir=ROOT / 'target'))
		print(f'Private workspace: {work}; local image: {image}', flush=True)
		image_may_exist = True
		build_output = run([ROOT / 'scripts/build-deployment-image.sh', image, DIST])
		print('Build stdout SHA-256: ' + hashlib.sha256(build_output).hexdigest(), flush=True)
		image_data = json.loads(run(['docker', 'image', 'inspect', image], timeout=30))[0]
		image_id = image_data['Id']
		require(image_data['Config']['User'] == '10001:10001', 'image does not use expected unprivileged user')
		print(f'Built local image ID: {image_id}', flush=True)
		run(['docker', 'create', '--name', container, image], timeout=30)
		container_created = True
		copied = work / 'image-content'
		copied.mkdir()
		run(['docker', 'cp', f'{container}:/opt/intruvia/intruvia-app.jar', copied / 'intruvia-app.jar'], timeout=120)
		run(['docker', 'cp', f'{container}:/opt/intruvia/lib', copied / 'lib'], timeout=120)
		require(digest(applications[0]) == digest(copied / 'intruvia-app.jar'), 'image application differs from prebuilt JAR')
		require({p.name: digest(p) for p in libraries} == {p.name: digest(p) for p in (copied / 'lib').iterdir()},
				'image libraries differ from prebuilt JARs')
		print('PASS: image app and all library JAR hashes match the prebuilt distribution', flush=True)
		out = work / 'out'
		out.mkdir()
		extracted = work / 'extracted'
		extracted.mkdir()
		run([ROOT / 'scripts/build-deployment-tarball.sh', version, image, out])
		inspect_archive(out / f'intruvia-deployment-{version}.tar.gz', image, extracted)
		restored_dir = work / 'restored'
		shutil.copytree(extracted, restored_dir)
		db_image = f'intruvia-023-db-{identifier}:1.0'
		proxy_image = f'intruvia-023-caddy-{identifier}:1.0'
		upgrade_image = f'intruvia-023-upgrade-{identifier}:2.0'
		for tag, base in [(db_image, 'postgres:18-bookworm'), (proxy_image, 'caddy:2'), (upgrade_image, image)]:
			require(not run(['docker', 'image', 'ls', '--quiet', tag], timeout=30).strip(), 'generated image already exists')
			images.append(tag)
			derived_id = build_derived(tag, base, identifier)
			if tag == upgrade_image:
				require(derived_id != image_id, 'upgrade must have a genuinely new image ID')
		passwords = tuple(secrets.token_urlsafe(36) for _ in range(4))
		REDACT.extend(passwords)
		port = listener_port()
		primary = Rehearsal(extracted, f'intruvia023{identifier}', port, image, db_image, proxy_image, passwords)
		restored = Rehearsal(restored_dir, f'intruvia023restore{identifier}', port, upgrade_image, db_image, proxy_image, passwords)
		for rehearsal in (primary, restored):
			require(not run(['docker', 'ps', '-aq', '--filter', f'label=com.docker.compose.project={rehearsal.project}'], timeout=30).strip(),
					'generated Compose project already exists')
			require(not run(['docker', 'volume', 'ls', '-q', '--filter', f'label=com.docker.compose.project={rehearsal.project}'], timeout=30).strip(),
					'generated Compose volumes already exist')
			rehearsals.append(rehearsal)
		primary.prepare_secrets(image)
		primary.wait()
		primary.maintenance('--entrypoint', 'sh', 'app', '-c', 'test ! -e /run/secrets/viewer-password')
		for service in ('db', 'app'):
			cid = primary.compose('ps', '-q', service).decode().strip()
			ports = json.loads(run(['docker', 'inspect', '--format', '{{json .NetworkSettings.Ports}}', cid], timeout=30))
			require(not ports or all(not binding for binding in ports.values()), f'{service} has a host port')
		role = psql(primary, "SELECT rolsuper, rolcreatedb, rolcreaterole, rolcanlogin FROM pg_roles WHERE rolname=current_user;")
		require(role == 'f|f|f|t', 'application database role has unexpected privileges')
		primary.compose('exec', '-T', 'app', 'java', '-cp', '/opt/intruvia/intruvia-app.jar:/opt/intruvia/lib/*',
				'li.intruvia.app.ContainerHealthcheck')
		for _ in range(30):
			try:
				primary.trust_ca()
				break
			except (RuntimeError, ssl.SSLError):
				time.sleep(1)
		else:
			raise RuntimeError('Caddy local CA unavailable')
		primary.request('/health/', expected=404)
		primary.websocket(primary.login())
		print('PASS: password file removed, private readiness, non-superuser DB role, no app/DB ports, public health blocked', flush=True)
		primary.stop()
		old, new = provision(primary)
		primary.wait()
		event = dict(schemaVersion=1, eventId=str(uuid.uuid4()), occurredAt=datetime.now(timezone.utc).isoformat(),
				action='ban', ip='2001:db8::42', jail='compose-rehearsal')
		first = primary.ingest(event, old, 201)
		duplicate = primary.ingest(event, new, 200)
		require(duplicate['duplicate'] and first['id'] == duplicate['id'] and first['sequence'] == duplicate['sequence'],
				'event duplicate differs')
		primary.stop()
		primary.offline('revoke', 'token-admin', '/run/secrets/operator-password', old.split(':', 1)[0])
		primary.wait()
		primary.ingest(event, old, 401)
		require(primary.ingest(event, new, 200)['id'] == first['id'], 'event missing after restart')
		primary.websocket(primary.login())
		print('PASS: HTTPS authenticated ingestion, duplicate, offline revocation and restart persistence', flush=True)
		primary.stop()
		before_runtime = primary.runtime_archive()
		before_db = fingerprint(primary)
		before_privilege = archive_member_hash(before_runtime, 'runtime/config/PrivilegeConfig.xml')
		before_env = primary.env_file.read_text()
		primary.env_file.write_text(before_env.replace('INTRUVIA_IMAGE=' + image, 'INTRUVIA_IMAGE=' + upgrade_image))
		primary.wait()
		require(primary.ingest(event, new, 200)['id'] == first['id'], 'upgrade lost persisted event')
		primary.websocket(primary.login())
		cid = primary.compose('ps', '-q', 'app').decode().strip()
		running_image = run(['docker', 'inspect', '--format', '{{.Image}}', cid], timeout=30).decode().strip()
		require(running_image == derived_id, 'upgrade container image ID mismatch')
		primary.stop()
		require(archive_member_hash(primary.runtime_archive(), 'runtime/config/PrivilegeConfig.xml') == before_privilege,
				'upgrade changed runtime privilege secrets')
		require(all(line in primary.env_file.read_text().splitlines() for line in before_env.splitlines() if not line.startswith('INTRUVIA_IMAGE=')),
				'upgrade changed database credentials')
		print('PASS: new image ID upgrade retained runtime privilege secrets, database credentials and persisted event', flush=True)
		before_db = fingerprint(primary)
		dump_command = 'export PGPASSWORD="$INTRUVIA_DB_PASSWORD"; exec pg_dump -h 127.0.0.1 -U "$INTRUVIA_DB_USERNAME" -d "$POSTGRES_DB" --format=custom --no-owner --no-acl'
		dump = primary.compose('exec', '-T', 'db', 'sh', '-c', dump_command)
		runtime = primary.runtime_archive()
		for name, data in [('database.dump', dump), ('runtime.tar.gz', runtime)]:
			(work / name).write_bytes(data)
			print(f'Backup {name} sha256:{hashlib.sha256(data).hexdigest()}', flush=True)
		restored.chown_secrets(image, '10001:10001')
		restored.compose('up', '-d', '--wait', '--wait-timeout', '180', 'db', timeout=240)
		restore_command = 'export PGPASSWORD="$INTRUVIA_DB_PASSWORD"; exec pg_restore -h 127.0.0.1 -U "$INTRUVIA_DB_USERNAME" -d "$POSTGRES_DB" --exit-on-error --single-transaction --no-owner --no-acl'
		restored.compose('exec', '-T', 'db', 'sh', '-c', restore_command, input=dump)
		require(fingerprint(restored) == before_db, 'restored database table fingerprints differ')
		restored.maintenance('--entrypoint', 'sh', 'app', '-c', 'test -z "$(ls -A /var/lib/intruvia/runtime)"')
		restored.maintenance('--entrypoint', 'tar', 'app', '-C', '/var/lib/intruvia/runtime', '--strip-components=1',
				'--no-same-owner', '-xzf', '-', input=runtime)
		restored.wait()
		restored.trust_ca()
		restored.websocket(restored.login())
		retry = restored.ingest(event, new, 200)
		require(retry['duplicate'] and retry['id'] == first['id'] and retry['sequence'] == first['sequence'], 'restored event differs')
		restored.ingest(event, old, 401)
		print('PASS: coordinated full backup restored to fresh project/database/runtime; table fingerprints, login, WSS, event and revocation preserved', flush=True)
		print('Restore risk: later events and revocations can be lost; sequences can be reused and tokens resurrected.', flush=True)
		print('UNRUN: registry publication, public CA and release verification.', flush=True)
	finally:
		for rehearsal in reversed(rehearsals):
			try:
				rehearsal.compose('down', '--volumes', '--remove-orphans', timeout=240)
				rehearsal.chown_secrets(image, f'{os.getuid()}:{os.getgid()}')
			except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
				cleanup_errors.append(f'project {rehearsal.project}: {error}')
		for tag in reversed(images):
			try:
				if run(['docker', 'image', 'ls', '--quiet', tag], timeout=30).strip():
					run(['docker', 'image', 'rm', '--no-prune', tag], timeout=30)
			except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
				cleanup_errors.append(f'image {tag}: {error}')
		if container_created:
			try:
				run(['docker', 'container', 'rm', container], timeout=30)
			except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
				cleanup_errors.append(f'container {container}: {error}')
		if image_may_exist:
			try:
				ids = run(['docker', 'image', 'ls', '--no-trunc', '--quiet', image], timeout=30).decode().splitlines()
				if ids:
					image_info = json.loads(run(['docker', 'image', 'inspect', image], timeout=30))[0]
					tags = image_info.get('RepoTags') or []
					if image_info['Id'] in preexisting_ids and tags == [image]:
						cleanup_errors.append(f'{image}: removing the last tag could delete a pre-existing untagged image')
					else:
						run(['docker', 'image', 'rm', '--no-prune', image], timeout=30)
			except (RuntimeError, OSError, subprocess.TimeoutExpired, ValueError, KeyError) as error:
				cleanup_errors.append(f'image {image}: {error}')
		if work is not None:
			try:
				shutil.rmtree(work)
				print(f'Removed private workspace {work}', flush=True)
			except OSError as error:
				cleanup_errors.append(f'workspace {work}: {error}')
		if cleanup_errors:
			raise RuntimeError('cleanup failed: ' + '; '.join(cleanup_errors))
		print('Owned Docker resources cleaned up.', flush=True)
	return 0


if __name__ == '__main__':
	try:
		sys.exit(main())
	except (RuntimeError, OSError, subprocess.TimeoutExpired, tarfile.TarError, zipfile.BadZipFile, ValueError) as error:
		print(f'FAIL: {error}', file=sys.stderr, flush=True)
		sys.exit(1)