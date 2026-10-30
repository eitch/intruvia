// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.retention;

import java.time.Clock;
import li.intruvia.core.persistence.EventStreamLock;
import li.strolch.service.api.AbstractService;
import li.strolch.service.api.ServiceArgument;
import li.strolch.service.api.ServiceResult;

/** Maintenance entry point; never exposed through REST or available to machine/viewer credentials. */
public final class RetentionService extends AbstractService<ServiceArgument, ServiceResult> {
	public static final String PRIVILEGE = "event:retention";
	private final Clock clock;
	private final RetentionPolicy policy;
	public RetentionService(RetentionPolicy policy, Clock clock) { this.policy = policy; this.clock = clock; }
	@Override public String getPrivilegeName() { return PRIVILEGE; }
	@Override public String getPrivilegeValue() { return PRIVILEGE; }
	@Override public ServiceArgument getArgumentInstance() { return new ServiceArgument(); }
	@Override protected ServiceResult getResultInstance() { return new ServiceResult(); }

	@Override protected ServiceResult internalDoService(ServiceArgument arg) {
		if (arg.realm != null)
			throw new IllegalArgumentException("Retention realm is not caller selectable");
		try (var tx = openUserTx().rollbackOnFailure()) {
			EventStreamLock.acquire(tx);
			tx.addCommand(new RetentionCommand(tx, this.policy, this.clock.instant(), PRIVILEGE, false));
			tx.commitOnClose();
		}
		return ServiceResult.success();
	}
}
