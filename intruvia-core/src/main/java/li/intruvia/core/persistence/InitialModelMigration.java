// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.persistence;

import li.intruvia.core.model.EventModelMapper;
import li.intruvia.core.model.EventStreamState;
import li.strolch.agent.api.ComponentContainer;
import li.strolch.migrations.CodeMigration;
import li.strolch.persistence.api.StrolchTransaction;
import li.strolch.privilege.model.Certificate;
import li.strolch.utils.Version;

import static li.intruvia.core.model.ModelConstants.*;

/** Versioned initialization; never reset a previously committed stream. */
public final class InitialModelMigration extends CodeMigration {
	public static final Version VERSION = Version.valueOf("0.0.1");

	public InitialModelMigration(String realm) {
		super(realm, VERSION);
	}

	@Override
	public void migrate(ComponentContainer container, Certificate certificate) {
		try (StrolchTransaction tx = openTx(container, certificate).rollbackOnFailure()) {
			if (tx.getResourceBy(TYPE_EVENT_STREAM_STATE, STREAM_ID) != null)
				throw new IllegalStateException("Stream state exists without its initial migration; restore consistent storage");
			tx.add(new EventModelMapper().toResource(new EventStreamState("0", "0")));
			buildMigrationVersionChangeCommand(tx);
			tx.commitOnClose();
		}
	}
}
