// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.net.ServerSocket;

public final class AdminBrowserFixture {
	public static void main(String[] args) throws Exception {
		int port;
		try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
		String origin = "http://127.0.0.1:" + port;
		try (var database = ViewerSessionIT.database(origin)) {
			ViewerSessionIT.addAdministrator(database);
			try (var app = new IntruviaApplication(database.runtime, port)) {
				app.start();
				System.out.println("ADMIN_BROWSER_FIXTURE=" + origin);
				System.out.flush();
				try (var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
					while (input.readLine() != null) { }
				}
			}
		}
	}
}