// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.persistence;

import li.strolch.agent.api.ComponentContainer;
import li.strolch.agent.api.StrolchComponent;

/** Optional acceleration only: durable polling remains authoritative. Listener must never block. */
public final class EventStreamNotifications extends StrolchComponent {
	private volatile Runnable listener = () -> {};
	public EventStreamNotifications(ComponentContainer container, String name) { super(container, name); }
	public void listen(Runnable listener) { this.listener = listener; }
	public void committed() {
		try { this.listener.run(); }
		catch (RuntimeException e) {
			org.slf4j.LoggerFactory.getLogger(EventStreamNotifications.class).warn("Stream wakeup failed; durable polling will catch up");
		}
	}
	@Override public void stop() throws Exception { this.listener = () -> {}; super.stop(); }
}
