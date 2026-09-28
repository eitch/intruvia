// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** SQL is used only to provision/drop a unique test database, never for application persistence. */
final class DatabaseFixture implements AutoCloseable {
	final Path runtime;
	final String url;
	private final String database = "intruvia_test_" + UUID.randomUUID().toString().replace("-", "");
	private final String adminUrl = required("INTRUVIA_TEST_DB_URL");
	private final String username = required("INTRUVIA_TEST_DB_USERNAME");
	private final String password = required("INTRUVIA_TEST_DB_PASSWORD");

	DatabaseFixture() throws Exception {
		if (!this.adminUrl.matches("jdbc:postgresql://[^/?]+/[^/?]+"))
			throw new IllegalArgumentException("Test DB URL must be a simple PostgreSQL URL without query parameters");
		this.url = this.adminUrl.substring(0, this.adminUrl.lastIndexOf('/') + 1) + this.database;
		this.runtime = Files.createTempDirectory(Path.of("target"), "database-runtime-").toAbsolutePath();
		Files.createDirectories(this.runtime.resolve("config"));
		Files.createDirectories(this.runtime.resolve("data"));
		String config = Files.readString(Path.of("src/main/runtime/config/StrolchConfiguration.xml"))
				.replace("<db.useEnv>true</db.useEnv>", "<db.useEnv>false</db.useEnv>" +
						"<db.url>" + xml(this.url) + "</db.url><db.username>" + xml(this.username) +
						"</db.username><db.password>" + xml(this.password) + "</db.password>" +
						"<db.pool.maximumPoolSize>2</db.pool.maximumPoolSize>")
				.replace("<allowSchemaCreation>false", "<allowSchemaCreation>true");
		Files.writeString(this.runtime.resolve("config/StrolchConfiguration.xml"), config);
		for (String name : new String[]{"PrivilegeConfig.xml", "PrivilegeRoles.xml", "PrivilegeUsers.xml"})
			Files.copy(Path.of("src/test/resources/privilege", name), this.runtime.resolve("config").resolve(name));
		try (Connection connection = admin(); var statement = connection.createStatement()) {
			statement.executeUpdate("CREATE DATABASE " + this.database);
		}
	}

	java.util.Map<String, String> productionEnvironment() throws Exception {
		var path = this.runtime.resolve("config/StrolchConfiguration.xml");
		Files.writeString(path, Files.readString(path).replace("<db.useEnv>false</db.useEnv>", "<db.useEnv>true</db.useEnv>"));
		return java.util.Map.of("DB_URL", this.url, "DB_USERNAME", this.username, "DB_PASSWORD", this.password);
	}

	void rejectRollbackFixture() throws SQLException {
		try (var connection = DriverManager.getConnection(this.url, this.username, this.password);
				var statement = connection.createStatement()) {
			statement.executeUpdate("ALTER TABLE resources ADD CONSTRAINT fixture_failure CHECK (id <> 'rolled-back')");
		}
	}

	private static String xml(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String required(String key) {
		String value = System.getenv(key);
		if (value == null || value.isBlank())
			throw new IllegalStateException("Missing " + key + "; run scripts/verify-postgresql.sh");
		return value;
	}

	private Connection admin() throws SQLException {
		if (!org.postgresql.Driver.isRegistered())
			org.postgresql.Driver.register();
		return DriverManager.getConnection(this.adminUrl, this.username, this.password);
	}

	@Override
	public void close() throws Exception {
		try (Connection connection = admin(); var statement = connection.createStatement()) {
			statement.executeUpdate("DROP DATABASE " + this.database + " WITH (FORCE)");
		}
		// Do not leave even test credentials in the retained runtime artifacts.
		Files.deleteIfExists(this.runtime.resolve("config/StrolchConfiguration.xml"));
	}
}
