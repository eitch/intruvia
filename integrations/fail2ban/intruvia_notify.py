#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Bounded, best-effort Fail2ban notification; Linux/Unix Python 3 standard library."""
import argparse
import contextlib
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import http.client
import ipaddress
import json
import os
import random
import re
import signal
import ssl
import stat
import sys
import time
from urllib.parse import urlsplit
import uuid


class NotificationError(Exception):
	"""Safe local diagnostic, containing neither credentials nor event data."""


@contextlib.contextmanager
def deadline(seconds):
	# Nested send() calls retain the invocation's earlier deadline.
	if signal.getitimer(signal.ITIMER_REAL)[0] > 0:
		yield
		return
	# SIGALRM bounds DNS, TLS and trickled HTTP headers as well as retry sleeps.
	def expire(signum, frame):
		raise NotificationError('notification deadline exceeded')
	previous = signal.signal(signal.SIGALRM, expire)
	signal.setitimer(signal.ITIMER_REAL, seconds)
	try:
		yield
	finally:
		signal.setitimer(signal.ITIMER_REAL, 0)
		signal.signal(signal.SIGALRM, previous)


def load_token(path):
	flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
	with os.fdopen(os.open(path, flags), 'rb') as stream:
		info = os.fstat(stream.fileno())
		if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
			raise NotificationError('PAT file must be a private regular file owned by the service user')
		value = stream.read(4097)
	if len(value) > 4096:
		raise NotificationError('invalid PAT file')
	try:
		token = value.decode('ascii').rstrip('\r\n')
	except UnicodeDecodeError:
		raise NotificationError('invalid PAT file') from None
	if not re.fullmatch(r'[A-Za-z0-9._-]+:[A-Za-z0-9._~+/=-]+', token):
		raise NotificationError('invalid PAT file')
	return token


def make_payload(ip, jail, failures=None):
	if '%' in ip or '/' in ip:
		raise NotificationError('invalid IP literal')
	try:
		ip = str(ipaddress.ip_address(ip))
	except ValueError:
		raise NotificationError('invalid IP literal') from None
	if not jail or len(jail) > 128 or any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in jail):
		raise NotificationError('invalid jail name')
	payload = dict(schemaVersion=1, eventId=str(uuid.uuid4()),
			occurredAt=datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z'), action='ban', ip=ip, jail=jail)
	if failures is not None:
		if not 0 <= failures <= 2147483647:
			raise NotificationError('invalid failure count')
		payload['failures'] = failures
	return json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode('utf-8')


def retry_after(value):
	if not value:
		return 0
	try:
		if value.isascii() and value.isdigit():
			return float(value)
		date = parsedate_to_datetime(value)
		return max(0, date.timestamp() - time.time())
	except (ValueError, TypeError, OverflowError):
		return 0


def send(endpoint, token, payload, ca_file=None):
	try:
		url = urlsplit(endpoint)
		port = url.port
	except ValueError:
		raise NotificationError('invalid HTTPS endpoint') from None
	if (url.scheme != 'https' or not url.hostname or url.username is not None or url.password is not None or
			url.query or url.fragment or url.path != '/api/v1/fail2ban/events'):
		raise NotificationError('endpoint must be HTTPS with the ingestion path and no credentials/query/fragment')
	end = time.monotonic() + 15
	with deadline(15):
		context = ssl.create_default_context(cafile=ca_file)
		for attempt in range(3):
			remaining = end - time.monotonic()
			if remaining <= 0:
				raise NotificationError('notification deadline exceeded')
			connection = http.client.HTTPSConnection(url.hostname, port, timeout=min(4, remaining), context=context)
			wait = 0
			try:
				connection.request('POST', url.path, body=payload,
						headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
				response = connection.getresponse()
				status = response.status
				if status in (200, 201):
					return
				if status != 429 and not 500 <= status <= 599:
					raise NotificationError('notification rejected (HTTP %d)' % status)
				wait = retry_after(response.getheader('Retry-After'))
			except ssl.SSLCertVerificationError:
				raise NotificationError('TLS certificate verification failed') from None
			except (OSError, http.client.HTTPException):
				pass  # Retry only transport failures; never expose exception text.
			finally:
				connection.close()
			if attempt == 2:
				break
			wait = max(wait, random.uniform(0.25, 0.75) * (2 ** attempt))
			if wait >= end - time.monotonic():
				raise NotificationError('retry delay exceeds notification deadline')
			time.sleep(wait)
	raise NotificationError('notification failed after three attempts')


def main():
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument('--endpoint', required=True)
	parser.add_argument('--token-file', required=True)
	parser.add_argument('--ca-file')
	parser.add_argument('--ip', required=True)
	parser.add_argument('--jail', required=True)
	parser.add_argument('--failures', type=int)
	args = parser.parse_args()
	try:
		with deadline(15):
			token = load_token(args.token_file)
			payload = make_payload(args.ip, args.jail, args.failures)
			send(args.endpoint, token, payload, args.ca_file)
	except (NotificationError, OSError, ValueError):
		# Fixed text prevents secrets, URLs, IPs and remote response bodies entering logs.
		sys.stderr.write('intruvia: notification failed; event may be lost\n')
		return 1
	return 0


if __name__ == '__main__':
	sys.exit(main())
