// SPDX-License-Identifier: AGPL-3.0-only
// One socket; the owning recovery coordinator decides when to reconnect.
export function openStream(after, onFrame, onClosed) {
	const url = new URL('/api/v1/events/stream', window.location.href);
	url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
	url.searchParams.set('after', after);
	const socket = new WebSocket(url);
	socket.onmessage = (message) => {
		try { onFrame(JSON.parse(message.data)); }
		catch { onClosed(true); }
	};
	socket.onclose = () => onClosed(false);
	return () => {
		socket.onmessage = null;
		socket.onclose = null;
		socket.close();
	};
}
