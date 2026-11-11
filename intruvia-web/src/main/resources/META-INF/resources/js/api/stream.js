// SPDX-License-Identifier: AGPL-3.0-only
// One connection owned by the page. Automatic recovery belongs to task 017.
export function openStream(after, onFrame, onClosed) {
	const url = new URL('/api/v1/events/stream', window.location.href);
	url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
	url.searchParams.set('after', after);
	const socket = new WebSocket(url);
	socket.onmessage = (message) => {
		try { onFrame(JSON.parse(message.data)); }
		catch {
			socket.onmessage = null;
			socket.onclose = null;
			onClosed('Live update could not be applied. Refresh the snapshot.');
			socket.close();
		}
	};
	socket.onclose = () => onClosed('Offline · Refresh the snapshot to reconnect.');
	return () => {
		socket.onmessage = null;
		socket.onclose = null;
		socket.close();
	};
}
