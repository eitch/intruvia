#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Install the additive Intruvia action for one existing default-action jail."""
import argparse
import configparser
from dataclasses import dataclass
import ipaddress
import os
from pathlib import Path
import pwd
import re
import shutil
import stat
import subprocess
import sys
from urllib.parse import urlsplit


@dataclass(frozen=True)
class InstallPaths:
	config_dir: Path = Path('/etc/fail2ban')
	action_dir: Path = Path('/etc/fail2ban/action.d')
	lib_dir: Path = Path('/usr/local/lib/intruvia')
	pat_file: Path = Path('/etc/fail2ban/intruvia.pat')
	source_dir: Path = Path(__file__).resolve().parent


class SetupError(Exception):
	pass


def validate_jail(jail):
	if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', jail):
		raise SetupError('invalid jail name')


def validate_endpoint(endpoint):
	try:
		url = urlsplit(endpoint)
		port = url.port
	except ValueError:
		raise SetupError('invalid HTTPS endpoint') from None
	if (url.scheme != 'https' or not url.hostname or url.username is not None or url.password is not None or
			url.path != '/api/v1/fail2ban/events' or url.query or url.fragment or
			not re.fullmatch(r'(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+\])(?::[0-9]{1,5})?', url.netloc)):
		raise SetupError('endpoint must be HTTPS with the exact ingestion path and no credentials, query, or fragment')
	if port is not None and not 1 <= port <= 65535:
		raise SetupError('invalid HTTPS endpoint port')
	host = url.hostname
	if ':' in host:
		try:
			ipaddress.IPv6Address(host)
		except ValueError:
			raise SetupError('invalid HTTPS endpoint host') from None
	else:
		labels = host.rstrip('.').split('.')
		if (len(host) > 253 or not all(1 <= len(label) <= 63 and
				re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?', label) for label in labels)):
			raise SetupError('invalid HTTPS endpoint host')
	return endpoint


def _lstat(path, description):
	try:
		return path.lstat()
	except FileNotFoundError:
		return None
	except OSError:
		raise SetupError('unable to inspect ' + description) from None


def _check_directory(path, description, owner_uid):
	info = _lstat(path, description)
	if info is None or not stat.S_ISDIR(info.st_mode) or info.st_uid != owner_uid or info.st_mode & 0o022:
		raise SetupError(description + ' must be a protected directory owned by the installer user')


def _check_pat(path, owner_uid):
	info = _lstat(path, 'PAT file')
	if (info is None or not stat.S_ISREG(info.st_mode) or info.st_uid != owner_uid or
			info.st_mode & 0o077 or not info.st_mode & 0o400):
		raise SetupError('PAT file must exist as a private regular file owned by the Fail2ban service user')


def _configuration_files(config_dir):
	files = [config_dir / 'jail.conf', config_dir / 'jail.local']
	jail_dir = config_dir / 'jail.d'
	if jail_dir.exists():
		files.extend(sorted(jail_dir.glob('*.conf')))
		files.extend(sorted(jail_dir.glob('*.local')))
	return [path for path in files if _lstat(path, 'Fail2ban configuration') is not None]


def _validate_configuration(config_dir, jail, owner_uid):
	for path in _configuration_files(config_dir):
		info = _lstat(path, 'Fail2ban configuration')
		if not stat.S_ISREG(info.st_mode) or info.st_uid != owner_uid or info.st_mode & 0o022:
			raise SetupError('Fail2ban configuration must be a protected regular file owned by the installer user')
		parser = configparser.ConfigParser(interpolation=None, strict=False)
		try:
			with path.open(encoding='utf-8') as stream:
				parser.read_file(stream)
		except (OSError, UnicodeError, configparser.Error):
			raise SetupError('unable to safely inspect Fail2ban configuration') from None
		default_action = parser.defaults().get('action')
		if default_action is not None and default_action.strip() != '%(action_)s':
			raise SetupError('custom default actions require the manual integration procedure')
		section = parser._sections.get(jail, {})
		if 'action' in section:
			raise SetupError('an action is already configured for this jail; use the manual integration procedure')


def _run(command):
	try:
		return subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
				stderr=subprocess.DEVNULL, text=True, timeout=30, check=False)
	except (OSError, subprocess.SubprocessError):
		return subprocess.CompletedProcess(command, 1, stdout='', stderr='')


def _write_new(path, contents, mode):
	flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0)
	try:
		fd = os.open(path, flags, mode)
	except FileExistsError:
		raise SetupError('refusing to overwrite an existing Intruvia file: ' + str(path)) from None
	except OSError:
		raise SetupError('unable to create an Intruvia file') from None
	try:
		os.fchmod(fd, mode)
		with os.fdopen(fd, 'wb') as stream:
			stream.write(contents)
			stream.flush()
			os.fsync(stream.fileno())
	except OSError:
		try:
			os.close(fd)
		except OSError:
			pass
		try:
			path.unlink()
		except OSError:
			pass
		raise SetupError('unable to write an Intruvia file') from None


def _remove_owned_files(created):
	for path, contents in reversed(created):
		try:
			if path.is_file() and not path.is_symlink() and path.read_bytes() == contents:
				path.unlink()
		except OSError:
			pass


def install(jail, endpoint, paths=InstallPaths(), confirm_default_action=False, client='fail2ban-client',
		runner=_run, owner_uid=None, service_uid=None):
	validate_jail(jail)
	validate_endpoint(endpoint)
	if not confirm_default_action:
		raise SetupError('confirm that the jail currently uses the default action before installing')
	if owner_uid is None:
		owner_uid = os.geteuid()
	if service_uid is None:
		service_uid = owner_uid

	jail_dir = paths.config_dir / 'jail.d'
	_check_directory(paths.config_dir, 'Fail2ban configuration directory', owner_uid)
	_check_directory(paths.action_dir, 'Fail2ban action directory', owner_uid)
	_check_directory(jail_dir, 'Fail2ban jail.d directory', owner_uid)
	_check_directory(paths.lib_dir.parent, 'Intruvia library parent', owner_uid)
	if _lstat(paths.lib_dir, 'Intruvia library directory') is not None:
		_check_directory(paths.lib_dir, 'Intruvia library directory', owner_uid)
	_check_pat(paths.pat_file, service_uid)
	_validate_configuration(paths.config_dir, jail, owner_uid)

	action_config = jail_dir / ('intruvia-' + jail + '.local')
	helper_target = paths.lib_dir / 'intruvia_notify.py'
	action_target = paths.action_dir / 'intruvia.py'
	for target in (action_config, helper_target, action_target):
		if _lstat(target, 'Intruvia installation target') is not None:
			raise SetupError('refusing to overwrite an existing Intruvia installation file')

	for source in (paths.source_dir / 'intruvia_notify.py', paths.source_dir / 'intruvia.py'):
		info = _lstat(source, 'Intruvia source file')
		if info is None or not stat.S_ISREG(info.st_mode):
			raise SetupError('Intruvia action/helper source is missing or unsafe')
	try:
		helper_contents = (paths.source_dir / 'intruvia_notify.py').read_bytes()
		action_contents = (paths.source_dir / 'intruvia.py').read_bytes()
	except OSError:
		raise SetupError('unable to read the Intruvia action/helper source') from None
	config_contents = ('# Managed by Intruvia; the existing default firewall action remains first.\n'
			'[' + jail + ']\n'
			'action = %(action_)s\n'
			'         intruvia.py[endpoint="' + endpoint + '"]\n').encode('utf-8')

	if runner([client, '-t']).returncode != 0:
		raise SetupError('current Fail2ban configuration validation failed; no files were changed')
	if runner([client, 'status', jail]).returncode != 0:
		raise SetupError('the requested Fail2ban jail is not active; no files were changed')

	created = []
	created_directories = []
	reload_attempted = False
	try:
		if _lstat(paths.lib_dir, 'Intruvia library directory') is None:
			try:
				paths.lib_dir.mkdir(mode=0o755)
				os.chmod(paths.lib_dir, 0o755)
			except OSError:
				raise SetupError('unable to create the protected Intruvia library directory') from None
			created_directories.append(paths.lib_dir)
		for target, contents, mode in ((helper_target, helper_contents, 0o644),
				(action_target, action_contents, 0o644), (action_config, config_contents, 0o644)):
			_write_new(target, contents, mode)
			created.append((target, contents))
		if runner([client, '-t']).returncode != 0:
			raise SetupError('Fail2ban rejected the new configuration')
		reload_attempted = True
		if runner([client, 'reload']).returncode != 0:
			raise SetupError('Fail2ban could not reload; the new files were removed')
		result = runner([client, 'get', jail, 'actions'])
		if result.returncode != 0 or 'intruvia' not in (result.stdout or '').lower():
			raise SetupError('Fail2ban did not report the Intruvia action; the new files were removed')
	except SetupError:
		_remove_owned_files(created)
		if reload_attempted:
			runner([client, 'reload'])
		for directory in reversed(created_directories):
			try:
				directory.rmdir()
			except OSError:
				pass
		raise
	return action_config


def main():
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument('--jail', required=True, help='existing enabled Fail2ban jail name')
	parser.add_argument('--endpoint', required=True, help='HTTPS Intruvia ingestion endpoint')
	parser.add_argument('--confirm-default-action', action='store_true',
			help='confirm the jail currently uses %%(action_)s and no custom action')
	parser.add_argument('--service-user', default='root',
			help='Fail2ban service account that owns the PAT file (default: root)')
	args = parser.parse_args()
	if os.geteuid() != 0:
		parser.error('run this installer as root (for example with sudo)')
	client = shutil.which('fail2ban-client')
	if client is None:
		parser.error('fail2ban-client is not installed or not on PATH')
	try:
		try:
			service_uid = pwd.getpwnam(args.service_user).pw_uid
		except KeyError:
			raise SetupError('the Fail2ban service account does not exist') from None
		config_path = install(args.jail, args.endpoint, confirm_default_action=args.confirm_default_action,
				client=client, owner_uid=0, service_uid=service_uid)
	except SetupError as error:
		print('Intruvia Fail2ban setup refused: ' + str(error), file=sys.stderr)
		return 1
	print('Intruvia notifications enabled for jail %s; configuration: %s' % (args.jail, config_path))
	print('Existing default firewall action remains first. Verify with fail2ban-client status %s.' % args.jail)
	return 0


if __name__ == '__main__':
	sys.exit(main())
