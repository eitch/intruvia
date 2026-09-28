// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;

/** Serves only the packaged public landing page, never runtime or arbitrary classpath files. */
final class StaticPageServlet extends HttpServlet {
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String path = request.getRequestURI();
		if (!"/".equals(path) && !"/index.html".equals(path)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		try (InputStream page = getClass().getResourceAsStream("/META-INF/resources/index.html")) {
			if (page == null) {
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				return;
			}
			response.setContentType("text/html;charset=UTF-8");
			response.setHeader("Content-Security-Policy", "default-src 'none'; base-uri 'none'; frame-ancestors 'none'");
			response.setHeader("X-Content-Type-Options", "nosniff");
			page.transferTo(response.getOutputStream());
		}
	}
}
