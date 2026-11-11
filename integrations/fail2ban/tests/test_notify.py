# SPDX-License-Identifier: AGPL-3.0-only
import contextlib
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('notify', ROOT / 'intruvia_notify.py')
notify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notify)


class Server(ThreadingHTTPServer):
	daemon_threads = True

	def handle_error(self, request, client_address):
		pass  # Expected disconnects in timeout/TLS fault tests.


class Handler(BaseHTTPRequestHandler):
	def log_message(self, *args):
		pass

	def do_POST(self):
		body = self.rfile.read(int(self.headers['Content-Length']))
		self.server.requests.append((self.headers, body, time.monotonic()))
		status, headers = self.server.steps.pop(0)
		if status == 'drop':
			self.connection.shutdown(socket.SHUT_RDWR)
			return
		if status == 'trickle':
			try:
				self.connection.sendall(b'HTTP/1.1 201 Created\r\nX-Slow: ')
				for tick in range(18):
					self.connection.sendall(b'x')
					time.sleep(1)
			except OSError:
				pass
			return
		self.send_response(status)
		for key, value in headers.items():
			self.send_header(key, value)
		self.send_header('Content-Length', '0')
		self.end_headers()


class NotifyTest(unittest.TestCase):
	@classmethod
	def setUpClass(cls):
		cls.directory = tempfile.TemporaryDirectory(prefix='intruvia-018-')
		cls.base = Path(cls.directory.name)
		cls.cert = cls.base / 'cert.pem'
		cls.key = cls.base / 'key.pem'
		subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
				'-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost',
				'-keyout', str(cls.key), '-out', str(cls.cert)], check=True, capture_output=True)
		cls.token = cls.base / 'pat'
		cls.token.write_text('test-id:synthetic_test_value\n')
		cls.token.chmod(0o600)

	@classmethod
	def tearDownClass(cls):
		cls.directory.cleanup()

	@contextlib.contextmanager
	def server(self, steps):
		server = Server(('127.0.0.1', 0), Handler)
		context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
		context.load_cert_chain(self.cert, self.key)
		server.socket = context.wrap_socket(server.socket, server_side=True)
		server.requests = []
		server.steps = list(steps)
		thread = threading.Thread(target=server.serve_forever, daemon=True)
		thread.start()
		server.endpoint = 'https://localhost:%d/api/v1/fail2ban/events' % server.server_port
		try:
			yield server
		finally:
			server.shutdown()
			server.server_close()
			thread.join()

	def send(self, server, jail='sshd'):
		payload = notify.make_payload('2001:db8::1', jail, 7)
		notify.send(server.endpoint, notify.load_token(self.token), payload, str(self.cert))
		return payload

	def test_retry_statuses_and_network_reuse_exact_payload(self):
		for failure in (429, 500, 503, 599, 'drop'):
			with self.subTest(failure=failure), self.server([(failure, {}), (failure, {}), (201, {})]) as server:
				payload = self.send(server)
				self.assertEqual(3, len(server.requests))
				self.assertEqual([payload] * 3, [item[1] for item in server.requests])
				for headers, body, when in server.requests:
					self.assertEqual('Bearer test-id:synthetic_test_value', headers['Authorization'])
					self.assertEqual('application/json', headers['Content-Type'])
				value = json.loads(payload)
				uuid.UUID(value['eventId'])
				self.assertIsNotNone(datetime.fromisoformat(value['occurredAt']).tzinfo)

	def test_no_retry_for_other_4xx_or_redirect(self):
		for status in (400, 401, 403, 404, 409, 413, 415, 422, 302):
			with self.subTest(status=status), self.server([(status, {'Location': 'https://example.invalid'})]) as server:
				with self.assertRaises(notify.NotificationError):
					self.send(server)
				self.assertEqual(1, len(server.requests))

	def test_three_attempt_bound(self):
		with self.server([(503, {})] * 4) as server:
			with self.assertRaises(notify.NotificationError):
				self.send(server)
			self.assertEqual(3, len(server.requests))

	def test_retry_after_and_deadline_refusal(self):
		with self.server([(429, {'Retry-After': '2'}), (200, {})]) as server:
			self.send(server)
			self.assertGreaterEqual(server.requests[1][2] - server.requests[0][2], 2)
		with self.server([(429, {'Retry-After': '86400'})]) as server:
			started = time.monotonic()
			with self.assertRaises(notify.NotificationError):
				self.send(server)
			self.assertLess(time.monotonic() - started, 1)
		future = format_datetime(datetime.now(timezone.utc) + timedelta(seconds=5), usegmt=True)
		self.assertTrue(3 < notify.retry_after(future) <= 5)
		self.assertEqual(0, notify.retry_after('invalid'))

	def command(self, server, jail='sshd'):
		return [sys.executable, str(ROOT / 'intruvia_notify.py'), '--endpoint=' + server.endpoint,
				'--token-file=' + str(self.token), '--ca-file=' + str(self.cert), '--ip=2001:db8::1', '--jail=' + jail]

	def test_cli_json_encoding_and_unique_invocations(self):
		jail = '-x; $(touch /tmp/intruvia-018-INJECTION) `id` " \\ ü <script>'
		with self.server([(201, {}), (200, {})]) as server:
			for attempt in range(2):
				result = subprocess.run(self.command(server, jail), capture_output=True, timeout=17)
				self.assertEqual(0, result.returncode, result.stderr)
			values = [json.loads(item[1]) for item in server.requests]
			self.assertEqual(jail, values[0]['jail'])
			self.assertNotEqual(values[0]['eventId'], values[1]['eventId'])
			self.assertFalse(Path('/tmp/intruvia-018-INJECTION').exists())

	def test_real_overall_deadline_with_trickled_headers(self):
		with self.server([('trickle', {})]) as server:
			started = time.monotonic()
			result = subprocess.run(self.command(server), capture_output=True, timeout=17)
			elapsed = time.monotonic() - started
			self.assertEqual(1, result.returncode)
			self.assertTrue(14.5 <= elapsed < 16, elapsed)
			self.assertEqual(b'intruvia: notification failed; event may be lost\n', result.stderr)
			self.assertEqual(1, len(server.requests))

	def test_verified_tls_and_endpoint_restrictions(self):
		with self.server([(201, {})]) as server:
			with self.assertRaises(notify.NotificationError):
				notify.send(server.endpoint, 'id:value', b'{}')
			with self.assertRaises(notify.NotificationError):
				notify.send(server.endpoint.replace('localhost', '127.0.0.1'), 'id:value', b'{}', str(self.cert))
			self.assertEqual([], server.requests)
		for endpoint in ('http://localhost/api/v1/fail2ban/events', 'https://user:pass@localhost/api/v1/fail2ban/events',
				'https://localhost/wrong', 'https://localhost/api/v1/fail2ban/events?x=1'):
			with self.assertRaises(notify.NotificationError):
				notify.send(endpoint, 'id:value', b'{}')

	def test_protected_token_file(self):
		self.assertEqual('test-id:synthetic_test_value', notify.load_token(self.token))
		for mode in (0o644, 0o640):
			self.token.chmod(mode)
			with self.assertRaises(notify.NotificationError):
				notify.load_token(self.token)
		self.token.chmod(0o600)
		link = self.base / 'link'
		link.symlink_to(self.token)
		with self.assertRaises(OSError):
			notify.load_token(link)
		with patch.object(notify.os, 'geteuid', return_value=os.geteuid() + 1):
			with self.assertRaises(notify.NotificationError):
				notify.load_token(self.token)
		bad = self.base / 'bad'
		for value in ('id:value\nInjected: header', 'no-colon', 'x' * 4097, 'id:ümlaut'):
			bad.write_text(value)
			bad.chmod(0o600)
			with self.assertRaises(notify.NotificationError):
				notify.load_token(bad)

	def test_outer_deadline_includes_setup_and_is_not_extended(self):
		started = time.monotonic()
		with patch.object(notify.ssl, 'create_default_context', side_effect=lambda **kwargs: time.sleep(1)):
			with self.assertRaises(notify.NotificationError):
				with notify.deadline(0.05):
					notify.send('https://localhost/api/v1/fail2ban/events', 'id:value', b'{}')
		self.assertLess(time.monotonic() - started, 0.5)

	def test_validation(self):
		for ip, jail, failures in [('localhost', 'sshd', None), ('::1%lo', 'sshd', None),
				('::1', 'x\n', None), ('::1', 'a' * 129, None), ('::1', 'sshd', -1)]:
			with self.assertRaises(notify.NotificationError):
				notify.make_payload(ip, jail, failures)


if __name__ == '__main__':
	unittest.main(verbosity=2)
