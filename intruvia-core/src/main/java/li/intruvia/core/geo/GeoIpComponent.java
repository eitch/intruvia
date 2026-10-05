// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.geo;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.maxmind.db.DeserializationException;
import com.maxmind.db.InvalidNetworkException;
import com.maxmind.db.NetworksIterationException;
import com.maxmind.db.Reader;
import com.maxmind.db.Reader.FileMode;
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

/** Owns a reusable local reader and staged replacements within the Strolch lifecycle. No network calls or ingestion transactions. */
public class GeoIpComponent extends StrolchComponent {
	private static final Logger LOGGER = LoggerFactory.getLogger(GeoIpComponent.class);
	private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
	private final AtomicBoolean lookupFailureReported = new AtomicBoolean();
	private final Clock clock;
	private Path databasePath;
	private DatabaseReader reader;
	private String edition;
	private String buildAt;
	private Instant loadedAt;
	private Duration staleAfter = Duration.ofDays(14);
	private long updateIntervalSeconds = 60;
	private boolean running;
	private boolean updateFailed;
	private ScheduledExecutorService updater;

	/** Safe immutable snapshot for future protected diagnostics; no filesystem paths or exception details. */
	public record Diagnostics(boolean available, String databaseEdition, Instant databaseBuildAt,
			Instant lastSuccessfulLoadAt, Duration databaseAge, boolean stale, boolean updateFailed) {
	}

	public Diagnostics diagnostics() {
		this.lifecycle.readLock().lock();
		try {
			Instant built = this.buildAt == null ? null : Instant.parse(this.buildAt);
			Duration age = built == null ? null : Duration.between(built, this.clock.instant());
			return new Diagnostics(this.reader != null, this.edition, built, this.loadedAt, age,
					age != null && age.compareTo(this.staleAfter) > 0, this.updateFailed);
		} finally {
			this.lifecycle.readLock().unlock();
		}
	}

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
		this.staleAfter = Duration.ofSeconds(Long.parseLong(configuration.getString("staleAfterSeconds", "1209600")));
		this.updateIntervalSeconds = Long.parseLong(configuration.getString("updateIntervalSeconds", "60"));
		if (this.staleAfter.isNegative() || this.staleAfter.isZero() || this.updateIntervalSeconds < 1)
			throw new IllegalArgumentException("GeoIP age and update intervals must be positive");
		super.initialize(configuration);
	}

	@Override
	public synchronized void start() throws Exception {
		this.lifecycle.writeLock().lock();
		try {
			if (this.running)
				throw new IllegalStateException("GeoIP reader already started");
			this.lookupFailureReported.set(false);
			if (this.databasePath != null) {
				try {
					// The live reader never depends on subsequent changes to the on-disk file.
					this.reader = new DatabaseReader.Builder(this.databasePath.toFile()).fileMode(FileMode.MEMORY).build();
					this.edition = this.reader.metadata().databaseType();
					if (!this.edition.equals("GeoLite2-City") && !this.edition.equals("GeoIP2-City"))
						throw new IllegalArgumentException("A City database is required");
					this.buildAt = this.reader.metadata().buildTime().toString();
					this.loadedAt = this.clock.instant();
				} catch (IOException | IllegalArgumentException | DateTimeException | ClassCastException | DeserializationException e) {
					closeReader();
					LOGGER.warn("GeoIP database could not be loaded; enrichment is UNAVAILABLE");
				}
			}
			super.start();
			this.running = true;
			if (this.databasePath != null) {
				this.updater = Executors.newSingleThreadScheduledExecutor(task -> {
					Thread thread = new Thread(task, "intruvia-geoip-update");
					thread.setDaemon(true);
					return thread;
				});
				this.updater.scheduleWithFixedDelay(this::replaceStagedDatabase, this.updateIntervalSeconds,
						this.updateIntervalSeconds, TimeUnit.SECONDS);
			}
		} finally {
			this.lifecycle.writeLock().unlock();
		}
	}

	/** Consumes databasePath + ".staged". Publish that file by rename, never by writing it in place. */
	public synchronized boolean replaceStagedDatabase() {
		Path candidatePath = null;
		DatabaseReader candidate = null;
		this.lifecycle.readLock().lock();
		try {
			if (!this.running || this.databasePath == null || Thread.currentThread().isInterrupted())
				return false;
		} finally {
			this.lifecycle.readLock().unlock();
		}
		Path staged = this.databasePath.resolveSibling(this.databasePath.getFileName() + ".staged");
		if (!Files.exists(staged, LinkOption.NOFOLLOW_LINKS))
			return false;
		try {
			if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Staged database must be a regular file");
			// Claim an immutable sibling before validation, so a later external publication cannot change these bytes.
			candidatePath = Files.createTempFile(this.databasePath.getParent(), ".intruvia-geoip-", ".candidate");
			Files.move(staged, candidatePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			if (!Files.isRegularFile(candidatePath, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Staged database must be a regular file");
			candidate = new DatabaseReader.Builder(candidatePath.toFile()).fileMode(FileMode.MEMORY).build();
			String candidateEdition = candidate.metadata().databaseType();
			if (!candidateEdition.equals("GeoLite2-City") && !candidateEdition.equals("GeoIP2-City"))
				throw new IllegalArgumentException("A City database is required");
			String candidateBuild = candidate.metadata().buildTime().toString();
			// Decode every record, not just the metadata or one sample address.
			try (var validation = new Reader(candidatePath.toFile(), FileMode.MEMORY)) {
				var networks = validation.networks(Map.class);
				while (networks.hasNext()) {
					if (Thread.currentThread().isInterrupted())
						return false;
					var address = networks.next().network().networkAddress();
					map(candidate.city(address), this.clock.instant().toString(), candidateEdition, candidateBuild);
				}
			}
			this.lifecycle.writeLock().lock();
			try {
				if (!this.running)
					return false;
				// No non-atomic fallback: an unsupported filesystem retains the current reader and file.
				Files.move(candidatePath, this.databasePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				closeReader();
				this.reader = candidate;
				candidate = null;
				this.edition = candidateEdition;
				this.buildAt = candidateBuild;
				this.loadedAt = this.clock.instant();
				this.updateFailed = false;
				this.lookupFailureReported.set(false);
				return true;
			} finally {
				this.lifecycle.writeLock().unlock();
			}
		} catch (IOException | GeoIp2Exception | IllegalArgumentException | DateTimeException |
				ClassCastException | DeserializationException | NetworksIterationException | InvalidNetworkException e) {
			this.lifecycle.writeLock().lock();
			try {
				if (!this.updateFailed)
					LOGGER.warn("GeoIP update rejected; retaining previous database (further warnings suppressed until successful update)");
				this.updateFailed = true;
			} finally {
				this.lifecycle.writeLock().unlock();
			}
			return false;
		} finally {
			close(candidate);
			if (candidatePath != null) {
				try {
					Files.deleteIfExists(candidatePath);
				} catch (IOException e) {
					LOGGER.warn("GeoIP candidate cleanup failed");
				}
			}
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
				return response.isPresent() ? map(response.get(), lookedUpAt, this.edition, this.buildAt) :
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

	private static Geo map(CityResponse response, String lookedUpAt, String edition, String buildAt) {
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
				radius == null ? null : radius.doubleValue(), "maxmind", edition, buildAt, lookedUpAt);
	}

	private static Geo empty(GeoStatus status, String provider, String edition, String buildAt, String lookedUpAt) {
		return new Geo(status, null, null, null, null, null, null, null, provider, edition, buildAt, lookedUpAt);
	}

	@Override
	public synchronized void stop() throws Exception {
		this.lifecycle.writeLock().lock();
		try {
			this.running = false;
			if (this.updater != null)
				this.updater.shutdownNow();
			closeReader();
			super.stop();
		} finally {
			this.lifecycle.writeLock().unlock();
		}
	}

	@Override
	public synchronized void destroy() throws Exception {
		this.lifecycle.writeLock().lock();
		try {
			this.running = false;
			if (this.updater != null)
				this.updater.shutdownNow();
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
		close(oldReader);
	}

	private static void close(DatabaseReader oldReader) {
		if (oldReader != null) {
			try {
				oldReader.close();
			} catch (IOException e) {
				LOGGER.warn("GeoIP reader reported a close failure");
			}
		}
	}
}
