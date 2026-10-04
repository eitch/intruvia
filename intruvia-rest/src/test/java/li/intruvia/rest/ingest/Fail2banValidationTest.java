// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import li.intruvia.core.ingest.EventAgePolicy;
import li.intruvia.core.ingest.Fail2banEvent;
import li.intruvia.core.ingest.InvalidEventException;
import li.intruvia.core.ingest.IpLiteral;
import li.intruvia.core.model.GeoStatus;
import li.intruvia.core.model.SecurityEvent;
import org.junit.Test;

import static org.junit.Assert.*;

public class Fail2banValidationTest {
	private static final String JSON = """
			{"schemaVersion":1,"eventId":"55c42ae2-865b-47b8-bc4b-c166c0344c5c",
			"occurredAt":"2026-10-04T12:00:00Z","action":"ban","ip":"203.0.113.42","jail":"sshd","failures":7}
			""";
	private final Fail2banRequestParser parser = new Fail2banRequestParser();

	private Fail2banEvent parse(String json) throws IOException {
		return this.parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
	}

	private String field(String name, String jsonValue) {
		JsonObject object = JsonParser.parseString(JSON).getAsJsonObject();
		object.add(name, JsonParser.parseString(jsonValue));
		return object.toString();
	}

	private void reject(String json) {
		InvalidEventException failure = assertThrows(InvalidEventException.class, () -> parse(json));
		assertFalse(failure.isOversized());
		assertEquals("Invalid event payload", failure.getMessage());
		assertNull(failure.getCause());
	}

	@Test
	public void strictShapeAndTypes() {
		for (String invalid : new String[]{"", "null", "[]", "1", "{}", JSON + "{}", JSON + "true",
				JSON.replace("\"schemaVersion\":1", "schemaVersion:1"), JSON.replace("\"ban\"", "'ban'"),
				JSON.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
				JSON.replace("\"failures\":7", "\"failures\":null,\"failures\":7"),
				JSON.replace("\"schemaVersion\":1", "/*comment*/\"schemaVersion\":1"),
				JSON.replace("7}", "7,}"), JSON.replace("7}", "NaN}"), JSON.replace("7}", "07}")})
			reject(invalid);
		for (String name : new String[]{"schemaVersion", "eventId", "occurredAt", "action", "ip", "jail"}) {
			JsonObject object = JsonParser.parseString(JSON).getAsJsonObject();
			object.remove(name);
			reject(object.toString());
			reject(field(name, "null"));
			for (String type : new String[]{"true", "[]", "{}"})
				reject(field(name, type));
		}
		for (String unknown : new String[]{"instanceId", "observer", "source", "geo", "password", "unknown"})
			reject(field(unknown, "\"untrusted-secret\""));
		reject(field("schemaVersion", "\"1\""));
		reject(field("schemaVersion", "2"));
		reject(field("schemaVersion", "1.1"));
		reject(field("action", "\"BAN\""));
		reject(field("action", "\"unban\""));
		reject(field("jail", "42"));
	}

	@Test
	public void boundedBodyAndUtf8() throws IOException {
		byte[] valid = JSON.getBytes(StandardCharsets.UTF_8);
		byte[] exact = (JSON + " ".repeat(16384 - valid.length)).getBytes(StandardCharsets.UTF_8);
		assertEquals(parse(JSON), this.parser.parse(new ByteArrayInputStream(exact)));
		var overflow = new ByteArrayInputStream((new String(exact, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8));
		assertTrue(assertThrows(InvalidEventException.class, () -> this.parser.parse(overflow)).isOversized());
		var custom = new Fail2banRequestParser(valid.length);
		assertEquals(parse(JSON), custom.parse(new ByteArrayInputStream(valid)));
		assertTrue(assertThrows(InvalidEventException.class,
				() -> custom.parse(new ByteArrayInputStream((JSON + "é").getBytes(StandardCharsets.UTF_8)))).isOversized());
		byte[] malformed = valid.clone();
		malformed[10] = (byte) 0xff;
		assertThrows(InvalidEventException.class, () -> this.parser.parse(new ByteArrayInputStream(malformed)));
		assertThrows(IllegalArgumentException.class, () -> new Fail2banRequestParser(0));
		assertThrows(IllegalArgumentException.class, () -> new Fail2banRequestParser(Integer.MAX_VALUE));
		InputStream endless = new InputStream() {
			private int count;
			@Override public int read() {
				assertTrue("Read exceeds bound", ++this.count <= 16385);
				return ' ';
			}
		};
		assertTrue(assertThrows(InvalidEventException.class, () -> this.parser.parse(endless)).isOversized());
		InputStream broken = new InputStream() {
			@Override public int read() throws IOException { throw new IOException("transport failure"); }
		};
		assertThrows(IOException.class, () -> this.parser.parse(broken));
	}

	@Test
	public void uuidJailAndFailuresBounds() throws IOException {
		for (String uuid : new String[]{"1-1-1-1-1", "", "55c42ae2-865b-47b8-bc4b-c166c0344c5g", " 55c42ae2-865b-47b8-bc4b-c166c0344c5c"})
			reject(field("eventId", "\"" + uuid + "\""));
		assertEquals(parse(JSON).eventId(), parse(field("eventId", "\"55C42AE2-865B-47B8-BC4B-C166C0344C5C\"")).eventId());
		for (String jail : new String[]{"\"\"", "\"a\\nb\"", "\"a\\u0000b\"", "\"a\\u007fb\"", "\"a\\u0085b\"", "\"\\ud800\""})
			reject(JSON.replace("\"sshd\"", jail));
		assertEquals(128, parse(field("jail", "\"" + "x".repeat(128) + "\"")).jail().length());
		reject(field("jail", "\"" + "x".repeat(129) + "\""));
		String unicode = "😀".repeat(128);
		assertEquals(unicode, parse(field("jail", "\"" + unicode + "\"")).jail());
		reject(field("jail", "\"" + unicode + "😀\""));
		assertEquals("<img onerror=alert(1)>", parse(field("jail", "\"<img onerror=alert(1)>\"")).jail());
		for (String failures : new String[]{"-1", "2147483648", "0.1", "\"7\"", "true", "[]", "{}", "1e999999999"})
			reject(field("failures", failures));
		assertEquals(Integer.valueOf(0), parse(field("failures", "0")).failures());
		assertEquals(Integer.valueOf(Integer.MAX_VALUE), parse(field("failures", "2147483647")).failures());
		assertNull(parse(field("failures", "null")).failures());
	}

	@Test
	public void canonicalLiteralAddressesWithoutNameResolution() {
		String[][] cases = {{"203.0.113.42", "203.0.113.42"}, {"0.0.0.0", "0.0.0.0"}, {"255.255.255.255", "255.255.255.255"},
				{"2001:0DB8:0000:0000:0000:0000:0000:0042", "2001:db8::42"}, {"::FFFF:203.0.113.42", "203.0.113.42"},
				{"0:0:0:0:0:ffff:cb00:712a", "203.0.113.42"}, {"::", "::"}, {"::1", "::1"}, {"2001:db8::", "2001:db8::"},
				{"1:0:0:2:0:0:3:4", "1::2:0:0:3:4"}, {"1:2:3:4:5:6:0:8", "1:2:3:4:5:6:0:8"},
				{"::192.0.2.1", "::c000:201"}};
		for (String[] pair : cases) {
			assertEquals(pair[0], pair[1], IpLiteral.normalize(pair[0]));
			assertEquals(pair[1], IpLiteral.normalize(pair[1]));
		}
		for (String invalid : new String[]{"localhost", "example.invalid", "127.1", "2130706433", "0x7f000001", "127.00.0.1",
				"256.0.0.1", "1.2.3.-1", "1.2.3.4.", "1.2.3.4/32", "fe80::1%eth0", "[::1]", "::1/128", " ::1", "::1 ",
				"1::2::3", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "::ffff:192.000.2.1", "", "g::1"})
			assertThrows(invalid, InvalidEventException.class, () -> IpLiteral.normalize(invalid));
	}

	@Test
	public void timestampShapeAndAgeAreSeparate() throws IOException {
		Instant now = Instant.parse("2026-10-04T12:00:00Z");
		for (String time : new String[]{"2026-10-04T14:00:00+02:00", "2026-10-04t12:00:00z", "2026-10-04T07:00:00-05:00"})
			assertEquals(now, parse(field("occurredAt", "\"" + time + "\"")).occurredAt());
		for (String time : new String[]{"2026-10-04T12:00:00", "2026-10-04", "2026-10-04 12:00:00Z", "2026-02-30T12:00:00Z",
				"2026-10-04T24:00:00Z", "2026-10-04T12:00:60Z", "2026-10-04T12:00:00+19:00", "2026-10-04T12:00:00Z[UTC]",
				"2026-10-04T12:00:00.1234567891Z"})
			reject(field("occurredAt", "\"" + time + "\""));
		EventAgePolicy.DEFAULT.validate(now.minus(Duration.ofDays(7)), now);
		EventAgePolicy.DEFAULT.validate(now.plusSeconds(300), now);
		assertThrows(InvalidEventException.class, () -> EventAgePolicy.DEFAULT.validate(now.minus(Duration.ofDays(7)).minusNanos(1), now));
		assertThrows(InvalidEventException.class, () -> EventAgePolicy.DEFAULT.validate(now.plusSeconds(300).plusNanos(1), now));
		Fail2banEvent old = parse(field("occurredAt", "\"2026-09-01T12:00:00Z\""));
		assertEquals(64, old.payloadDigest().length());
		assertThrows(InvalidEventException.class, () -> EventAgePolicy.DEFAULT.validate(old.occurredAt(), now));
		var policy = new EventAgePolicy(Duration.ofHours(1), Duration.ZERO);
		policy.validate(now.minusSeconds(3600), now);
		assertThrows(InvalidEventException.class, () -> policy.validate(now.plusNanos(1), now));
		assertThrows(IllegalArgumentException.class, () -> new EventAgePolicy(Duration.ofSeconds(-1), Duration.ZERO));
	}

	@Test
	public void semanticDigestsAndTrustedEnvelope() throws IOException {
		Fail2banEvent original = parse(JSON);
		String equivalent = """
				{ "jail":"ss\\u0068d", "ip":"::ffff:cb00:712a", "action":"ban", "failures":7.0e0,
				"occurredAt":"2026-10-04T14:00:00.000+02:00","eventId":"55C42AE2-865B-47B8-BC4B-C166C0344C5C","schemaVersion":1.0 }
				""";
		assertEquals(original, parse(equivalent));
		assertEquals(original.payloadDigest(), parse(equivalent).payloadDigest());
		for (String[] change : new String[][]{{"eventId", "\"55c42ae2-865b-47b8-bc4b-c166c0344c5d\""},
				{"occurredAt", "\"2026-10-04T12:00:00.000000001Z\""}, {"ip", "\"203.0.113.43\""}, {"jail", "\"sshd \""},
				{"failures", "8"}, {"failures", "null"}})
			assertNotEquals(original.payloadDigest(), parse(field(change[0], change[1])).payloadDigest());
		JsonObject missing = JsonParser.parseString(JSON).getAsJsonObject();
		missing.remove("failures");
		assertEquals(parse(missing.toString()).payloadDigest(), parse(field("failures", "null")).payloadDigest());
		assertNotEquals(parse(missing.toString()).payloadDigest(), parse(field("failures", "0")).payloadDigest());
		var geo = new SecurityEvent.Geo(GeoStatus.UNAVAILABLE, null, null, null, null, null, null, null, null, null, null, null);
		UUID id = UUID.randomUUID();
		SecurityEvent event = original.toEvent("trusted-server", id, 42, Instant.parse("2026-10-04T12:00:01Z"), geo);
		assertEquals("trusted-server", event.source().instanceId());
		assertEquals(event.source().instanceId(), event.observer().id());
		assertEquals(original.eventId(), event.source().eventId());
		assertEquals("fail2ban", event.source().type());
		assertEquals("network.security", event.category());
		assertEquals("ban", event.action());
		assertEquals(id, event.id());
		assertEquals("42", event.sequence());
		assertEquals(original.ip(), event.subject().ip());
		assertEquals(original.jail(), event.attributes().fail2ban().jail());
		assertEquals(original.failures(), event.attributes().fail2ban().failures());
		assertEquals(geo, event.geo());
		assertEquals("2026-10-04T12:00:01Z", event.receivedAt());
		parse(field("jail", "\"" + "😀".repeat(128) + "\"")).toEvent("trusted-server", id, 43, Instant.now(), geo);
		assertEquals(original.payloadDigest(), parse(JSON).payloadDigest());
	}
}
