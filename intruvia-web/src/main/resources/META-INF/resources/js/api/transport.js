// SPDX-License-Identifier: AGPL-3.0-only
export class ApiError extends Error {
	constructor(status) {
		super(status === 401 ? 'Your session ended. Please sign in.' :
				status === 403 ? 'Access denied. Check your viewer permissions and same-origin configuration.' :
				'Request failed. Check the service and try again.');
		this.status = status;
	}
}

export async function request(path, {method = 'GET', body, signal} = {}) {
	const headers = {Accept: 'application/json'};
	if (method !== 'GET') headers['X-Intruvia-CSRF'] = '1';
	if (body !== undefined) headers['Content-Type'] = 'application/json';
	const response = await fetch(path, {method, headers, signal, credentials: 'same-origin', cache: 'no-store',
		redirect: 'error', body: body === undefined ? undefined : JSON.stringify(body)});
	if (!response.ok) throw new ApiError(response.status);
	return response.status === 204 ? null : response.json();
}
