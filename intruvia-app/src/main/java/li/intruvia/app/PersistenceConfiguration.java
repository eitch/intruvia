// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.util.Arrays;

import li.strolch.persistence.postgresql.PostgreSqlPersistenceHandler;
import li.strolch.runtime.configuration.StrolchConfiguration;

/** Fail closed before components initialize: there is no transient production fallback. */
final class PersistenceConfiguration {
	private PersistenceConfiguration() {}

	static void validate(StrolchConfiguration configuration) {
		var realm = configuration.getComponentConfiguration("RealmHandler");
		var persistence = configuration.getComponentConfiguration("PersistenceHandler");
		if (!"li.strolch.agent.impl.DefaultRealmHandler".equals(realm.getImpl()) ||
				!Arrays.equals(new String[]{"defaultRealm"}, realm.getStringArray("realms", "defaultRealm")) ||
				!"CACHED".equals(realm.getString("dataStoreMode", "")) ||
				!PostgreSqlPersistenceHandler.class.getName().equals(persistence.getImpl()) ||
				persistence.getBoolean("db.ignoreRealm", false) || persistence.getBoolean("allowSchemaDrop", false))
			throw new IllegalStateException("Intruvia requires one CACHED PostgreSQL realm with schema drop disabled");
		boolean environment = persistence.getBoolean("db.useEnv", false);
		for (String key : new String[]{"db.url", "db.username", "db.password"}) {
			String value = environment ? System.getenv(key.replace('.', '_').toUpperCase(java.util.Locale.ROOT)) :
					key.equals("db.password") ? persistence.getSecret(key) : persistence.getString(key, "");
			if (value == null || value.isBlank() || key.equals("db.url") && !value.startsWith("jdbc:postgresql://"))
				throw new ConfigurationFailure("Missing or invalid PostgreSQL setting: " + key);
		}
		poolBound(persistence, environment, "maximumPoolSize", 10, 1, 256);
		poolBound(persistence, environment, "connectionTimeout", 30000, 250, 30000);
		poolBound(persistence, environment, "validationTimeout", 5000, 250, 5000);
	}

	private static void poolBound(li.strolch.runtime.configuration.ComponentConfiguration persistence, boolean environment,
			String key, int fallback, int minimum, int maximum) {
		String value = environment ? System.getenv().getOrDefault("DB_POOL_" + key, Integer.toString(fallback)) :
				persistence.getAsMap().getOrDefault("db.pool." + key, Integer.toString(fallback));
		try {
			int number = Integer.parseInt(value);
			if (number >= minimum && number <= maximum) return;
		} catch (NumberFormatException e) {
			// Report only the fixed Hikari setting name.
		}
		throw new ConfigurationFailure("Invalid PostgreSQL pool setting: " + key);
	}
}
