# SPDX-License-Identifier: AGPL-3.0-only
"""Run against an unpacked real Fail2ban 1.1.0 tree, without firewall privileges."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

from test_notify import NotifyTest, ROOT


def wait_for(check, timeout=8):
	end = time.monotonic() + timeout
	while time.monotonic() < end:
		if check():
			return
		time.sleep(0.1)
	raise AssertionError('smoke condition timed out')


def main():
	upstream = Path(sys.argv[1]).resolve()
	NotifyTest.setUpClass()
	fixture = NotifyTest()
	try:
		with tempfile.TemporaryDirectory(prefix='intruvia-018-action-') as directory, fixture.server([(201, {}), (403, {}), (201, {})]) as https:
			base = Path(directory)
			config = base / 'config'
			shutil.copytree(upstream / 'config', config)
			shutil.copy(ROOT / 'intruvia.py', config / 'action.d/intruvia.py')
			marker = base / 'firewall-marker'
			(config / 'action.d/smoke-firewall.conf').write_text(
					'[Definition]\nactionban = echo preserved >> ' + str(marker) + '\nactionunban =\n')
			(config / 'filter.d/smoke.conf').write_text('[Definition]\nfailregex = ^FAIL <HOST>$\n')
			(base / 'input.log').touch()
			(config / 'jail.conf').write_text('[DEFAULT]\nbackend = polling\nbantime = 600\nfindtime = 600\nmaxretry = 1\n'
					'action_ = smoke-firewall\n[sshd]\nenabled = true\nfilter = smoke\nlogpath = ' + str(base / 'input.log') + '\n')
			# Use the shipped additive installation template, with fixture-only options.
			example = (ROOT / 'jail.local.example').read_text().replace(
					'https://intruvia.example.org/api/v1/fail2ban/events', https.endpoint)
			example = example.replace('events"]', 'events", token_file="' + str(fixture.token) + '", ca_file="' +
					str(fixture.cert) + '", helper="' + str(ROOT / 'intruvia_notify.py') + '", python="' + sys.executable + '"]')
			(config / 'jail.local').write_text(example)
			(config / 'fail2ban.local').write_text('[Definition]\ndbfile = None\nlogtarget = ' + str(base / 'server.log') + '\n')
			env = dict(os.environ, PYTHONPATH=str(upstream), PYTHONDONTWRITEBYTECODE='1')
			options = ['-c', str(config), '-s', str(base / 'socket'), '-p', str(base / 'pid')]
			client = [sys.executable, str(upstream / 'bin/fail2ban-client'), *options]
			def call(*args):
				result = subprocess.run([*client, *args], env=env, capture_output=True, text=True, timeout=25)
				assert result.returncode == 0, (args, result.stdout, result.stderr)
				return result.stdout.strip()
			print('Fail2ban:', call('--version'))
			print('Configuration:', call('-t'))
			with (base / 'process.log').open('w') as output:
				process = subprocess.Popen([sys.executable, str(upstream / 'bin/fail2ban-server'), *options, '-f', 'start'],
						env=env, stdout=output, stderr=output)
				try:
					wait_for(lambda: (base / 'socket').exists())
					wait_for(lambda: 'sshd' in call('status'))
					print('Actions:', call('get', 'sshd', 'actions'))
					call('set', 'sshd', 'banip', '2001:db8::1')
					wait_for(lambda: len(https.requests) == 1)
					wait_for(lambda: marker.exists())
					value = json.loads(https.requests[0][1])
					assert value['ip'] == '2001:db8::1' and value['jail'] == 'sshd'
					call('set', 'sshd', 'unbanip', '2001:db8::1')
					time.sleep(0.5)
					assert len(https.requests) == 1
					# A failed notification still leaves the independent ban action effective.
					call('set', 'sshd', 'banip', '192.0.2.2')
					wait_for(lambda: len(https.requests) == 2)
					wait_for(lambda: len(marker.read_text().splitlines()) == 2)
					assert '192.0.2.2' in call('get', 'sshd', 'banip')
					time.sleep(0.5)
					assert len(https.requests) == 2
					call('stop')
					process.wait(timeout=10)
					assert len(https.requests) == 2
					print('PASS: real config/start/ban/unban/stop; additive firewall marker twice; IPv6 JSON; '
							'403 notification failure preserves ban; only two ban callbacks notify.')
					assert 'Intruvia notification failed; event may be lost' in (base / 'server.log').read_text()
					print('PASS: concise local failure warning present.')
					# Exercise the real module loader with an adversarial jail name.
					sys.path.insert(0, str(upstream))
					from fail2ban.server.jail import Jail
					jail_name = '-x; $(touch /tmp/intruvia-018-INJECTION) `id` " \\ ü'
					jail = Jail(jail_name, backend='polling')
					jail.actions.add('intruvia', pythonModule=str(ROOT / 'intruvia.py'), initOpts=dict(
							endpoint=https.endpoint, token_file=str(fixture.token), ca_file=str(fixture.cert),
							helper=str(ROOT / 'intruvia_notify.py'), python=sys.executable))
					action = jail.actions['intruvia']
					action.start()
					action.ban({'ip': '2001:db8::3', 'failures': 7})
					assert json.loads(https.requests[2][1])['jail'] == jail_name
					action.reban({'ip': '2001:db8::3'})
					action.unban({'ip': '2001:db8::3'})
					action.stop()
					assert action.norestored and len(https.requests) == 3
					assert not Path('/tmp/intruvia-018-INJECTION').exists()
					print('PASS: real ActionBase loader, shell-looking jail passed unchanged; start/reban/unban/stop do not notify.')
				finally:
					if process.poll() is None:
						process.terminate()
						process.wait(timeout=10)
					print((base / 'process.log').read_text())
	finally:
		NotifyTest.tearDownClass()


if __name__ == '__main__':
	main()
