# SPDX-License-Identifier: AGPL-3.0-only
"""Additive Fail2ban Python action: only ban() notifies, without a shell."""
import logging
import subprocess

from fail2ban.server.action import ActionBase

LOG = logging.getLogger('fail2ban.action.intruvia')


class IntruviaAction(ActionBase):
	norestored = True

	def reban(self, aInfo):
		pass  # Reconciliation of an existing ban is not a new observation.

	def __init__(self, jail, name, endpoint, token_file='/etc/fail2ban/intruvia.pat', ca_file=None,
			helper='/usr/local/lib/intruvia/intruvia_notify.py', python='/usr/bin/python3'):
		super().__init__(jail, name)
		self.endpoint = endpoint
		self.token_file = token_file
		self.ca_file = ca_file
		self.helper = helper
		self.python = python

	def ban(self, aInfo):
		# No <matches>, shell interpolation, eval or credential in argv.
		args = [self.python, self.helper, '--endpoint=' + self.endpoint, '--token-file=' + self.token_file,
				'--ip=' + str(aInfo['ip']), '--jail=' + self._jail.name]
		if self.ca_file:
			args.append('--ca-file=' + self.ca_file)
		try:
			failures = aInfo.get('failures')
			if failures is not None:
				args.append('--failures=' + str(int(failures)))
			result = subprocess.run(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
					stderr=subprocess.DEVNULL, timeout=16, check=False)
			if result.returncode:
				LOG.warning('Intruvia notification failed; event may be lost')
		except (OSError, ValueError, TypeError, subprocess.TimeoutExpired):
			LOG.warning('Intruvia notification failed; event may be lost')
		# Best effort: never fail the independent firewall action.


Action = IntruviaAction
