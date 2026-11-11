// SPDX-License-Identifier: AGPL-3.0-only
const dateFormat = new Intl.DateTimeFormat(undefined, {dateStyle: 'medium', timeStyle: 'medium'});
const numberFormat = new Intl.NumberFormat(undefined, {maximumFractionDigits: 3});

export function formatTime(value) {
	const date = value ? new Date(value) : null;
	return date && Number.isFinite(date.getTime()) ? dateFormat.format(date) : 'Unknown time';
}
export function formatNumber(value) { return numberFormat.format(value); }
export function locationText(geo = {}) {
	return [geo.city, geo.region, geo.countryName || geo.countryCode].filter(Boolean).join(', ') || 'Location unavailable';
}
export function hasCoordinates(event) {
	const geo = event.geo;
	return geo?.status === 'FOUND' && Number.isFinite(geo.latitude) && Number.isFinite(geo.longitude) &&
		Math.abs(geo.latitude) <= 90 && Math.abs(geo.longitude) <= 180;
}
export function unmappedReason(event) {
	return ({FOUND: 'No coordinates in location record', NON_PUBLIC: 'Non-public address', NOT_FOUND: 'No location record',
		UNAVAILABLE: 'GeoIP database unavailable', ERROR: 'GeoIP lookup failed'})[event.geo?.status] || 'Location unavailable';
}
