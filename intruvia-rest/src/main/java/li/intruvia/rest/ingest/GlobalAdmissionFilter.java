// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.ingest;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;

/** Bounds unauthenticated request work before route matching or PAT verification. */
@PreMatching
@Priority(Priorities.AUTHENTICATION - 100)
public final class GlobalAdmissionFilter implements ContainerRequestFilter {
	private final TokenBucket bucket;

	public GlobalAdmissionFilter(IngestionLimits limits) {
		this.bucket = new TokenBucket(limits.globalPerSecond(), limits.globalBurst());
	}

	@Override
	public void filter(ContainerRequestContext request) {
		int retry = this.bucket.acquire();
		if (retry > 0)
			request.abortWith(IngestHttp.limited(retry));
	}
}
