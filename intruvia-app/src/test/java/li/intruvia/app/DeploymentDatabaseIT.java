// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import static org.junit.Assert.*;

public class DeploymentDatabaseIT {

	@Test(timeout = 60000)
	public void acceptsEmptyDatabaseAndRequiresARealResourcesTable() throws Exception {
		try (var database = new ProbeDatabase()) {
			DeploymentDatabase.requireEmpty(database.environment());
			assertThrows(ConfigurationFailure.class, () -> DeploymentDatabase.requireInitialized(database.environment()));
			database.execute("CREATE VIEW public.resources AS SELECT 1 AS id");
			assertThrows(ConfigurationFailure.class, () -> DeploymentDatabase.requireInitialized(database.environment()));
			database.execute("DROP VIEW public.resources");
			database.execute("CREATE TABLE public.resources (id integer PRIMARY KEY)");
			database.execute("INSERT INTO public.resources VALUES (42)");
			DeploymentDatabase.requireInitialized(database.environment());
			assertThrows(ConfigurationFailure.class, () -> DeploymentDatabase.requireEmpty(database.environment()));
			assertEquals(42, database.integer("SELECT id FROM public.resources"));
		}
	}

	@Test(timeout = 60000)
	public void rejectsPublicObjectsAndNonSystemSchemasWithoutRemovingThem() throws Exception {
		try (var database = new ProbeDatabase()) {
			String[][] objects = {
					{"CREATE SCHEMA other", "DROP SCHEMA other"},
					{"CREATE SEQUENCE public.fixture_sequence", "DROP SEQUENCE public.fixture_sequence"},
					{"CREATE FUNCTION public.fixture_function() RETURNS integer LANGUAGE sql AS 'SELECT 1'",
							"DROP FUNCTION public.fixture_function()"},
					{"CREATE TYPE public.fixture_type AS ENUM ('value')", "DROP TYPE public.fixture_type"},
					{"CREATE OPERATOR public.=== (PROCEDURE = pg_catalog.int4pl, LEFTARG = integer, RIGHTARG = integer)",
							"DROP OPERATOR public.=== (integer, integer)"},
					{"CREATE EXTENSION hstore", "DROP EXTENSION hstore"},
					{"CREATE PUBLICATION fixture_publication", "DROP PUBLICATION fixture_publication"},
					{"ALTER DEFAULT PRIVILEGES GRANT SELECT ON TABLES TO PUBLIC",
							"ALTER DEFAULT PRIVILEGES REVOKE SELECT ON TABLES FROM PUBLIC"}
			};
			for (String[] object : objects) {
				DeploymentDatabase.requireEmpty(database.environment());
				database.execute(object[0]);
				assertThrows(object[0], ConfigurationFailure.class, () -> DeploymentDatabase.requireEmpty(database.environment()));
				// DROP without IF EXISTS proves the probe did not remove the object.
				database.execute(object[1]);
			}
			DeploymentDatabase.requireEmpty(database.environment());
		}
	}

	@Test(timeout = 60000)
	public void rejectsMissingPublicSchema() throws Exception {
		try (var database = new ProbeDatabase()) {
			database.execute("DROP SCHEMA public");
			assertThrows(ConfigurationFailure.class, () -> DeploymentDatabase.requireEmpty(database.environment()));
			assertThrows(ConfigurationFailure.class, () -> DeploymentDatabase.requireInitialized(database.environment()));
			assertEquals(0, database.integer("SELECT count(*) FROM pg_catalog.pg_namespace WHERE nspname = 'public'"));
		}
	}

	@Test(timeout = 60000)
	public void rejectsExistingSubscriptionWithoutReadingItsConnectionSecret() throws Exception {
		try (var database = new ProbeDatabase()) {
			database.adminDatabaseExecute("CREATE SUBSCRIPTION fixture_subscription " +
					"CONNECTION 'host=127.0.0.1 port=1 dbname=unused user=unused' " +
					"PUBLICATION fixture_publication WITH (connect=false)");
			try {
				assertEquals(1, database.integer("SELECT count(subdbid) FROM pg_catalog.pg_subscription " +
						"WHERE subdbid = (SELECT oid FROM pg_catalog.pg_database WHERE datname = current_database())"));
				assertThrows(ConfigurationFailure.class, () -> DeploymentDatabase.requireEmpty(database.environment()));
			} finally {
				database.adminDatabaseExecute("ALTER SUBSCRIPTION fixture_subscription SET (slot_name = NONE)");
				database.adminDatabaseExecute("DROP SUBSCRIPTION fixture_subscription");
			}
		}
	}

	@Test(timeout = 60000)
	public void rejectsAdministrativeRolesAndIndirectOrMissingOwnership() throws Exception {
		try (var database = new ProbeDatabase()) {
			for (String privilege : new String[]{"CREATEDB", "CREATEROLE", "SUPERUSER", "REPLICATION", "BYPASSRLS"}) {
				DeploymentDatabase.requireEmpty(database.environment());
				database.execute("CREATE TABLE public.resources (id integer)");
				DeploymentDatabase.requireInitialized(database.environment());
				database.adminExecute("ALTER ROLE " + database.role + " " + privilege);
				assertThrows(privilege, ConfigurationFailure.class, () -> DeploymentDatabase.requireInitialized(database.environment()));
				database.execute("DROP TABLE public.resources");
				assertThrows(privilege, ConfigurationFailure.class, () -> DeploymentDatabase.requireEmpty(database.environment()));
				database.adminExecute("ALTER ROLE " + database.role + " NO" + privilege);
			}
			DeploymentDatabase.requireEmpty(database.environment());
			database.adminExecute("CREATE ROLE " + database.ownerRole + " CREATEDB");
			database.ownerRoleCreated = true;
			database.adminExecute("GRANT " + database.ownerRole + " TO " + database.role);
			assertThrows(ConfigurationFailure.class, () -> DeploymentDatabase.requireEmpty(database.environment()));
			database.adminExecute("ALTER ROLE " + database.ownerRole + " NOCREATEDB");
			DeploymentDatabase.requireEmpty(database.environment());
			database.adminExecute("ALTER DATABASE " + database.name + " OWNER TO " + database.ownerRole);
			assertThrows(ConfigurationFailure.class, () -> DeploymentDatabase.requireEmpty(database.environment()));
			database.execute("CREATE TABLE public.resources (id integer)");
			assertThrows(ConfigurationFailure.class, () -> DeploymentDatabase.requireInitialized(database.environment()));
		}
	}

	@Test(timeout = 60000)
	public void neverReportsConnectionSecretsOrSqlExceptionDetails() throws Exception {
		try (var database = new ProbeDatabase()) {
			String canary = "secret_canary_" + UUID.randomUUID().toString().replace("-", "");
			String url = database.url + "?ApplicationName=" + canary;
			var invalid = Map.of("DB_URL", url, "DB_USERNAME", database.role, "DB_PASSWORD", canary);
			for (var failure : new ConfigurationFailure[]{
					assertThrows(ConfigurationFailure.class, () -> DeploymentDatabase.requireEmpty(invalid)),
					assertThrows(ConfigurationFailure.class, () -> DeploymentDatabase.requireInitialized(invalid)),
					assertThrows(ConfigurationFailure.class, () -> DeploymentDatabase.requireEmpty(
							Map.of("DB_URL", canary, "DB_USERNAME", canary, "DB_PASSWORD", canary)))}) {
				var trace = new StringWriter();
				failure.printStackTrace(new PrintWriter(trace));
				assertFalse(trace.toString().contains(canary));
				assertFalse(trace.toString().contains(database.url));
				assertFalse(trace.toString().contains(database.role));
				assertFalse(trace.toString().contains("SQLException"));
				assertNull(failure.getCause());
				assertEquals(0, failure.getSuppressed().length);
			}
		}
	}

	static final class ProbeDatabase implements AutoCloseable {
		private final String suffix = UUID.randomUUID().toString().replace("-", "");
		private final String name = "intruvia_probe_" + this.suffix;
		private final String role = "intruvia_probe_" + this.suffix;
		private final String ownerRole = "intruvia_owner_" + this.suffix;
		private final String password = "probe_" + UUID.randomUUID().toString().replace("-", "");
		private final String adminUrl = required("INTRUVIA_TEST_DB_URL");
		private final String adminUser = required("INTRUVIA_TEST_DB_USERNAME");
		private final String adminPassword = required("INTRUVIA_TEST_DB_PASSWORD");
		private final String url;
		private boolean roleCreated;
		private boolean databaseCreated;
		private boolean ownerRoleCreated;

		ProbeDatabase() throws SQLException {
			if (!this.adminUrl.matches("jdbc:postgresql://[^/?]+/[^/?]+"))
				throw new IllegalArgumentException("Test DB URL must be a simple PostgreSQL URL without query parameters");
			this.url = this.adminUrl.substring(0, this.adminUrl.lastIndexOf('/') + 1) + this.name;
			try {
				adminExecute("CREATE ROLE " + this.role + " LOGIN PASSWORD '" + this.password + "'");
				this.roleCreated = true;
				adminExecute("CREATE DATABASE " + this.name + " OWNER " + this.role + " TEMPLATE template0");
				this.databaseCreated = true;
			} catch (SQLException e) {
				try {
					close();
				} catch (SQLException cleanup) {
					e.addSuppressed(cleanup);
				}
				throw e;
			}
		}

		Map<String, String> environment() {
			return Map.of("DB_URL", this.url, "DB_USERNAME", this.role, "DB_PASSWORD", this.password);
		}

		private void adminExecute(String sql) throws SQLException {
			try (var connection = DriverManager.getConnection(this.adminUrl, this.adminUser, this.adminPassword);
					var statement = connection.createStatement()) {
				statement.execute(sql);
			}
		}

		private void adminDatabaseExecute(String sql) throws SQLException {
			try (var connection = DriverManager.getConnection(this.url, this.adminUser, this.adminPassword);
					var statement = connection.createStatement()) {
				statement.execute(sql);
			}
		}

		private Connection connect() throws SQLException {
			return DriverManager.getConnection(this.url, this.role, this.password);
		}

		void execute(String sql) throws SQLException {
			try (var connection = connect(); var statement = connection.createStatement()) {
				statement.execute(sql);
			}
		}

		int integer(String sql) throws SQLException {
			try (var connection = connect(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
				assertTrue(rows.next());
				return rows.getInt(1);
			}
		}

		@Override
		public void close() throws SQLException {
			try {
				if (this.databaseCreated)
					adminExecute("DROP DATABASE " + this.name + " WITH (FORCE)");
			} finally {
				try {
					if (this.roleCreated)
						adminExecute("DROP ROLE " + this.role);
				} finally {
					if (this.ownerRoleCreated)
						adminExecute("DROP ROLE " + this.ownerRole);
				}
			}
		}

		private static String required(String key) {
			String value = System.getenv(key);
			if (value == null || value.isBlank())
				throw new IllegalStateException("Missing " + key + "; configure a PostgreSQL test administrator");
			return value;
		}
	}
}