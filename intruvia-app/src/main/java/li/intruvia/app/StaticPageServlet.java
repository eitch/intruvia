// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Serves only public packaged web assets; runtime and other classpath resources are never exposed. */
final class StaticPageServlet extends HttpServlet {
	private static final Map<String, String> CONTENT_TYPES = Map.of(
			"html", "text/html;charset=UTF-8", "js", "text/javascript;charset=UTF-8",
			"css", "text/css;charset=UTF-8", "geojson", "application/geo+json;charset=UTF-8", "png", "image/png");

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String path = request.getRequestURI();
		if ("/".equals(path)) path = "/index.html";
		if (!path.matches("/(index\\.html|viewer\\.css|js/[A-Za-z0-9/]+\\.js|data/world-110m\\.geojson|" +
				"vendor/leaflet/(leaflet\\.(js|css)|images/[a-z0-9-]+\\.png))")) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		try (InputStream asset = getClass().getResourceAsStream("/META-INF/resources" + path)) {
			if (asset == null) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			response.setContentType(CONTENT_TYPES.get(path.substring(path.lastIndexOf('.') + 1)));
			response.setHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
					"img-src 'self' data:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
			response.setHeader("X-Content-Type-Options", "nosniff");
			response.setHeader("Cache-Control", "no-cache");
			response.setHeader("Referrer-Policy", "same-origin");
			asset.transferTo(response.getOutputStream());
		}
	}
}
