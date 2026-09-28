// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.model;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import li.strolch.model.Resource;
import li.strolch.model.StrolchRootElement;
import li.strolch.model.xml.StrolchXmlHelper;

import static li.intruvia.core.model.ModelConstants.*;

/** Detached mapping only; persistence and locking belong to Strolch transactions. */
public final class EventModelMapper {
	private final Map<String, StrolchRootElement> templates;

	public EventModelMapper() {
		try (var stream = EventModelMapper.class.getResourceAsStream("/model/templates.xml")) {
			if (stream == null)
				throw new IllegalStateException("Missing event model templates");
			this.templates = StrolchXmlHelper.parseToMap(stream, "UTF-8");
		} catch (IOException e) {
			throw new IllegalStateException("Cannot read event model templates", e);
		}
	}

	private Resource create(String type, String id) {
		Resource resource = ((Resource) this.templates.get(type)).getClone();
		resource.setId(id);
		return resource;
	}

	private static void requireType(Resource resource, String type) {
		if (!type.equals(resource.getType()))
			throw new IllegalArgumentException("Unexpected model type: " + resource.getType());
	}

	public Resource toResource(SecurityEvent value) {
		Resource resource = create(TYPE_SECURITY_EVENT, value.id().toString());
		resource.setInteger(BAG_ENVELOPE, PARAM_SCHEMA_VERSION, value.schemaVersion());
		resource.setLong(BAG_ENVELOPE, PARAM_SEQUENCE, Long.parseLong(value.sequence()));
		resource.setString(BAG_ENVELOPE, PARAM_CATEGORY, value.category());
		resource.setString(BAG_ENVELOPE, PARAM_ACTION, value.action());
		resource.setString(BAG_ENVELOPE, PARAM_OCCURRED_AT, value.occurredAt());
		resource.setString(BAG_ENVELOPE, PARAM_RECEIVED_AT, value.receivedAt());
		resource.setString(BAG_SOURCE, PARAM_TYPE, value.source().type());
		resource.setString(BAG_SOURCE, PARAM_INSTANCE_ID, value.source().instanceId());
		resource.setString(BAG_SOURCE, PARAM_EVENT_ID, value.source().eventId().toString());
		resource.setString(BAG_SUBJECT, PARAM_IP, value.subject().ip());
		resource.setString(BAG_OBSERVER, PARAM_ID, value.observer().id());
		resource.setString(BAG_ATTRIBUTES_FAIL2BAN, PARAM_JAIL, value.attributes().fail2ban().jail());
		if (value.attributes().fail2ban().failures() == null)
			resource.removeParameter(BAG_ATTRIBUTES_FAIL2BAN, PARAM_FAILURES);
		else
			resource.setInteger(BAG_ATTRIBUTES_FAIL2BAN, PARAM_FAILURES, value.attributes().fail2ban().failures());
		resource.setString(BAG_GEO, PARAM_STATUS, value.geo().status().name());
		if (value.geo().countryCode() == null)
			resource.removeParameter(BAG_GEO, PARAM_COUNTRY_CODE);
		else
			resource.setString(BAG_GEO, PARAM_COUNTRY_CODE, value.geo().countryCode());
		if (value.geo().countryName() == null)
			resource.removeParameter(BAG_GEO, PARAM_COUNTRY_NAME);
		else
			resource.setString(BAG_GEO, PARAM_COUNTRY_NAME, value.geo().countryName());
		if (value.geo().region() == null)
			resource.removeParameter(BAG_GEO, PARAM_REGION);
		else
			resource.setString(BAG_GEO, PARAM_REGION, value.geo().region());
		if (value.geo().city() == null)
			resource.removeParameter(BAG_GEO, PARAM_CITY);
		else
			resource.setString(BAG_GEO, PARAM_CITY, value.geo().city());
		if (value.geo().latitude() == null)
			resource.removeParameter(BAG_GEO, PARAM_LATITUDE);
		else
			resource.setDouble(BAG_GEO, PARAM_LATITUDE, value.geo().latitude());
		if (value.geo().longitude() == null)
			resource.removeParameter(BAG_GEO, PARAM_LONGITUDE);
		else
			resource.setDouble(BAG_GEO, PARAM_LONGITUDE, value.geo().longitude());
		if (value.geo().accuracyRadiusKm() == null)
			resource.removeParameter(BAG_GEO, PARAM_ACCURACY_RADIUS_KM);
		else
			resource.setDouble(BAG_GEO, PARAM_ACCURACY_RADIUS_KM, value.geo().accuracyRadiusKm());
		if (value.geo().provider() == null)
			resource.removeParameter(BAG_GEO, PARAM_PROVIDER);
		else
			resource.setString(BAG_GEO, PARAM_PROVIDER, value.geo().provider());
		if (value.geo().databaseEdition() == null)
			resource.removeParameter(BAG_GEO, PARAM_DATABASE_EDITION);
		else
			resource.setString(BAG_GEO, PARAM_DATABASE_EDITION, value.geo().databaseEdition());
		if (value.geo().databaseBuildAt() == null)
			resource.removeParameter(BAG_GEO, PARAM_DATABASE_BUILD_AT);
		else
			resource.setString(BAG_GEO, PARAM_DATABASE_BUILD_AT, value.geo().databaseBuildAt());
		if (value.geo().lookedUpAt() == null)
			resource.removeParameter(BAG_GEO, PARAM_LOOKED_UP_AT);
		else
			resource.setString(BAG_GEO, PARAM_LOOKED_UP_AT, value.geo().lookedUpAt());
		return resource;
	}

	public SecurityEvent toEvent(Resource resource) {
		requireType(resource, TYPE_SECURITY_EVENT);
		return new SecurityEvent(
				resource.getInteger(BAG_ENVELOPE, PARAM_SCHEMA_VERSION),
				UUID.fromString(resource.getId()),
				Long.toString(resource.getLong(BAG_ENVELOPE, PARAM_SEQUENCE)),
				new SecurityEvent.Source(resource.getString(BAG_SOURCE, PARAM_TYPE),
						resource.getString(BAG_SOURCE, PARAM_INSTANCE_ID), UUID.fromString(resource.getString(BAG_SOURCE, PARAM_EVENT_ID))),
				resource.getString(BAG_ENVELOPE, PARAM_CATEGORY),
				resource.getString(BAG_ENVELOPE, PARAM_ACTION),
				resource.getString(BAG_ENVELOPE, PARAM_OCCURRED_AT),
				resource.getString(BAG_ENVELOPE, PARAM_RECEIVED_AT),
				new SecurityEvent.Subject(resource.getString(BAG_SUBJECT, PARAM_IP)),
				new SecurityEvent.Observer(resource.getString(BAG_OBSERVER, PARAM_ID)),
				new SecurityEvent.Attributes(new SecurityEvent.Fail2ban(resource.getString(BAG_ATTRIBUTES_FAIL2BAN, PARAM_JAIL),
						resource.hasParameter(BAG_ATTRIBUTES_FAIL2BAN, PARAM_FAILURES) ?
								resource.getInteger(BAG_ATTRIBUTES_FAIL2BAN, PARAM_FAILURES) : null)),
				new SecurityEvent.Geo(
				GeoStatus.valueOf(resource.getString(BAG_GEO, PARAM_STATUS)),
				resource.hasParameter(BAG_GEO, PARAM_COUNTRY_CODE) ? resource.getString(BAG_GEO, PARAM_COUNTRY_CODE) : null,
				resource.hasParameter(BAG_GEO, PARAM_COUNTRY_NAME) ? resource.getString(BAG_GEO, PARAM_COUNTRY_NAME) : null,
				resource.hasParameter(BAG_GEO, PARAM_REGION) ? resource.getString(BAG_GEO, PARAM_REGION) : null,
				resource.hasParameter(BAG_GEO, PARAM_CITY) ? resource.getString(BAG_GEO, PARAM_CITY) : null,
				resource.hasParameter(BAG_GEO, PARAM_LATITUDE) ? resource.getDouble(BAG_GEO, PARAM_LATITUDE) : null,
				resource.hasParameter(BAG_GEO, PARAM_LONGITUDE) ? resource.getDouble(BAG_GEO, PARAM_LONGITUDE) : null,
				resource.hasParameter(BAG_GEO, PARAM_ACCURACY_RADIUS_KM) ? resource.getDouble(BAG_GEO, PARAM_ACCURACY_RADIUS_KM) : null,
				resource.hasParameter(BAG_GEO, PARAM_PROVIDER) ? resource.getString(BAG_GEO, PARAM_PROVIDER) : null,
				resource.hasParameter(BAG_GEO, PARAM_DATABASE_EDITION) ? resource.getString(BAG_GEO, PARAM_DATABASE_EDITION) : null,
				resource.hasParameter(BAG_GEO, PARAM_DATABASE_BUILD_AT) ? resource.getString(BAG_GEO, PARAM_DATABASE_BUILD_AT) : null,
				resource.hasParameter(BAG_GEO, PARAM_LOOKED_UP_AT) ? resource.getString(BAG_GEO, PARAM_LOOKED_UP_AT) : null));
	}

	public Resource toResource(IngestReceipt value) {
		Resource resource = create(TYPE_INGEST_RECEIPT, value.id());
		resource.setString(BAG_PARAMETERS, PARAM_INSTANCE_ID, value.instanceId());
		resource.setString(BAG_PARAMETERS, PARAM_SOURCE_EVENT_ID, value.sourceEventId().toString());
		resource.setString(BAG_PARAMETERS, PARAM_PAYLOAD_DIGEST, value.payloadDigest());
		resource.setString(BAG_PARAMETERS, PARAM_EVENT_ID, value.eventId().toString());
		resource.setLong(BAG_PARAMETERS, PARAM_SEQUENCE, Long.parseLong(value.sequence()));
		resource.setString(BAG_PARAMETERS, PARAM_EXPIRES_AT, value.expiresAt());
		return resource;
	}

	public IngestReceipt toReceipt(Resource resource) {
		requireType(resource, TYPE_INGEST_RECEIPT);
		return new IngestReceipt(
				resource.getId(),
				resource.getString(BAG_PARAMETERS, PARAM_INSTANCE_ID),
				UUID.fromString(resource.getString(BAG_PARAMETERS, PARAM_SOURCE_EVENT_ID)),
				resource.getString(BAG_PARAMETERS, PARAM_PAYLOAD_DIGEST),
				UUID.fromString(resource.getString(BAG_PARAMETERS, PARAM_EVENT_ID)),
				Long.toString(resource.getLong(BAG_PARAMETERS, PARAM_SEQUENCE)),
				resource.getString(BAG_PARAMETERS, PARAM_EXPIRES_AT));
	}

	public Resource toResource(EventStreamState value) {
		Resource resource = create(TYPE_EVENT_STREAM_STATE, STREAM_ID);
		resource.setLong(BAG_PARAMETERS, PARAM_LAST_COMMITTED_SEQUENCE, Long.parseLong(value.lastCommittedSequence()));
		resource.setLong(BAG_PARAMETERS, PARAM_MIN_AFTER, Long.parseLong(value.minAfter()));
		return resource;
	}

	public EventStreamState toStreamState(Resource resource) {
		requireType(resource, TYPE_EVENT_STREAM_STATE);
		return new EventStreamState(
				Long.toString(resource.getLong(BAG_PARAMETERS, PARAM_LAST_COMMITTED_SEQUENCE)),
				Long.toString(resource.getLong(BAG_PARAMETERS, PARAM_MIN_AFTER)));
	}
}
