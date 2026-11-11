// SPDX-License-Identifier: AGPL-3.0-only
export function snapshotModel(snapshot) {
	if (!Array.isArray(snapshot.items) || !/^(0|[1-9][0-9]*)$/.test(snapshot.cursor)) throw new Error('Unsupported snapshot response.');
	const items = [...snapshot.items];
	for (const event of items) {
		if (event.schemaVersion !== 1 || typeof event.sequence !== 'string' || !/^[1-9][0-9]*$/.test(event.sequence)) {
			throw new Error('Unsupported event version or sequence.');
		}
	}
	items.sort((left, right) => {
		const a = BigInt(left.sequence), b = BigInt(right.sequence);
		return a < b ? -1 : a > b ? 1 : 0;
	});
	return {items: items.slice(-1000), cursor: snapshot.cursor, truncated: snapshot.truncated || items.length > 1000};
}
