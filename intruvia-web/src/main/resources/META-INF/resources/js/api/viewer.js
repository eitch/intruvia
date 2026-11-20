// SPDX-License-Identifier: AGPL-3.0-only
import {request} from './transport.js';

export const getSession = (signal) => request('/api/v1/session', {signal});
export const login = (username, password, signal) => request('/api/v1/session', {method: 'POST', body: {username, password}, signal});
export const logout = (signal) => request('/api/v1/session', {method: 'DELETE', signal});
export const getSnapshot = (signal) => request('/api/v1/events/snapshot?limit=1000', {signal});
export const getWorldBoundaries = (signal) => request('./data/world-110m.geojson', {signal});
export const getUsers = (signal) => request('/api/v1/admin/users', {signal});
export const createUser = (user, signal) => request('/api/v1/admin/users', {method: 'POST', body: user, signal});
export const setUserPassword = (username, password, signal) => request(`/api/v1/admin/users/${encodeURIComponent(username)}/password`,
	{method: 'PUT', body: {password}, signal});
export const setUserState = (username, state, signal) => request(`/api/v1/admin/users/${encodeURIComponent(username)}/state`,
	{method: 'PUT', body: {state}, signal});
export const deleteUser = (username, signal) => request(`/api/v1/admin/users/${encodeURIComponent(username)}`,
	{method: 'DELETE', signal});
export const getMachines = (signal) => request('/api/v1/admin/machines', {signal});
export const createMachine = (machine, signal) => request('/api/v1/admin/machines', {method: 'POST', body: machine, signal});
export const setMachineIdentity = (username, instanceId, signal) => request(`/api/v1/admin/machines/${encodeURIComponent(username)}`,
	{method: 'PUT', body: {instanceId}, signal});
export const deleteMachine = (username, signal) => request(`/api/v1/admin/machines/${encodeURIComponent(username)}`,
	{method: 'DELETE', signal});
export const createMachineToken = (username, token, signal) => request(`/api/v1/admin/machines/${encodeURIComponent(username)}/tokens`,
	{method: 'POST', body: token, signal});
export const revokeMachineToken = (username, tokenId, signal) => request(
	`/api/v1/admin/machines/${encodeURIComponent(username)}/tokens/${encodeURIComponent(tokenId)}`, {method: 'DELETE', signal});
