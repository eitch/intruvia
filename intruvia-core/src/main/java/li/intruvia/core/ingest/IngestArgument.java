// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.ingest;

import li.strolch.service.api.ServiceArgument;

/** Producer data only. Identity is resolved by the service from its certificate. */
public final class IngestArgument extends ServiceArgument {
	public Fail2banEvent event;

	public IngestArgument() {}

	public IngestArgument(Fail2banEvent event) {
		this.event = event;
	}
}
