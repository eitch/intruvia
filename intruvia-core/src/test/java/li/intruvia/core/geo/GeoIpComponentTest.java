// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.geo;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import com.maxmind.geoip2.DatabaseReader;
import li.intruvia.core.model.GeoStatus;
import li.intruvia.core.model.SecurityEvent.Geo;
import li.strolch.agent.api.ComponentState;
import li.strolch.runtime.configuration.ComponentConfiguration;
import li.strolch.runtime.configuration.RuntimeConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class GeoIpComponentTest {
	private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
	@Rule
	public TemporaryFolder folder = new TemporaryFolder(Path.of("target").toAbsolutePath().toFile());

	@Test
	public void mapsIpv4Ipv6AndProvenanceUsingRealReader() throws Exception {
		Path path = database(Map.of("8.8.8.8", fullRecord(47.1, 9.5), "2606:4700:4700::1111", fullRecord(0.0, 0.0)));
		GeoIpComponent component = start(path);
		try {
			Geo ipv4 = component.enrich("8.8.8.8");
			assertEquals(GeoStatus.FOUND, ipv4.status());
			assertEquals("CH", ipv4.countryCode());
			assertEquals("Synthetic country", ipv4.countryName());
			assertEquals("Synthetic region", ipv4.region());
			assertEquals("Synthetic city", ipv4.city());
			assertEquals(Double.valueOf(47.1), ipv4.latitude());
			assertEquals(Double.valueOf(9.5), ipv4.longitude());
			assertEquals(Double.valueOf(12), ipv4.accuracyRadiusKm());
			assertEquals("maxmind", ipv4.provider());
			assertEquals("GeoIP2-City", ipv4.databaseEdition());
			assertEquals(Instant.ofEpochSecond(SyntheticCityDatabase.BUILD_EPOCH).toString(), ipv4.databaseBuildAt());
			assertEquals(NOW.toString(), ipv4.lookedUpAt());
			assertEquals(ipv4, component.enrich("::ffff:8.8.8.8"));
			Geo ipv6 = component.enrich("2606:4700:4700::1111");
			assertEquals(GeoStatus.FOUND, ipv6.status());
			assertEquals(Double.valueOf(0), ipv6.latitude());
			assertEquals(Double.valueOf(0), ipv6.longitude());
		} finally {
			stop(component);
		}
	}

	@Test
	public void preservesPartialRecordsAndNeverInventsCoordinates() throws Exception {
		Path path = database(Map.of("8.8.8.8", Map.of("country", Map.of("iso_code", "CH")),
				"9.9.9.9", Map.of("location", Map.of("latitude", 0.0)), "1.1.1.1", Map.of()));
		GeoIpComponent component = start(path);
		try {
			for (String ip : new String[]{"8.8.8.8", "9.9.9.9", "1.1.1.1"}) {
				Geo geo = component.enrich(ip);
				assertEquals(GeoStatus.FOUND, geo.status());
				assertNull(geo.latitude());
				assertNull(geo.longitude());
				assertNull(geo.city());
				assertNull(geo.accuracyRadiusKm());
			}
			assertEquals("CH", component.enrich("8.8.8.8").countryCode());
			Geo missing = component.enrich("8.8.4.4");
			assertEquals(GeoStatus.NOT_FOUND, missing.status());
			assertNull(missing.latitude());
			assertEquals("maxmind", missing.provider());
			assertNotNull(missing.databaseBuildAt());
		} finally {
			stop(component);
		}
	}

	@Test
	public void startupWithoutUsableDatabaseIsDegradedAndNonPublicStillWins() throws Exception {
		Path corrupt = this.folder.newFile("corrupt.mmdb").toPath();
		Files.writeString(corrupt, "not a database");
		Path wrongEdition = SyntheticCityDatabase.write(this.folder.newFile("country.mmdb").toPath(), Map.of(), "GeoIP2-Country");
		for (Path path : new Path[]{null, this.folder.getRoot().toPath().resolve("missing.mmdb"), corrupt, wrongEdition}) {
			GeoIpComponent component = start(path);
			try {
				assertEquals(ComponentState.STARTED, component.getState());
				Geo geo = component.enrich("8.8.8.8");
				assertEquals(GeoStatus.UNAVAILABLE, geo.status());
				assertNull(geo.provider());
				assertNull(geo.databaseEdition());
				assertNull(geo.latitude());
				assertEquals(GeoStatus.NON_PUBLIC, component.enrich("192.0.2.1").status());
			} finally {
				stop(component);
			}
		}
	}

	@Test
	public void corruptRecordsAndInvalidGeographyReturnErrorWithoutAborting() throws Exception {
		Path path = database(Map.of("8.8.8.8", "wrong record type", "9.9.9.9", fullRecord(91.0, 0.0),
				"1.1.1.1", fullRecord(Double.NaN, 1.0), "8.8.4.4", fullRecord(1.0, Double.POSITIVE_INFINITY),
				"1.0.0.1", Map.of("location", Map.of("accuracy_radius", -1))));
		GeoIpComponent component = start(path);
		try {
			for (String ip : new String[]{"8.8.8.8", "9.9.9.9", "1.1.1.1", "8.8.4.4", "1.0.0.1"}) {
				Geo result = component.enrich(ip);
				assertEquals(ip, GeoStatus.ERROR, result.status());
				assertNull(result.latitude());
				assertEquals("maxmind", result.provider());
				assertNotNull(result.databaseBuildAt());
			}
			assertEquals(GeoStatus.NOT_FOUND, component.enrich("4.4.4.4").status());
			assertEquals(ComponentState.STARTED, component.getState());
		} finally {
			stop(component);
		}
	}

	@Test
	public void nonPublicAddressesBypassEvenMatchingDatabaseRecords() throws Exception {
		String[] values = {"127.0.0.1", "10.0.0.1", "100.64.0.1", "169.254.1.1", "172.16.0.1", "192.168.1.1",
				"192.0.2.1", "198.51.100.1", "203.0.113.42", "224.0.0.1", "255.255.255.255", "0.0.0.0",
				"::", "::1", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1", "3fff::1", "64:ff9b::808:808",
				"2002:808:808::1", "2001::1", "5f00::1", "100::1", "::ffff:192.168.1.1"};
		Map<String, Object> records = new java.util.LinkedHashMap<>();
		for (String ip : values)
			records.put(ip, fullRecord(1.0, 1.0));
		GeoIpComponent component = start(database(records));
		try {
			for (String ip : values) {
				Geo geo = component.enrich(ip);
				assertEquals(ip, GeoStatus.NON_PUBLIC, geo.status());
				assertNull(geo.latitude());
				assertNull(geo.provider());
				assertNull(geo.databaseBuildAt());
			}
		} finally {
			stop(component);
		}
	}

	@Test
	public void readerIsReusedClosedAndReopenedOnlyOnLifecycleRestart() throws Exception {
		Path path = database(Map.of("8.8.8.8", fullRecord(0.0, 9.5)));
		GeoIpComponent component = start(path);
		DatabaseReader original = reader(component);
		try {
			Files.delete(path);
			for (int i = 0; i < 20; i++) {
				assertEquals(GeoStatus.FOUND, component.enrich("8.8.8.8").status());
				assertSame(original, reader(component));
			}
			component.stop();
			assertThrows(IOException.class, () -> original.tryCity(InetAddress.ofLiteral("8.8.8.8")));
			assertEquals(GeoStatus.UNAVAILABLE, component.enrich("8.8.8.8").status());
			SyntheticCityDatabase.write(path, Map.of("8.8.8.8", fullRecord(0.0, 0.0)), "GeoLite2-City");
			component.start();
			assertNotSame(original, reader(component));
			assertEquals("GeoLite2-City", component.enrich("8.8.8.8").databaseEdition());
			assertEquals(Double.valueOf(0), component.enrich("8.8.8.8").longitude());
		} finally {
			DatabaseReader current = reader(component);
			stop(component);
			assertThrows(IOException.class, () -> current.tryCity(InetAddress.ofLiteral("8.8.8.8")));
		}
	}

	@Test
	public void rejectsRelativeConfigurationAndInvalidLiterals() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> start(Path.of("relative.mmdb")));
		GeoIpComponent component = start(null);
		try {
			for (String ip : new String[]{"example.org", "8.8.8.8/32", "fe80::1%eth0"})
				assertThrows(IllegalArgumentException.class, () -> component.enrich(ip));
		} finally {
			stop(component);
		}
	}

	private Path database(Map<String, Object> records) throws IOException {
		return SyntheticCityDatabase.write(this.folder.newFile().toPath(), records);
	}

	private GeoIpComponent start(Path path) throws Exception {
		var directory = this.folder.getRoot();
		var runtime = new RuntimeConfiguration("Intruvia", "test", Map.of(), directory, directory, directory, Set.of());
		var configuration = new ComponentConfiguration(runtime, "GeoIp", path == null ? Map.of() : Map.of("databasePath", path.toString()),
				GeoIpComponent.class.getName(), GeoIpComponent.class.getName(), Set.of());
		var component = new GeoIpComponent(null, "GeoIp", Clock.fixed(NOW, ZoneOffset.UTC));
		component.setup(configuration);
		component.initialize(configuration);
		component.start();
		return component;
	}

	private static void stop(GeoIpComponent component) throws Exception {
		component.stop();
		component.destroy();
		assertEquals(ComponentState.DESTROYED, component.getState());
	}

	private static DatabaseReader reader(GeoIpComponent component) throws Exception {
		var field = GeoIpComponent.class.getDeclaredField("reader");
		field.setAccessible(true);
		return (DatabaseReader) field.get(component);
	}

	private static Map<String, Object> fullRecord(double latitude, double longitude) {
		return Map.of("country", Map.of("iso_code", "CH", "names", Map.of("en", "Synthetic country")),
				"city", Map.of("names", Map.of("en", "Synthetic city")),
				"subdivisions", java.util.List.of(Map.of("names", Map.of("en", "Synthetic region"))),
				"location", Map.of("latitude", latitude, "longitude", longitude, "accuracy_radius", 12));
	}
}
