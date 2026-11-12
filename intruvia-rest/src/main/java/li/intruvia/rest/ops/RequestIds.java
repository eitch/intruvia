// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.ops;

import org.slf4j.MDC;
import java.util.UUID;

/** Server-generated only; incoming request IDs are never trusted or echoed. */
public final class RequestIds {
	private RequestIds() {}
	public static String current() {
		String value = MDC.get("requestId");
		return value == null ? UUID.randomUUID().toString() : value;
	}
}
