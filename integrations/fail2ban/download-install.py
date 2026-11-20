#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Self-contained, interactive bootstrap rendered by the Intruvia server."""
import base64
import binascii
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import tempfile
import types
from urllib.parse import urlsplit


INSTALLER_B64 = '__INTRUVIA_INSTALLER_B64__'
ACTION_B64 = '__INTRUVIA_ACTION_B64__'
HELPER_B64 = '__INTRUVIA_HELPER_B64__'
INGESTION_PATH = '/api/v1/fail2ban/events'


class BootstrapError(Exception):
	pass


def load_sources():
	try:
		return tuple(base64.b64decode(value, validate=True) for value in (INSTALLER_B64, ACTION_B64, HELPER_B64))
	except (ValueError, binascii.Error):
		raise BootstrapError('installer payload is missing or invalid') from None


def load_installer(source, source_dir):
	module = types.ModuleType('_intruvia_embedded_installer')
	module.__file__ = str(source_dir / 'install.py')
	sys.modules[module.__name__] = module
	try:
		exec(compile(source, module.__file__, 'exec'), module.__dict__)
	except (SyntaxError, ValueError, UnicodeError):
		raise BootstrapError('installer payload is missing or invalid') from None
	finally:
		sys.modules.pop(module.__name__, None)
	return module


def endpoint_for_origin(origin, installer):
	try:
		url = urlsplit(origin)
	except ValueError:
		raise BootstrapError('origin must be an HTTPS origin without a path, credentials, query, or fragment') from None
	if url.scheme != 'https' or origin != 'https://' + url.netloc:
		raise BootstrapError('origin must be an HTTPS origin without a path, credentials, query, or fragment')
	return installer.validate_endpoint(origin + INGESTION_PATH)


def read_token(stream):
	try:
		line = stream.readline(4098)
	except (OSError, UnicodeError):
		raise BootstrapError('unable to read the PAT from standard input') from None
	if (len(line.rstrip('\r\n')) + 1 > 4096 or
			not re.fullmatch(r'[A-Za-z0-9._-]+:[A-Za-z0-9._~+/=-]+(?:\r?\n)?', line, re.ASCII)):
		raise BootstrapError('invalid PAT on standard input')
	return line.rstrip('\r\n').encode('ascii') + b'\n'


def prompt(tty):
	tty.write('Existing enabled Fail2ban jail: ')
	tty.flush()
	jail = tty.readline().strip()
	tty.write('Does this jail use the default action with no custom action? Type yes to confirm: ')
	tty.flush()
	confirmed = tty.readline().strip().lower() == 'yes'
	if not confirmed:
		raise BootstrapError('default action was not confirmed; no files were changed')
	return jail


def check_ancestors(path, owner_uid):
	if not path.is_absolute() or '..' in path.parts:
		raise BootstrapError('installation paths must be absolute and protected')
	for directory in (path, *path.parents):
		info = directory.lstat()
		sticky_root = info.st_uid == 0 and info.st_mode & stat.S_ISVTX
		if (not stat.S_ISDIR(info.st_mode) or info.st_uid not in (0, owner_uid) or
				info.st_mode & 0o022 and not sticky_root):
			raise BootstrapError('installation paths must be absolute and protected')


def setup(origin, token, jail, sources, config_dir=Path('/etc/fail2ban'), lib_dir=Path('/usr/local/lib/intruvia'),
		client='fail2ban-client', runner=None, owner_uid=0):
	installer_source, action_source, helper_source = sources
	# Compile in memory; only the companion action/helper need files for the guarded installer.
	installer = load_installer(installer_source, config_dir)
	endpoint = endpoint_for_origin(origin, installer)
	installer.validate_jail(jail)
	check_ancestors(config_dir, owner_uid)
	check_ancestors(lib_dir.parent, owner_uid)
	installer._check_directory(config_dir, 'Fail2ban configuration directory', owner_uid)
	pat_file = config_dir / 'intruvia.pat'
	config = config_dir / 'jail.d' / ('intruvia-' + jail + '.local')
	config_preexisting = installer._lstat(config, 'jail configuration') is not None
	paths = installer.InstallPaths(config_dir=config_dir, action_dir=config_dir / 'action.d', lib_dir=lib_dir,
			pat_file=pat_file)
	commands = []
	if runner is None:
		runner = installer._run

	def tracked_runner(command):
		commands.append((command[1:], None))
		result = runner(command)
		commands[-1] = (command[1:], result.returncode)
		return result

	with tempfile.TemporaryDirectory(prefix='.intruvia-setup-', dir=config_dir) as temporary:
		source_dir = Path(temporary)
		installer._write_new(source_dir / 'intruvia.py', action_source, 0o600)
		installer._write_new(source_dir / 'intruvia_notify.py', helper_source, 0o600)
		paths = installer.InstallPaths(config_dir=paths.config_dir, action_dir=paths.action_dir, lib_dir=paths.lib_dir,
				pat_file=pat_file, source_dir=source_dir)
		installer._write_new(pat_file, token, 0o600)
		pat_identity = pat_file.lstat()
		try:
			return installer.install(jail, endpoint, paths=paths, confirm_default_action=True, client=client,
					runner=tracked_runner, owner_uid=owner_uid, service_uid=owner_uid)
		except BaseException:
			# Retain a possibly live credential when a reload or rollback cannot be confirmed.
			reloads = [status for command, status in commands if command == ['reload']]
			config_absent = installer._lstat(config, 'jail configuration') is None
			if (not reloads and (config_preexisting or config_absent) or
					len(reloads) >= 2 and reloads[-1] == 0 and config_absent):
				current = installer._lstat(pat_file, 'PAT file')
				if current is not None and (current.st_dev, current.st_ino) == (pat_identity.st_dev, pat_identity.st_ino):
					installer._remove_owned_files([(pat_file, token)])
			if installer._lstat(pat_file, 'PAT file') is not None:
				print('The PAT file was retained; inspect the installation and Fail2ban state before retrying.', file=sys.stderr)
			raise


def main(argv=None, stdin=None, tty=None):
	args = sys.argv[1:] if argv is None else argv
	stdin = sys.stdin if stdin is None else stdin
	if len(args) != 1:
		print('Usage: sudo python3 <downloaded-private-file> https://origin', file=sys.stderr)
		return 1
	if os.geteuid() != 0:
		print('Intruvia Fail2ban setup refused: run as root', file=sys.stderr)
		return 1
	try:
		sources = load_sources()
		token = read_token(stdin)
		client = shutil.which('fail2ban-client')
		if client is None:
			raise BootstrapError('fail2ban-client is not installed or not on PATH')
		if tty is None:
			with open('/dev/tty', 'r+', encoding='utf-8') as terminal:
				jail = prompt(terminal)
		else:
			jail = prompt(tty)
		config = setup(args[0], token, jail, sources, client=client)
	except BootstrapError as error:
		print('Intruvia Fail2ban setup refused: ' + str(error), file=sys.stderr)
		return 1
	except (OSError, ValueError, KeyboardInterrupt):
		print('Intruvia Fail2ban setup refused; check the origin, PAT, terminal, and installation prerequisites.', file=sys.stderr)
		return 1
	except Exception as error:
		# The embedded installer emits only fixed diagnostics, never the token or subprocess output.
		if error.__class__.__name__ == 'SetupError' and error.__class__.__module__ == '_intruvia_embedded_installer':
			print('Intruvia Fail2ban setup refused: ' + str(error), file=sys.stderr)
		else:
			print('Intruvia Fail2ban setup failed; inspect the installation before retrying.', file=sys.stderr)
		return 1
	print('Intruvia notifications enabled for jail %s; configuration: %s' % (jail, config))
	print('Existing default firewall action remains first. Verify with fail2ban-client status %s.' % jail)
	return 0


if __name__ == '__main__':
	sys.exit(main())