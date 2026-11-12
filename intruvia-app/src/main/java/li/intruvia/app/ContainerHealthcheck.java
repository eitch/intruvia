// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;

/** Probes private readiness without requiring a network client in the image. */
public final class ContainerHealthcheck {
	private ContainerHealthcheck() {}

	public static void main(String[] args) {
		if (args.length != 0 || !ready(URI.create("http://127.0.0.1:8080/health/ready"))) System.exit(1);
	}

	static boolean ready(URI uri) {
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
			connection.setConnectTimeout(2000);
			connection.setReadTimeout(2000);
			connection.setInstanceFollowRedirects(false);
			return connection.getResponseCode() == 200;
		} catch (IOException e) {
			return false;
		} finally {
			if (connection != null) connection.disconnect();
		}
	}
}