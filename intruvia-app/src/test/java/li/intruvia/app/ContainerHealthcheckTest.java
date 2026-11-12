// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ContainerHealthcheckTest {
	@Test
	public void acceptsOnlyReadyResponsesAndFailsWhenUnavailable() throws Exception {
		var status = new AtomicInteger(200);
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/health/ready", exchange -> {
			exchange.sendResponseHeaders(status.get(), -1);
			exchange.close();
		});
		server.start();
		URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/health/ready");
		try {
			assertTrue(ContainerHealthcheck.ready(uri));
			status.set(503);
			assertFalse(ContainerHealthcheck.ready(uri));
		} finally {
			server.stop(0);
		}
		assertFalse(ContainerHealthcheck.ready(uri));
	}
}