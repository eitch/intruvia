// SPDX-License-Identifier: AGPL-3.0-only
import {hasCoordinates} from '../utils/format.js';
import {eventDetails} from './eventDetails.js';

export default class EventMap {
	constructor(root, boundaries) {
		this.map = L.map(root, {minZoom: 0, maxZoom: 12, zoomSnap: .25, worldCopyJump: false});
		this.map.fitBounds([[-60, -180], [85, 180]]);
		L.geoJSON(boundaries, {interactive: false, style: {color: '#758f9d', weight: 1, fillColor: '#f6f2e7', fillOpacity: 1}}).addTo(this.map);
		this.markers = L.layerGroup().addTo(this.map);
		this.byId = new Map();
	}
	render(events) {
		this.markers.clearLayers();
		this.byId.clear();
		for (const event of events) {
			if (!hasCoordinates(event)) continue;
			const marker = L.circleMarker([event.geo.latitude, event.geo.longitude], {
				radius: 6, color: '#73370b', weight: 2, fillColor: '#ed9b35', fillOpacity: .85
			}).bindPopup(eventDetails(event)).addTo(this.markers);
			this.byId.set(event.id, marker);
		}
		this.map.invalidateSize();
	}
	show(event) {
		const marker = this.byId.get(event.id);
		if (marker) { this.map.panTo(marker.getLatLng()); marker.openPopup(); }
	}
	destroy() { this.map.remove(); this.byId.clear(); }
}
