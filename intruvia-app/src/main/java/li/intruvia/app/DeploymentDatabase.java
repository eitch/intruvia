// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

final class DeploymentDatabase {

	private static final String ROLE_CHECK = """
			SELECT NOT r.rolsuper AND NOT r.rolcreatedb AND NOT r.rolcreaterole
			  AND NOT r.rolreplication AND NOT r.rolbypassrls AND d.datdba = r.oid
			  AND NOT EXISTS (
			    SELECT 1 FROM pg_catalog.pg_roles privileged
			    WHERE (privileged.rolsuper OR privileged.rolcreatedb OR privileged.rolcreaterole
			      OR privileged.rolreplication OR privileged.rolbypassrls
			      OR (privileged.rolname ~ '^pg_' AND privileged.rolname <> 'pg_database_owner'))
			      AND pg_catalog.pg_has_role(r.oid, privileged.oid, 'MEMBER')
			  )
			FROM pg_catalog.pg_roles r
			JOIN pg_catalog.pg_database d ON d.datname = current_database()
			WHERE r.rolname = current_user
			""";

	private static final String EMPTY_CHECK = """
			SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = 'public')
			AND NOT EXISTS (
			  SELECT 1 FROM pg_catalog.pg_namespace
			  WHERE nspname <> 'public' AND nspname <> 'information_schema'
			    AND nspname !~ '^pg_'
			)
			AND NOT EXISTS (
			  SELECT 1 FROM pg_catalog.pg_depend dep
			  JOIN pg_catalog.pg_namespace n ON n.oid = dep.refobjid
			  WHERE dep.refclassid = 'pg_catalog.pg_namespace'::pg_catalog.regclass AND n.nspname = 'public'
			)
			AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_extension WHERE extname <> 'plpgsql')
			AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_event_trigger)
			AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_publication)
			AND NOT EXISTS (SELECT subdbid FROM pg_catalog.pg_subscription
			  WHERE subdbid = (SELECT oid FROM pg_catalog.pg_database WHERE datname = current_database()))
			AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_foreign_data_wrapper)
			AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_foreign_server)
			AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_largeobject_metadata)
			AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_default_acl)
			AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_cast WHERE oid >= 16384)
			AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_language WHERE oid >= 16384)
			AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_transform WHERE oid >= 16384)
			""";

	private static final String INITIALIZED_CHECK = """
			SELECT EXISTS (
			  SELECT 1 FROM pg_catalog.pg_class c
			  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
			  WHERE n.nspname = 'public' AND c.relname = 'resources' AND c.relkind IN ('r', 'p')
			)
			""";

	private DeploymentDatabase() {
	}

	static void requireEmpty(Map<String, String> environment) {
		check(environment, EMPTY_CHECK, "PostgreSQL database is not empty or its public schema is missing");
	}

	static void requireInitialized(Map<String, String> environment) {
		check(environment, INITIALIZED_CHECK, "PostgreSQL database is missing the required public.resources table");
	}

	private static void check(Map<String, String> environment, String stateCheck, String stateFailure) {
		if (environment == null || missing(environment, "DB_URL") || missing(environment, "DB_USERNAME") ||
				missing(environment, "DB_PASSWORD") || !environment.get("DB_URL").startsWith("jdbc:postgresql://"))
			throw new ConfigurationFailure("Missing or invalid PostgreSQL connection settings");

		int result;
		// One statement uses one catalog snapshot for the role and database checks.
		String sql = "SELECT COALESCE((" + ROLE_CHECK + "), false), (" + stateCheck + ")";
		try (var connection = DriverManager.getConnection(environment.get("DB_URL"), environment.get("DB_USERNAME"),
				environment.get("DB_PASSWORD")); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
			if (!rows.next())
				result = 0;
			else if (!rows.getBoolean(1))
				result = 1;
			else if (!rows.getBoolean(2))
				result = 2;
			else
				result = 3;
		} catch (SQLException | RuntimeException e) {
			throw new ConfigurationFailure("Unable to verify PostgreSQL database safety");
		}
		if (result == 0)
			throw new ConfigurationFailure("Unable to verify PostgreSQL database safety");
		if (result == 1)
			throw new ConfigurationFailure("PostgreSQL user must directly own the database and have no administrative role privileges");
		if (result == 2)
			throw new ConfigurationFailure(stateFailure);
	}

	private static boolean missing(Map<String, String> environment, String name) {
		String value = environment.get(name);
		return value == null || value.isBlank();
	}
}