// SPDX-License-Identifier: AGPL-3.0-only
import {request} from './transport.js';

export const getSession = (signal) => request('/api/v1/session', {signal});
export const login = (username, password, signal) => request('/api/v1/session', {method: 'POST', body: {username, password}, signal});
export const logout = (signal) => request('/api/v1/session', {method: 'DELETE', signal});
export const getSnapshot = (signal) => request('/api/v1/events/snapshot?limit=1000', {signal});
export const getWorldBoundaries = (signal) => request('./data/world-110m.geojson', {signal});
