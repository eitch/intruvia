// SPDX-License-Identifier: AGPL-3.0-only
// Cursor stays a decimal string; only advance after the view has applied the candidate model.
export function applyFrame(model, frame) {
	if (frame.version !== 1 || frame.type !== 'event.created' || typeof frame.sequence !== 'string' ||
			!(/^[1-9][0-9]*$/).test(frame.sequence) || frame.event?.schemaVersion !== 1 ||
			frame.event.sequence !== frame.sequence || typeof frame.event.id !== 'string' || !frame.event.id) {
		throw new Error('Unsupported live event.');
	}
	if (BigInt(frame.sequence) <= BigInt(model.cursor)) return null;
	if (model.items.some((event) => event.id === frame.event.id)) throw new Error('Inconsistent live event identity.');
	const items = [...model.items, frame.event];
	const evicted = items.length > 1000 ? items.shift() : null;
	return {model: {items, cursor: frame.sequence, truncated: model.truncated || !!evicted}, event: frame.event, evicted};
}
