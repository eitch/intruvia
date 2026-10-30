// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.retention;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import li.strolch.agent.api.ComponentContainer;
import li.strolch.agent.api.StrolchComponent;
import li.strolch.runtime.configuration.ComponentConfiguration;
import li.strolch.service.api.ServiceHandler;
import org.slf4j.LoggerFactory;

/** Strolch-owned scheduler. Stops before the model/realm dependencies; no task survives shutdown. */
public final class RetentionComponent extends StrolchComponent {
	private RetentionPolicy policy = RetentionPolicy.DEFAULT;
	private ScheduledExecutorService scheduler;
	public RetentionComponent(ComponentContainer container, String name) { super(container, name); }
	public RetentionPolicy policy() { return this.policy; }
	@Override public void initialize(ComponentConfiguration configuration) throws Exception {
		this.policy = RetentionPolicy.load(configuration);
		super.initialize(configuration);
	}

	@Override public synchronized void start() throws Exception {
		if (this.scheduler != null)
			throw new IllegalStateException("Retention already running");
		super.start();
		this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, "intruvia-retention"));
		this.scheduler.scheduleWithFixedDelay(this::sweep, this.policy.sweepSeconds(), this.policy.sweepSeconds(), TimeUnit.SECONDS);
	}

	private void sweep() {
		try {
			getContainer().getPrivilegeHandler().runAsAgent(ctx -> {
				var result = getComponent(ServiceHandler.class).doService(ctx.getCertificate(),
						new RetentionService(this.policy, Clock.systemUTC()), new li.strolch.service.api.ServiceArgument());
				if (!result.isOk())
					LoggerFactory.getLogger(RetentionComponent.class).warn("Retention sweep failed; will retry on next sweep");
			});
		} catch (Exception e) {
			// runAsAgent declares checked Exception; keep the recurring job alive without logging sensitive details.
			LoggerFactory.getLogger(RetentionComponent.class).warn("Retention sweep failed; will retry on next sweep");
		}
	}

	private void stopScheduler() throws InterruptedException {
		if (this.scheduler == null)
			return;
		this.scheduler.shutdownNow();
		try {
			if (!this.scheduler.awaitTermination(30, TimeUnit.SECONDS))
				throw new IllegalStateException("Retention did not stop");
		} finally {
			this.scheduler = null;
		}
	}
	@Override public synchronized void stop() throws Exception { stopScheduler(); super.stop(); }
	@Override public synchronized void destroy() throws Exception { stopScheduler(); super.destroy(); }
}
