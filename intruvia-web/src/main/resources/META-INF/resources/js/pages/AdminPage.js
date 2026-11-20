// SPDX-License-Identifier: AGPL-3.0-only
import * as api from '../api/viewer.js';
import {fail2banInstallCommand} from './fail2banInstall.js';

export default class AdminPage {
	constructor(root, username, sessionEnded) {
		this.root = root;
		this.username = username;
		this.sessionEnded = sessionEnded;
		this.controller = new AbortController();
		this.status = root.querySelector('#admin-status');
		this.users = root.querySelector('#admin-users');
		this.machines = root.querySelector('#admin-machines');
		this.secret = root.querySelector('#issued-secret');
		this.secretValue = root.querySelector('#issued-token');
		this.installCommand = root.querySelector('#install-command');
		this.installContainer = root.querySelector('#install-command-container');
		this.installUnavailable = root.querySelector('#install-unavailable');
		root.querySelector('#create-user-form').addEventListener('submit', (event) => this.createUser(event),
			{signal: this.controller.signal});
		root.querySelector('#create-machine-form').addEventListener('submit', (event) => this.createMachine(event),
			{signal: this.controller.signal});
		root.querySelector('#copy-token').addEventListener('click', () => this.copyToken(), {signal: this.controller.signal});
		root.querySelector('#copy-install-command').addEventListener('click', () => this.copyInstallCommand(),
			{signal: this.controller.signal});
	}

	async load(message = null) {
		this.status.textContent = 'Loading users and machines…';
		try {
			const [users, machines] = await Promise.all([api.getUsers(this.controller.signal), api.getMachines(this.controller.signal)]);
			if (this.controller.signal.aborted) return;
			this.renderUsers(users.users);
			this.renderMachines(machines.machines);
			this.status.textContent = message ?? 'Changes take effect immediately. Ingestion tokens are shown only when created.';
		} catch (error) {
			this.handleError(error);
		}
	}

	async createUser(event) {
		event.preventDefault();
		const form = event.currentTarget;
		const values = new FormData(form);
		const button = form.querySelector('button[type="submit"]');
		button.disabled = true;
		try {
			await api.createUser(Object.fromEntries(values), this.controller.signal);
			form.reset();
			this.status.textContent = 'Account created.';
			await this.load('Account created.');
		} catch (error) {
			this.handleError(error);
		} finally {
			button.disabled = false;
		}
	}

	async createMachine(event) {
		event.preventDefault();
		const form = event.currentTarget;
		const values = new FormData(form);
		const button = form.querySelector('button[type="submit"]');
		button.disabled = true;
		try {
			const machineId = values.get('machineId');
			const result = await api.createMachine({
				username: values.get('username') || machineId, instanceId: values.get('instanceId') || machineId,
				tokenName: values.get('tokenName') || machineId.slice(0, 64),
				validDays: Number(values.get('validDays'))
			}, this.controller.signal);
			form.reset();
			form.querySelector('details').open = false;
			this.revealToken(result.token);
			this.status.textContent = `Machine ${result.username} is ready. Copy its token now.`;
			await this.load(`Machine ${result.username} is ready. Copy its token now; it cannot be shown again.`);
		} catch (error) {
			this.handleError(error);
		} finally {
			button.disabled = false;
		}
	}

	renderUsers(users) {
		this.users.replaceChildren();
		const accounts = users.filter((entry) => !entry.roles.includes('Producer'));
		if (accounts.length === 0) {
			const empty = document.createElement('li');
			empty.textContent = 'No viewer accounts have been created.';
			this.users.append(empty);
		}
		for (const user of accounts) {
			const item = document.createElement('li');
			const details = document.createElement('p');
			details.textContent = `${user.username} · ${user.firstName ?? ''} ${user.lastName ?? ''} · ${user.roles.join(', ')} · ${user.state}`;
			item.append(details);

			const passwordForm = document.createElement('form');
			const password = document.createElement('input');
			password.type = 'password';
			password.autocomplete = 'new-password';
			password.required = true;
			password.maxLength = 1024;
			password.setAttribute('aria-label', `New password for ${user.username}`);
			const savePassword = this.button('Set password');
			savePassword.type = 'submit';
			passwordForm.append(password, savePassword);
			passwordForm.addEventListener('submit', async (event) => {
				event.preventDefault();
				savePassword.disabled = true;
				try {
					await api.setUserPassword(user.username, password.value, this.controller.signal);
					password.value = '';
					this.status.textContent = `Password updated for ${user.username}.`;
				} catch (error) {
					this.handleError(error);
				} finally { savePassword.disabled = false; }
			});
			item.append(passwordForm);

			if (user.username !== this.username) {
				const state = user.state === 'ENABLED' ? 'DISABLED' : 'ENABLED';
				const changeState = this.button(user.state === 'ENABLED' ? 'Disable account' : 'Enable account');
				changeState.addEventListener('click', () => this.perform(async () => {
					await api.setUserState(user.username, state, this.controller.signal);
					await this.load(`Account ${user.username} updated.`);
				}));
				item.append(changeState);
			}
			if (!user.roles.includes('Admin') && user.username !== this.username) {
				const remove = this.button('Delete account');
				remove.addEventListener('click', () => this.perform(async () => {
					if (!window.confirm(`Delete the account ${user.username}?`)) return;
					await api.deleteUser(user.username, this.controller.signal);
					await this.load(`Account ${user.username} deleted.`);
				}));
				item.append(remove);
			}
			this.users.append(item);
		}
	}

	renderMachines(machines) {
		this.machines.replaceChildren();
		if (machines.length === 0) {
			const empty = document.createElement('li');
			empty.textContent = 'No machines have been configured.';
			this.machines.append(empty);
		}
		for (const machine of machines) {
			const item = document.createElement('li');
			const title = document.createElement('p');
			title.textContent = `${machine.username} · ${machine.state}${machine.instanceId ? ` · ${machine.instanceId}` : ' · not assigned'}`;
			item.append(title);

			const mappingForm = document.createElement('form');
			const instance = document.createElement('input');
			instance.required = true;
			instance.maxLength = 128;
			instance.value = machine.instanceId;
			instance.setAttribute('aria-label', `Instance ID for ${machine.username}`);
			const saveMapping = this.button('Save machine identity');
			saveMapping.type = 'submit';
			mappingForm.append(instance, saveMapping);
			mappingForm.addEventListener('submit', async (event) => {
				event.preventDefault();
				saveMapping.disabled = true;
				try {
					await api.setMachineIdentity(machine.username, instance.value, this.controller.signal);
					await this.load(`Identity updated for ${machine.username}; ingestion uses it immediately.`);
				} catch (error) {
					this.handleError(error);
				} finally { saveMapping.disabled = false; }
			});
			item.append(mappingForm);

			const tokens = document.createElement('ul');
			for (const token of machine.tokens) {
				const tokenItem = document.createElement('li');
				const tokenDetails = document.createElement('span');
				const expiry = token.validTo === '9999-12-31T23:59:59Z' ?
					'practically does not expire (through 9999-12-31 UTC)' : `expires ${new Date(token.validTo).toLocaleString()}`;
				tokenDetails.textContent = `${token.name} · ${expiry} · ${token.privileges.join(', ')}`;
				const revoke = this.button('Revoke token');
				revoke.addEventListener('click', () => this.perform(async () => {
					if (!window.confirm(`Revoke the token ${token.name} for ${machine.username}?`)) return;
					await api.revokeMachineToken(machine.username, token.tokenId, this.controller.signal);
					await this.load(`Token ${token.name} revoked.`);
				}));
				tokenItem.append(tokenDetails, revoke);
				tokens.append(tokenItem);
			}
			item.append(tokens);

			const rotateForm = document.createElement('form');
			const name = document.createElement('input');
			name.required = true;
			name.maxLength = 64;
			name.value = 'rotation';
			name.setAttribute('aria-label', `New token name for ${machine.username}`);
			const days = document.createElement('input');
			days.type = 'number';
			days.required = true;
			days.min = '0';
			days.max = '3650';
			days.value = '365';
			days.title = 'Enter 0 for practical non-expiry; maximum 3650 days.';
			days.setAttribute('aria-label', `New token validity in days for ${machine.username}`);
			const rotate = this.button('Create another token');
			rotate.type = 'submit';
			rotateForm.append(name, days, rotate);
			rotateForm.addEventListener('submit', async (event) => {
				event.preventDefault();
				rotate.disabled = true;
				try {
					const result = await api.createMachineToken(machine.username,
						{name: name.value, validDays: Number(days.value)}, this.controller.signal);
					this.revealToken(result.token);
					await this.load(`New token for ${machine.username} created. Copy it now; it cannot be shown again.`);
				} catch (error) {
					this.handleError(error);
				} finally { rotate.disabled = false; }
			});
			item.append(rotateForm);

			if (machine.tokens.length === 0) {
				const remove = this.button('Remove machine');
				remove.addEventListener('click', () => this.perform(async () => {
					if (!window.confirm(`Remove machine ${machine.username}?`)) return;
					await api.deleteMachine(machine.username, this.controller.signal);
					await this.load(`Machine ${machine.username} removed.`);
				}));
				item.append(remove);
			}
			this.machines.append(item);
		}
	}

	async perform(action) {
		try { await action(); }
		catch (error) { this.handleError(error); }
	}

	revealToken(token) {
		this.secretValue.value = token;
		const command = fail2banInstallCommand(window.location.origin);
		this.installCommand.value = command ?? '';
		this.installContainer.hidden = !command;
		this.installUnavailable.hidden = !!command;
		this.secret.hidden = false;
		this.secretValue.focus();
		this.secretValue.select();
	}

	hideSecret() {
		this.secretValue.value = '';
		this.installCommand.value = '';
		this.secret.hidden = true;
	}

	async copyInstallCommand() {
		try {
			await navigator.clipboard.writeText(this.installCommand.value);
			this.status.textContent = 'Installation command copied. Run it on the Fail2ban host.';
		} catch {
			this.installCommand.focus();
			this.installCommand.select();
			this.status.textContent = 'Select and copy the installation command.';
		}
	}

	async copyToken() {
		try {
			await navigator.clipboard.writeText(this.secretValue.value);
			this.status.textContent = 'Token copied. Store it securely; it will not be shown again.';
		} catch {
			this.secretValue.focus();
			this.secretValue.select();
			this.status.textContent = 'Select and copy the token, then store it securely.';
		}
	}

	button(label) {
		const button = document.createElement('button');
		button.type = 'button';
		button.textContent = label;
		return button;
	}

	handleError(error) {
		if (error.name === 'AbortError') return;
		if (error.status === 401) {
			this.sessionEnded();
			return;
		}
		this.status.textContent = error.status === 403 ? 'Administrator access is required.' :
			error.status === 409 ? 'That username or machine identity may already be in use, or the password may not meet the configured policy.' :
			'Unable to complete the request. Check the values and try again.';
	}

	destroy() {
		this.controller.abort();
		this.hideSecret();
		this.root.hidden = true;
	}
}
