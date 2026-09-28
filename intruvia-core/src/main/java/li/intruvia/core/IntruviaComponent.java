// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core;

import li.intruvia.core.persistence.InitialModelMigration;
import li.intruvia.core.persistence.EventRepository;
import li.strolch.migrations.CodeMigration;
import li.strolch.migrations.MigrationsHandler;
import li.strolch.utils.collections.MapOfLists;
import li.strolch.agent.api.ComponentContainer;
import li.strolch.agent.api.StrolchComponent;

/** Initializes the versioned model before HTTP starts. */
public class IntruviaComponent extends StrolchComponent {
	public IntruviaComponent(ComponentContainer container, String componentName) {
		super(container, componentName);
	}
	@Override
	public void start() throws Exception {
		getContainer().getPrivilegeHandler().runAsAgent(ctx -> {
			MapOfLists<String, CodeMigration> migrations = new MapOfLists<>();
			for (String realm : getContainer().getRealmNames())
				migrations.addElement(realm, new InitialModelMigration(realm));
			getComponent(MigrationsHandler.class).runCodeMigrations(ctx.getCertificate(), migrations);
			try (var tx = getContainer().getAgent().openTx(ctx.getCertificate(), "VerifyModel", true)) {
				new EventRepository().streamState(tx);
			}
		});
		super.start();
	}
}
