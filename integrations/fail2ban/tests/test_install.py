# SPDX-License-Identifier: AGPL-3.0-only
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('install_fail2ban', ROOT / 'install.py')
installer = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = installer
spec.loader.exec_module(installer)


class InstallTest(unittest.TestCase):
	def setUp(self):
		self.temporary = tempfile.TemporaryDirectory(prefix='intruvia-fail2ban-install-')
		self.root = Path(self.temporary.name)
		self.config_dir = self.root / 'etc' / 'fail2ban'
		self.action_dir = self.config_dir / 'action.d'
		self.jail_dir = self.config_dir / 'jail.d'
		self.lib_parent = self.root / 'usr' / 'local' / 'lib'
		self.lib_dir = self.lib_parent / 'intruvia'
		self.source_dir = self.root / 'source'
		for directory in (self.config_dir, self.action_dir, self.jail_dir, self.lib_parent, self.source_dir):
			directory.mkdir(parents=True, exist_ok=True)
		self.config = self.config_dir / 'jail.conf'
		self.config.write_text('[DEFAULT]\naction = %(action_)s\n\n[sshd]\nenabled = true\n')
		self.config.chmod(0o644)
		self.pat_file = self.config_dir / 'intruvia.pat'
		self.pat_file.write_text('opaque-id:synthetic-secret-value\n')
		self.pat_file.chmod(0o600)
		for name in ('intruvia.py', 'intruvia_notify.py'):
			(self.source_dir / name).write_text('trusted ' + name + '\n')
		self.paths = installer.InstallPaths(config_dir=self.config_dir, action_dir=self.action_dir,
				lib_dir=self.lib_dir, pat_file=self.pat_file, source_dir=self.source_dir)
		self.commands = []

	def tearDown(self):
		self.temporary.cleanup()

	def runner(self, command):
		self.commands.append(command[1:])
		stdout = 'iptables-multiport\nintruvia' if command[1:] == ['get', 'sshd', 'actions'] else ''
		return subprocess.CompletedProcess(command, 0, stdout=stdout, stderr='')

	def install(self, runner=None):
		return installer.install('sshd', 'https://intruvia.example.org/api/v1/fail2ban/events',
				paths=self.paths, confirm_default_action=True, client='fail2ban-client',
				runner=runner or self.runner, owner_uid=os.geteuid(), service_uid=os.geteuid())

	def test_cli_help_formats_default_action(self):
		result = subprocess.run([sys.executable, str(ROOT / 'install.py'), '--help'],
				capture_output=True, text=True, check=False)

		self.assertEqual(0, result.returncode, result.stderr)
		self.assertIn('%(action_)s', result.stdout)

	def test_success_adds_notification_after_default_and_preserves_existing_files(self):
		original_config = self.config.read_bytes()
		custom_jail = self.jail_dir / 'custom.conf'
		custom_jail.write_text('[custom]\naction = my-firewall\n')
		custom_jail.chmod(0o644)
		original_custom = custom_jail.read_bytes()

		created_config = self.install()

		self.assertEqual(original_config, self.config.read_bytes())
		self.assertEqual(original_custom, custom_jail.read_bytes())
		self.assertEqual('[sshd]\naction = %(action_)s\n         intruvia.py[endpoint="https://intruvia.example.org/api/v1/fail2ban/events"]\n',
				created_config.read_text().split('\n', 1)[1])
		self.assertEqual(b'trusted intruvia.py\n', (self.action_dir / 'intruvia.py').read_bytes())
		self.assertEqual(b'trusted intruvia_notify.py\n', (self.lib_dir / 'intruvia_notify.py').read_bytes())
		self.assertEqual(0o644, (created_config.stat().st_mode & 0o777))
		self.assertEqual([['-t'], ['status', 'sshd'], ['-t'], ['reload'], ['get', 'sshd', 'actions']], self.commands)
		self.assertNotIn('synthetic-secret-value', created_config.read_text())

	def test_refuses_jail_specific_custom_action_without_changes(self):
		custom = self.config_dir / 'jail.local'
		custom.write_text('[sshd]\naction = custom-firewall\n')
		custom.chmod(0o644)

		with self.assertRaises(installer.SetupError):
			self.install()

		self.assertFalse(self.lib_dir.exists())
		self.assertFalse((self.action_dir / 'intruvia.py').exists())
		self.assertEqual([], self.commands)

	def test_refuses_custom_default_action_without_changes(self):
		self.config.write_text('[DEFAULT]\naction = %(action_mwl)s\n\n[sshd]\nenabled = true\n')
		self.config.chmod(0o644)

		with self.assertRaises(installer.SetupError):
			self.install()

		self.assertFalse(self.lib_dir.exists())
		self.assertEqual([], self.commands)

	def test_refuses_existing_installation_file_without_overwrite(self):
		target = self.jail_dir / 'intruvia-sshd.local'
		target.write_text('administrator-owned configuration\n')
		target.chmod(0o640)
		original = target.read_bytes()

		with self.assertRaises(installer.SetupError):
			self.install()

		self.assertEqual(original, target.read_bytes())
		self.assertFalse(self.lib_dir.exists())
		self.assertEqual([], self.commands)

	def test_refuses_unsafe_endpoint_pat_and_unconfirmed_action(self):
		for endpoint in ('http://intruvia.example.org/api/v1/fail2ban/events',
				'https://intruvia.example.org/api/v1/fail2ban/events?token=secret',
				'https://intruvia.example.org/api/v1/fail2ban/events\"\n[action]'):
			with self.subTest(endpoint=endpoint):
				with self.assertRaises(installer.SetupError):
					installer.validate_endpoint(endpoint)
		self.pat_file.chmod(0o644)
		with self.assertRaises(installer.SetupError):
			self.install()
		self.pat_file.chmod(0o600)
		with self.assertRaises(installer.SetupError):
			installer.install('sshd', 'https://intruvia.example.org/api/v1/fail2ban/events',
					paths=self.paths, confirm_default_action=False, owner_uid=os.geteuid())
		self.assertFalse(self.lib_dir.exists())
		self.assertEqual([], self.commands)

	def test_refuses_fail2ban_validation_failure_before_writing(self):
		calls = []

		def fail_new_configuration(command):
			calls.append(command[1:])
			status = 1 if command[1:] == ['-t'] and calls.count(['-t']) == 2 else 0
			return subprocess.CompletedProcess(command, status, stdout='', stderr='')

		with self.assertRaises(installer.SetupError):
			self.install(fail_new_configuration)

		self.assertEqual([['-t'], ['status', 'sshd'], ['-t']], calls)
		self.assertFalse(self.lib_dir.exists())
		self.assertFalse((self.action_dir / 'intruvia.py').exists())
		self.assertFalse((self.jail_dir / 'intruvia-sshd.local').exists())
		self.assertEqual('[DEFAULT]\naction = %(action_)s\n\n[sshd]\nenabled = true\n', self.config.read_text())

	def test_refuses_failed_reload_and_removes_only_new_files(self):
		calls = []

		def fail_first_reload(command):
			calls.append(command[1:])
			if command[1:] == ['reload'] and calls.count(['reload']) == 1:
				return subprocess.CompletedProcess(command, 1, stdout='', stderr='')
			return subprocess.CompletedProcess(command, 0, stdout='', stderr='')

		with self.assertRaises(installer.SetupError):
			self.install(fail_first_reload)

		self.assertFalse(self.lib_dir.exists())
		self.assertFalse((self.action_dir / 'intruvia.py').exists())
		self.assertFalse((self.action_dir / 'intruvia.py').is_symlink())
		self.assertFalse((self.jail_dir / 'intruvia-sshd.local').exists())
		self.assertEqual(self.config.read_text(), '[DEFAULT]\naction = %(action_)s\n\n[sshd]\nenabled = true\n')
		self.assertEqual(2, calls.count(['reload']))
		self.assertEqual('opaque-id:synthetic-secret-value\n', self.pat_file.read_text())

	def test_refuses_if_action_not_active_after_reload_and_rolls_back(self):
		calls = []

		def missing_action(command):
			calls.append(command[1:])
			stdout = 'iptables-multiport' if command[1:] == ['get', 'sshd', 'actions'] else ''
			return subprocess.CompletedProcess(command, 0, stdout=stdout, stderr='')

		with self.assertRaises(installer.SetupError):
			self.install(missing_action)

		self.assertFalse(self.lib_dir.exists())
		self.assertFalse((self.action_dir / 'intruvia.py').exists())
		self.assertFalse((self.jail_dir / 'intruvia-sshd.local').exists())
		self.assertEqual(2, calls.count(['reload']))


if __name__ == '__main__':
	unittest.main(verbosity=2)
