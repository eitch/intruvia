// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.model;

public final class ModelConstants {
	private ModelConstants() {}
	public static final String STREAM_ID = "event-stream";
	public static final String TYPE_SECURITY_EVENT = "SecurityEvent";
	public static final String TYPE_INGEST_RECEIPT = "IngestReceipt";
	public static final String TYPE_EVENT_STREAM_STATE = "EventStreamState";
	public static final String BAG_ENVELOPE = "envelope";
	public static final String BAG_SOURCE = "source";
	public static final String BAG_SUBJECT = "subject";
	public static final String BAG_OBSERVER = "observer";
	public static final String BAG_ATTRIBUTES_FAIL2BAN = "attributes.fail2ban";
	public static final String BAG_GEO = "geo";
	public static final String BAG_PARAMETERS = "parameters";
	public static final String PARAM_SCHEMA_VERSION = "schemaVersion";
	public static final String PARAM_SEQUENCE = "sequence";
	public static final String PARAM_CATEGORY = "category";
	public static final String PARAM_ACTION = "action";
	public static final String PARAM_OCCURRED_AT = "occurredAt";
	public static final String PARAM_RECEIVED_AT = "receivedAt";
	public static final String PARAM_TYPE = "type";
	public static final String PARAM_INSTANCE_ID = "instanceId";
	public static final String PARAM_EVENT_ID = "eventId";
	public static final String PARAM_IP = "ip";
	public static final String PARAM_ID = "id";
	public static final String PARAM_JAIL = "jail";
	public static final String PARAM_FAILURES = "failures";
	public static final String PARAM_STATUS = "status";
	public static final String PARAM_COUNTRY_CODE = "countryCode";
	public static final String PARAM_COUNTRY_NAME = "countryName";
	public static final String PARAM_REGION = "region";
	public static final String PARAM_CITY = "city";
	public static final String PARAM_LATITUDE = "latitude";
	public static final String PARAM_LONGITUDE = "longitude";
	public static final String PARAM_ACCURACY_RADIUS_KM = "accuracyRadiusKm";
	public static final String PARAM_PROVIDER = "provider";
	public static final String PARAM_DATABASE_EDITION = "databaseEdition";
	public static final String PARAM_DATABASE_BUILD_AT = "databaseBuildAt";
	public static final String PARAM_LOOKED_UP_AT = "lookedUpAt";
	public static final String PARAM_SOURCE_EVENT_ID = "sourceEventId";
	public static final String PARAM_PAYLOAD_DIGEST = "payloadDigest";
	public static final String PARAM_EXPIRES_AT = "expiresAt";
	public static final String PARAM_LAST_COMMITTED_SEQUENCE = "lastCommittedSequence";
	public static final String PARAM_MIN_AFTER = "minAfter";
}
