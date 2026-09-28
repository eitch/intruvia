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
	}
}
