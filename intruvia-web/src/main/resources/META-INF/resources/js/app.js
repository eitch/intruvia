// SPDX-License-Identifier: AGPL-3.0-only
import * as api from './api/viewer.js';
import ViewerPage from './pages/ViewerPage.js';
import AdminPage from './pages/AdminPage.js';

const status = document.querySelector('#status');
const loginPanel = document.querySelector('#login');
const form = loginPanel.querySelector('form');
const account = document.querySelector('#account');
const logoutButton = document.querySelector('#logout');
const manageButton = document.querySelector('#manage');
const adminRoot = document.querySelector('#admin');
const viewerRoot = document.querySelector('#viewer');
const controller = new AbortController();
let page = null;
let adminPage = null;
let loginAttempt = 0;

function showLogin(message) {
	loginAttempt++;
	page?.destroy();
	page = null;
	adminPage?.destroy();
	adminPage = null;
	adminRoot.hidden = true;
	viewerRoot.hidden = true;
	manageButton.hidden = true;
	account.hidden = true;
	loginPanel.hidden = false;
	status.textContent = message;
	form.elements.username.focus();
}
function showViewer(session) {
	const attempt = ++loginAttempt;
	page?.destroy();
	adminPage?.destroy();
	adminPage = null;
	manageButton.hidden = true;
	adminRoot.hidden = true;
	loginPanel.hidden = true;
	account.hidden = false;
	document.querySelector('#username').textContent = session.username;
	page = new ViewerPage(viewerRoot, status, () => showLogin('Your session ended. Please sign in.'));
	page.load();
	api.getUsers(controller.signal).then(() => {
		if (attempt !== loginAttempt) return;
		manageButton.hidden = false;
		adminPage = new AdminPage(adminRoot, session.username, () => showLogin('Your session ended. Please sign in.'));
	}).catch((error) => {
		if (error.status === 401 && attempt === loginAttempt) showLogin('Your session ended. Please sign in.');
	});
}
async function signIn(event) {
	event.preventDefault();
	const button = form.querySelector('button');
	button.disabled = true;
	try {
		const session = await api.login(form.elements.username.value, form.elements.password.value, controller.signal);
		form.elements.password.value = '';
		showViewer(session);
	} catch (error) {
		if (error.name !== 'AbortError') status.textContent = error.status === 401 ? 'Sign-in failed. Check your credentials.' : error.message;
	} finally { button.disabled = false; }
}
async function signOut() {
	logoutButton.disabled = true;
	try {
		await api.logout(controller.signal);
		showLogin('Signed out.');
	} catch (error) {
		if (error.status === 401) showLogin('Your session ended. Please sign in.');
		else if (error.name !== 'AbortError') status.textContent = 'Logout failed. Your session may still be active. Try again.';
	} finally { logoutButton.disabled = false; }
}
async function start() {
	try { showViewer(await api.getSession(controller.signal)); }
	catch (error) {
		if (error.name !== 'AbortError') showLogin(error.status === 401 ? 'Sign in with your viewer account.' : error.message);
	}
}
function showAdministration() {
	if (!adminPage) return;
	viewerRoot.hidden = true;
	adminRoot.hidden = false;
	adminPage.load();
}
function showEvents() {
	adminPage?.hideSecret();
	adminRoot.hidden = true;
	viewerRoot.hidden = false;
}
form.addEventListener('submit', signIn);
logoutButton.addEventListener('click', signOut);
manageButton.addEventListener('click', showAdministration);
document.querySelector('#back-to-events').addEventListener('click', showEvents);
window.addEventListener('pagehide', () => { controller.abort(); page?.destroy(); adminPage?.destroy(); });
window.addEventListener('pageshow', (event) => { if (event.persisted) window.location.reload(); });
start();
