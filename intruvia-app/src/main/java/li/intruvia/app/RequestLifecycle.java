// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import li.intruvia.rest.ops.RuntimeDiagnostics;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.UUID;

/** Synchronous requests own admission until completion, before any Strolch component can be destroyed. */
final class RequestLifecycle implements Filter {
	private final RuntimeDiagnostics diagnostics;
	private boolean stopping;
	private int active;
	private long lastFailureLog;
	RequestLifecycle(RuntimeDiagnostics diagnostics) { this.diagnostics = diagnostics; }
	synchronized boolean stopping() { return this.stopping; }
	synchronized void drain() throws InterruptedException {
		this.stopping = true;
		long deadline = System.nanoTime() + 30_000_000_000L;
		while (this.active != 0) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) throw new IllegalStateException("Requests still active; persistence left running");
			java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
		}
	}
	@Override public void doFilter(ServletRequest raw, ServletResponse output, FilterChain chain) throws IOException, ServletException {
		var request = (HttpServletRequest) raw;
		var response = (HttpServletResponse) output;
		String id = UUID.randomUUID().toString();
		String previous = MDC.get("requestId");
		MDC.put("requestId", id);
		response.setHeader("X-Request-ID", id);
		boolean admitted;
		synchronized (this) { admitted = !this.stopping; if (admitted) this.active++; }
		try {
			if (admitted) chain.doFilter(raw, output);
			else {
				response.setStatus(503);
				response.setContentType("application/json");
				response.setHeader("Cache-Control", "no-store");
				response.getWriter().write("{\"error\":{\"code\":\"stopping\",\"message\":\"Server stopping\",\"requestId\":\"" + id + "\"}}");
			}
		} finally {
			if (request.getMethod().equals("POST") && request.getRequestURI().equals("/api/v1/fail2ban/events"))
				this.diagnostics.ingestion(response.getStatus());
			if (response.getStatus() >= 400) logFailure(response.getStatus(), id);
			if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous);
			synchronized (this) { if (admitted) this.active--; notifyAll(); }
		}
	}
	private synchronized void logFailure(int status, String id) {
		long now = System.nanoTime();
		if (now - this.lastFailureLog < 1_000_000_000L) return;
		this.lastFailureLog = now;
		LoggerFactory.getLogger(RequestLifecycle.class).warn("request_failed requestId={} status={}", id, status);
	}
}
