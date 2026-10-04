// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.geo;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.maxmind.db.Reader.FileMode;
import com.maxmind.db.DeserializationException;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CityResponse;
import li.intruvia.core.ingest.IpLiteral;
import li.intruvia.core.model.GeoStatus;
import li.intruvia.core.model.SecurityEvent.Geo;
import li.strolch.agent.api.ComponentContainer;
import li.strolch.agent.api.StrolchComponent;
import li.strolch.runtime.configuration.ComponentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns one local reader per start/stop lifecycle. No network calls or ingestion transactions. */
public class GeoIpComponent extends StrolchComponent {
	private static final Logger LOGGER = LoggerFactory.getLogger(GeoIpComponent.class);
	private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
	private final AtomicBoolean lookupFailureReported = new AtomicBoolean();
	private final Clock clock;
	private Path databasePath;
	private DatabaseReader reader;
	private String edition;
	private String buildAt;

	public GeoIpComponent(ComponentContainer container, String componentName) {
		this(container, componentName, Clock.systemUTC());
	}

	GeoIpComponent(ComponentContainer container, String componentName, Clock clock) {
		super(container, componentName);
		this.clock = clock;
	}

	@Override
	public void initialize(ComponentConfiguration configuration) throws Exception {
		String configured = configuration.getString("databasePath", "");
		if (!configured.isBlank()) {
			this.databasePath = Path.of(configured);
			if (!this.databasePath.isAbsolute())
				throw new IllegalArgumentException("GeoIP databasePath must be absolute");
		}
		super.initialize(configuration);
	}

	@Override
	public void start() throws Exception {
		this.lifecycle.writeLock().lock();
		try {
			if (this.reader != null)
				throw new IllegalStateException("GeoIP reader already started");
			this.lookupFailureReported.set(false);
			if (this.databasePath != null) {
				try {
					// Snapshot in memory avoids live-file mutation/mapping hazards. Updates require restart until task 008.
					this.reader = new DatabaseReader.Builder(this.databasePath.toFile()).fileMode(FileMode.MEMORY).build();
					this.edition = this.reader.metadata().databaseType();
					if (!this.edition.equals("GeoLite2-City") && !this.edition.equals("GeoIP2-City"))
						throw new IllegalArgumentException("A City database is required");
					this.buildAt = this.reader.metadata().buildTime().toString();
				} catch (IOException | IllegalArgumentException | DateTimeException | ClassCastException | DeserializationException e) {
					closeReader();
					LOGGER.warn("GeoIP database could not be loaded; enrichment is UNAVAILABLE");
				}
			}
			super.start();
		} finally {
			this.lifecycle.writeLock().unlock();
		}
	}

	/** Input must be a validated literal. Invalid caller input is a validation error, not a database failure. */
	public Geo enrich(String literal) {
		InetAddress address = InetAddress.ofLiteral(IpLiteral.normalize(literal));
		String lookedUpAt = this.clock.instant().toString();
		if (!PublicAddressPolicy.isPublic(address))
			return empty(GeoStatus.NON_PUBLIC, null, null, null, lookedUpAt);
		this.lifecycle.readLock().lock();
		try {
			if (this.reader == null)
				return empty(GeoStatus.UNAVAILABLE, null, null, null, lookedUpAt);
			try {
				var response = this.reader.tryCity(address);
				return response.isPresent() ? map(response.get(), lookedUpAt) :
						empty(GeoStatus.NOT_FOUND, "maxmind", this.edition, this.buildAt, lookedUpAt);
			} catch (IOException | GeoIp2Exception | IllegalArgumentException | ClassCastException | DeserializationException e) {
				if (this.lookupFailureReported.compareAndSet(false, true))
					LOGGER.warn("GeoIP lookup failed; enrichment returns ERROR (further warnings suppressed until restart)");
				return empty(GeoStatus.ERROR, "maxmind", this.edition, this.buildAt, lookedUpAt);
			}
		} finally {
			this.lifecycle.readLock().unlock();
		}
	}

	private Geo map(CityResponse response, String lookedUpAt) {
		var location = response.location();
		Double latitude = location.latitude();
		Double longitude = location.longitude();
		// A partial pair cannot identify a point. Preserve location names, but leave both coordinates absent.
		if (latitude == null || longitude == null) {
			latitude = null;
			longitude = null;
		}
		Integer radius = location.accuracyRadius();
		return new Geo(GeoStatus.FOUND, response.country().isoCode(), response.country().name(),
				response.mostSpecificSubdivision().name(), response.city().name(), latitude, longitude,
				radius == null ? null : radius.doubleValue(), "maxmind", this.edition, this.buildAt, lookedUpAt);
	}

	private static Geo empty(GeoStatus status, String provider, String edition, String buildAt, String lookedUpAt) {
		return new Geo(status, null, null, null, null, null, null, null, provider, edition, buildAt, lookedUpAt);
	}

	@Override
	public void stop() throws Exception {
		this.lifecycle.writeLock().lock();
		try {
			closeReader();
			super.stop();
		} finally {
			this.lifecycle.writeLock().unlock();
		}
	}

	@Override
	public void destroy() throws Exception {
		this.lifecycle.writeLock().lock();
		try {
			closeReader();
			super.destroy();
		} finally {
			this.lifecycle.writeLock().unlock();
		}
	}

	private void closeReader() {
		DatabaseReader oldReader = this.reader;
		this.reader = null;
		this.edition = null;
		this.buildAt = null;
		if (oldReader != null) {
			try {
				oldReader.close();
			} catch (IOException e) {
				LOGGER.warn("GeoIP reader reported a close failure");
			}
		}
	}
}
