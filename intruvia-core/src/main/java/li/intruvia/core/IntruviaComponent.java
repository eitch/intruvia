// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core;

import li.strolch.agent.api.ComponentContainer;
import li.strolch.agent.api.StrolchComponent;

/** Lifecycle anchor for the application core; domain services are added by subsequent tasks. */
public class IntruviaComponent extends StrolchComponent {
	public IntruviaComponent(ComponentContainer container, String componentName) {
		super(container, componentName);
	}
}
