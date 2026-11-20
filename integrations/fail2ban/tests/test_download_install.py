# SPDX-License-Identifier: AGPL-3.0-only
import base64
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
TOKEN = 'synthetic-id:synthetic-secret-value'
SOURCES = tuple((ROOT / name).read_bytes() for name in ('install.py', 'intruvia.py', 'intruvia_notify.py'))


class Terminal:
	def __init__(self, text):
		self.input = io.StringIO(text)
		self.output = io.StringIO()

	def readline(self):
		return self.input.readline()

	def write(self, text):
		self.output.write(text)

	def flush(self):
		pass


class DownloadInstallTest(unittest.TestCase):
	def setUp(self):
		self.temporary = tempfile.TemporaryDirectory(prefix='intruvia-download-test-', dir=ROOT)
		self.root = Path(self.temporary.name)
		self.config_dir = self.root / 'etc' / 'fail2ban'
		self.action_dir = self.config_dir / 'action.d'
		self.jail_dir = self.config_dir / 'jail.d'
		self.lib_dir = self.root / 'usr' / 'local' / 'lib' / 'intruvia'
		for directory in (self.action_dir, self.jail_dir, self.lib_dir.parent):
			directory.mkdir(parents=True, exist_ok=True)
		(self.config_dir / 'jail.conf').write_text('[DEFAULT]\naction = %(action_)s\n\n[sshd]\nenabled = true\n')
		self.pat = self.config_dir / 'intruvia.pat'
		self.config = self.jail_dir / 'intruvia-sshd.local'
		self.commands = []
		template = (ROOT / 'download-install.py').read_text()
		for placeholder, source in zip(('__INTRUVIA_INSTALLER_B64__', '__INTRUVIA_ACTION_B64__',
				'__INTRUVIA_HELPER_B64__'), SOURCES):
			self.assertEqual(1, template.count(placeholder))
			template = template.replace(placeholder, base64.b64encode(source).decode('ascii'))
		self.rendered = self.root / 'downloaded.py'
		self.rendered.write_text(template)
		spec = importlib.util.spec_from_file_location('download_install_test', self.rendered)
		self.bootstrap = importlib.util.module_from_spec(spec)
		spec.loader.exec_module(self.bootstrap)
		real_check = self.bootstrap.check_ancestors
		real_lstat = Path.lstat
		outside = set(self.root.parents)

		def protected_fixture_ancestors(path, *args, **kwargs):
			info = real_lstat(path, *args, **kwargs)
			if path in outside:
				values = list(info)
				values[0] &= ~0o022
				return os.stat_result(values)
			return info

		def check_sandbox(path, owner_uid):
			with mock.patch.object(Path, 'lstat', protected_fixture_ancestors):
				return real_check(path, owner_uid)

		patcher = mock.patch.object(self.bootstrap, 'check_ancestors', side_effect=check_sandbox)
		patcher.start()
		self.addCleanup(patcher.stop)

	def tearDown(self):
		self.temporary.cleanup()

	def runner(self, command):
		self.commands.append(command)
		output = 'iptables-multiport\nintruvia' if command[1:] == ['get', 'sshd', 'actions'] else ''
		return subprocess.CompletedProcess(command, 0, stdout=output, stderr='')

	def setup_install(self, origin='https://intruvia.example.org:8443', jail='sshd', runner=None):
		return self.bootstrap.setup(origin, (TOKEN + '\n').encode(), jail, self.bootstrap.load_sources(),
				config_dir=self.config_dir, lib_dir=self.lib_dir, client='fail2ban-client',
				runner=runner or self.runner, owner_uid=os.geteuid())

	def test_rendered_main_success_prompts_separately_and_installs_real_sources(self):
		terminal = Terminal('sshd\nyes\n')
		output, errors = io.StringIO(), io.StringIO()
		real_setup = self.bootstrap.setup
		owner_uid = os.geteuid()

		def setup(origin, token, jail, sources, client):
			return real_setup(origin, token, jail, sources, config_dir=self.config_dir, lib_dir=self.lib_dir,
					client=client, runner=self.runner, owner_uid=owner_uid)

		with mock.patch.object(self.bootstrap.os, 'geteuid', return_value=0), \
				mock.patch.object(self.bootstrap.shutil, 'which', return_value='/usr/bin/fail2ban-client'), \
				mock.patch.object(self.bootstrap, 'setup', side_effect=setup), \
				contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
			result = self.bootstrap.main(['https://intruvia.example.org:8443'], io.StringIO(TOKEN + '\n'), terminal)

		self.assertEqual(0, result, errors.getvalue())
		self.assertEqual(TOKEN + '\n', self.pat.read_text())
		self.assertEqual(0o600, self.pat.stat().st_mode & 0o777)
		self.assertEqual(os.geteuid(), self.pat.stat().st_uid)
		self.assertEqual(SOURCES[1], (self.action_dir / 'intruvia.py').read_bytes())
		self.assertEqual(SOURCES[2], (self.lib_dir / 'intruvia_notify.py').read_bytes())
		self.assertIn('action = %(action_)s\n         intruvia.py[endpoint="https://intruvia.example.org:8443/api/v1/fail2ban/events"]',
				self.config.read_text())
		self.assertEqual([['-t'], ['status', 'sshd'], ['-t'], ['reload'], ['get', 'sshd', 'actions']],
				[command[1:] for command in self.commands])
		self.assertNotIn(TOKEN, output.getvalue() + errors.getvalue() + terminal.output.getvalue() + str(self.commands))
		self.assertEqual([], list(self.config_dir.glob('.intruvia-setup-*')))

	def test_confirmation_refusal_creates_nothing(self):
		terminal = Terminal('sshd\nno\n')
		with mock.patch.object(self.bootstrap.os, 'geteuid', return_value=0), \
				mock.patch.object(self.bootstrap.shutil, 'which', return_value='fail2ban-client'), \
				contextlib.redirect_stderr(io.StringIO()):
			self.assertEqual(1, self.bootstrap.main(['https://example.org'], io.StringIO(TOKEN + '\n'), terminal))
		self.assertFalse(self.pat.exists())
		self.assertFalse(self.config.exists())

	def test_invalid_origin_and_jail_refused_without_writes(self):
		for origin in ('http://example.org', 'https://example.org/', 'https://user@example.org',
				'https://example.org?x=1', 'https://example.org#fragment', 'https://example.org:99999',
				'https://example.org\n'):
			with self.subTest(origin=origin), self.assertRaisesRegex(Exception, '(HTTPS|endpoint|port)'):
				self.setup_install(origin=origin)
		with self.assertRaisesRegex(Exception, 'invalid jail name'):
			self.setup_install(jail='../sshd')
		self.assertFalse(self.pat.exists())
		self.assertEqual([], self.commands)

	def test_token_validation_and_unrendered_template(self):
		self.assertEqual((TOKEN + '\n').encode(), self.bootstrap.read_token(io.StringIO(TOKEN + '\n')))
		self.assertEqual(4096, len(self.bootstrap.read_token(io.StringIO('id:' + 'x' * 4092))))
		for token in ('', 'bad', 'id:hello world\n', 'id:secret\r', 'id:' + 'x' * 4093):
			with self.subTest(token=token[:20]), self.assertRaises(self.bootstrap.BootstrapError):
				self.bootstrap.read_token(io.StringIO(token))
		with mock.patch.object(self.bootstrap, 'INSTALLER_B64', '__INTRUVIA_INSTALLER_B64__'):
			with self.assertRaises(self.bootstrap.BootstrapError):
				self.bootstrap.load_sources()

	def test_existing_pat_or_configuration_never_overwritten(self):
		self.pat.write_text('existing-secret\n')
		self.pat.chmod(0o600)
		with self.assertRaisesRegex(Exception, 'refusing to overwrite'):
			self.setup_install()
		self.assertEqual('existing-secret\n', self.pat.read_text())
		self.pat.unlink()
		self.config.write_text('[other]\nenabled = true\n')
		with self.assertRaisesRegex(Exception, 'refusing to overwrite an existing Intruvia installation file'):
			self.setup_install()
		self.assertEqual('[other]\nenabled = true\n', self.config.read_text())
		self.assertFalse(self.pat.exists())

	def test_symlink_pat_and_unsafe_directories_are_refused(self):
		victim = self.root / 'victim'
		victim.write_text('do not touch')
		self.pat.symlink_to(victim)
		with self.assertRaisesRegex(Exception, 'refusing to overwrite'):
			self.setup_install()
		self.assertEqual('do not touch', victim.read_text())
		self.pat.unlink()
		self.action_dir.chmod(0o777)
		with self.assertRaisesRegex(Exception, 'protected directory'):
			self.setup_install()
		self.assertFalse(self.pat.exists())
		self.action_dir.chmod(0o755)
		self.config_dir.parent.chmod(0o777)
		with self.assertRaisesRegex(self.bootstrap.BootstrapError, 'installation paths'):
			self.setup_install()
		self.assertFalse(self.pat.exists())

	def test_custom_action_and_failed_validation_remove_new_pat(self):
		(self.config_dir / 'jail.local').write_text('[sshd]\naction = custom\n')
		with self.assertRaisesRegex(Exception, 'action is already configured'):
			self.setup_install()
		self.assertFalse(self.pat.exists())
		(self.config_dir / 'jail.local').unlink()

		def fail_validation(command):
			self.commands.append(command)
			return subprocess.CompletedProcess(command, 1, stdout='', stderr='')

		with self.assertRaisesRegex(Exception, 'current Fail2ban configuration validation failed'):
			self.setup_install(runner=fail_validation)
		self.assertFalse(self.pat.exists())
		self.assertFalse(self.config.exists())

	def test_failed_reload_and_successful_rollback_remove_new_pat(self):
		def fail_reload(command):
			self.commands.append(command)
			failed = command[1:] == ['reload'] and sum(c[1:] == ['reload'] for c in self.commands) == 1
			return subprocess.CompletedProcess(command, int(failed), stdout='', stderr='')

		with self.assertRaisesRegex(Exception, 'Fail2ban could not reload'):
			self.setup_install(runner=fail_reload)
		self.assertFalse(self.pat.exists())
		self.assertFalse(self.config.exists())
		self.assertFalse((self.action_dir / 'intruvia.py').exists())
		self.assertFalse(self.lib_dir.exists())
		self.assertEqual(2, sum(c[1:] == ['reload'] for c in self.commands))

	def test_failed_rollback_retains_pat_and_reports_no_secret(self):
		def fail_reload(command):
			self.commands.append(command)
			return subprocess.CompletedProcess(command, int(command[1:] == ['reload']), stdout='', stderr='')

		errors = io.StringIO()
		with contextlib.redirect_stderr(errors), self.assertRaisesRegex(Exception, 'Fail2ban could not reload'):
			self.setup_install(runner=fail_reload)
		self.assertEqual(TOKEN + '\n', self.pat.read_text())
		self.assertEqual(0o600, self.pat.stat().st_mode & 0o777)
		self.assertIn('PAT file was retained', errors.getvalue())
		self.assertNotIn(TOKEN, errors.getvalue())

	def test_interrupted_reload_retains_pat_and_new_configuration(self):
		def interrupt_reload(command):
			self.commands.append(command)
			if command[1:] == ['reload']:
				raise KeyboardInterrupt()
			return subprocess.CompletedProcess(command, 0, stdout='', stderr='')

		errors = io.StringIO()
		with contextlib.redirect_stderr(errors), self.assertRaises(KeyboardInterrupt):
			self.setup_install(runner=interrupt_reload)
		self.assertEqual(TOKEN + '\n', self.pat.read_text())
		self.assertTrue(self.config.exists())
		self.assertIn('PAT file was retained', errors.getvalue())
		self.assertNotIn(TOKEN, errors.getvalue())

	def test_modified_new_pat_is_preserved_when_configuration_is_rolled_back(self):
		def change_pat_then_fail(command):
			self.commands.append(command)
			if command[1:] == ['-t'] and sum(c[1:] == ['-t'] for c in self.commands) == 2:
				self.pat.write_text('replacement-secret\n')
				return subprocess.CompletedProcess(command, 1, stdout='', stderr='')
			return subprocess.CompletedProcess(command, 0, stdout='', stderr='')

		errors = io.StringIO()
		with contextlib.redirect_stderr(errors), self.assertRaisesRegex(Exception, 'Fail2ban rejected the new configuration'):
			self.setup_install(runner=change_pat_then_fail)
		self.assertEqual('replacement-secret\n', self.pat.read_text())
		self.assertFalse(self.config.exists())
		self.assertIn('PAT file was retained', errors.getvalue())
		self.assertNotIn('secret', errors.getvalue())

	def test_replaced_pat_with_same_content_is_not_removed(self):
		def replace_pat_then_fail(command):
			self.commands.append(command)
			if command[1:] == ['-t'] and sum(c[1:] == ['-t'] for c in self.commands) == 2:
				replacement = self.config_dir / 'replacement.pat'
				replacement.write_bytes((TOKEN + '\n').encode())
				replacement.chmod(0o600)
				replacement.replace(self.pat)
				return subprocess.CompletedProcess(command, 1, stdout='', stderr='')
			return subprocess.CompletedProcess(command, 0, stdout='', stderr='')

		with contextlib.redirect_stderr(io.StringIO()), self.assertRaisesRegex(Exception, 'Fail2ban rejected the new configuration'):
			self.setup_install(runner=replace_pat_then_fail)
		self.assertEqual(TOKEN + '\n', self.pat.read_text())
		self.assertFalse(self.config.exists())

	def test_rendered_script_executes_without_companions(self):
		result = subprocess.run([sys.executable, str(self.rendered)], input='', capture_output=True, text=True, check=False)
		self.assertEqual(1, result.returncode)
		self.assertIn('Usage:', result.stderr)
		self.assertNotIn(TOKEN, result.stderr)


if __name__ == '__main__':
	unittest.main(verbosity=2)