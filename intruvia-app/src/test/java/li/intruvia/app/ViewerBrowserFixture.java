// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.net.ServerSocket;
import java.time.Instant;
import java.util.UUID;
import li.intruvia.core.ingest.Fail2banEvent;
import li.intruvia.core.model.*;
import li.intruvia.core.persistence.*;

/** Optional browser smoke fixture: real Jetty/Strolch/PostgreSQL, synthetic geography, no production credentials. */
public final class ViewerBrowserFixture {
	public static void main(String[] args) throws Exception {
		int port;
		try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
		String origin = "http://127.0.0.1:" + port;
		try (var database = ViewerSessionIT.database(origin); var app = new IntruviaApplication(database.runtime, port)) {
			app.start();
			app.agent().getPrivilegeHandler().runAsAgent(ctx -> {
				try (var tx = app.agent().openTx(ctx.getCertificate(), "BrowserFixture", false).rollbackOnFailure()) {
					EventStreamLock.acquire(tx);
					var repository = new EventRepository();
					for (long sequence = 1; sequence <= 1005; sequence++) {
						int kind = (int) (sequence % 6);
						GeoStatus status = GeoStatus.values()[kind % GeoStatus.values().length];
						boolean mapped = kind == 0;
						var geo = new SecurityEvent.Geo(mapped ? GeoStatus.FOUND : status, "ZZ", "Synthetic country", null,
								mapped ? "Synthetic zero point" : null, mapped ? 0.0 : null, mapped ? 0.0 : null,
								mapped ? 25.0 : null, "maxmind", "Synthetic fixture", null, null);
						var payload = new Fail2banEvent(UUID.randomUUID(), Instant.now(), "2001:db8::42",
								"<img src=x onerror=window.pwned=1>", null);
						repository.addEvent(tx, payload.toEvent("fixture-server", UUID.randomUUID(), sequence, Instant.now(), geo));
					}
					repository.updateStreamState(tx, new EventStreamState("1005", "0"));
					tx.commitOnClose();
				}
			});
			System.out.println("BROWSER_FIXTURE=" + origin);
			System.out.flush();
			// The harness closes stdin to release both the application and disposable database.
			try (var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
				long sequence = 1005;
				String command;
				while ((command = input.readLine()) != null) {
					final long next = ++sequence;
					final boolean mapped = !command.equals("unmapped");
					app.agent().getPrivilegeHandler().runAsAgent(ctx -> {
						try (var tx = app.agent().openTx(ctx.getCertificate(), "BrowserLiveFixture", false).rollbackOnFailure()) {
							EventStreamLock.acquire(tx);
							var repository = new EventRepository();
							var geo = new SecurityEvent.Geo(mapped ? GeoStatus.FOUND : GeoStatus.NON_PUBLIC, null, null, null, null,
									mapped ? 0.0 : null, mapped ? 0.0 : null, null, null, null, null, null);
							var payload = new Fail2banEvent(UUID.randomUUID(), Instant.now(), "2001:db8::42", "live-fixture", null);
							repository.addEvent(tx, payload.toEvent("live-fixture-" + next, UUID.randomUUID(), next, Instant.now(), geo));
							repository.updateStreamState(tx, new EventStreamState(Long.toString(next), "0"));
							tx.commitOnClose();
						}
					});
				}
			}
		}
	}
}
