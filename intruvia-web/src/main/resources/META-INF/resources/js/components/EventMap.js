// SPDX-License-Identifier: AGPL-3.0-only
import {formatNumber, hasCoordinates} from '../utils/format.js';
import {eventDetails} from './eventDetails.js';

export default class EventMap {
	constructor(root, boundaries) {
		this.map = L.map(root, {minZoom: 0, maxZoom: 12, zoomSnap: .25, worldCopyJump: false});
		this.map.fitBounds([[-60, -180], [85, 180]]);
		L.geoJSON(boundaries, {interactive: false, style: {color: '#758f9d', weight: 1, fillColor: '#f6f2e7', fillOpacity: 1}}).addTo(this.map);
		this.markers = L.layerGroup().addTo(this.map);
		this.byId = new Map();
		this.locations = new Map();
		this.highlights = new Map();
	}
	render(events) {
		this.clear();
		for (const event of events) this.add(event);
		this.map.invalidateSize();
	}
	add(event) {
		if (!hasCoordinates(event)) return;
		const point = [event.geo.latitude, event.geo.longitude];
		const key = point.join(',');
		let location = this.locations.get(key);
		if (!location) {
			location = {ids: new Set(), label: null, point};
			this.locations.set(key, location);
		}
		location.ids.add(event.id);
		const marker = L.circleMarker(point, {
			radius: 6, color: '#73370b', weight: 2, fillColor: '#ed9b35', fillOpacity: .85
		}).bindPopup(eventDetails(event)).addTo(this.markers);
		this.byId.set(event.id, {marker, key});
		this.updateCount(location);
	}
	updateCount(location) {
		if (location.label) this.markers.removeLayer(location.label);
		location.label = null;
		if (location.ids.size < 2) return;
		const label = document.createElement('span');
		label.textContent = formatNumber(location.ids.size);
		label.setAttribute('aria-label', `${formatNumber(location.ids.size)} events at this location`);
		location.label = L.marker(location.point, {
			interactive: false, keyboard: false, icon: L.divIcon({className: 'overlap-count', html: label, iconAnchor: [-10, 20]})
		}).addTo(this.markers);
	}
	apply(event, evicted) {
		if (evicted) this.remove(evicted.id);
		this.add(event);
		const entry = this.byId.get(event.id);
		if (!entry) return;
		const index = this.locations.get(entry.key).ids.size - 1;
		const ring = L.circleMarker(entry.marker.getLatLng(), {
			radius: 14 + (index % 8) * 4, color: '#b52b00', weight: 4, fill: false, interactive: false,
			className: 'event-halo'
		}).addTo(this.markers);
		const element = ring.getElement();
		element.dataset.eventId = event.id;
		element.style.animationDelay = `${-(index % 6) / 6}s`;
		const timer = setTimeout(() => {
			this.markers.removeLayer(ring);
			this.highlights.delete(event.id);
		}, 3000);
		this.highlights.set(event.id, {ring, timer});
	}
	remove(id) {
		const highlight = this.highlights.get(id);
		if (highlight) {
			clearTimeout(highlight.timer);
			this.markers.removeLayer(highlight.ring);
			this.highlights.delete(id);
		}
		const entry = this.byId.get(id);
		if (!entry) return;
		this.markers.removeLayer(entry.marker);
		this.byId.delete(id);
		const location = this.locations.get(entry.key);
		location.ids.delete(id);
		this.updateCount(location);
		if (!location.ids.size) this.locations.delete(entry.key);
	}
	show(event) {
		const entry = this.byId.get(event.id);
		if (entry) { this.map.panTo(entry.marker.getLatLng()); entry.marker.openPopup(); }
	}
	clear() {
		for (const {timer} of this.highlights.values()) clearTimeout(timer);
		this.highlights.clear();
		this.markers.clearLayers();
		this.byId.clear();
		this.locations.clear();
	}
	destroy() { this.clear(); this.map.remove(); }
}
