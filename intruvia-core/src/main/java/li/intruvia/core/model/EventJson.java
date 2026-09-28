// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Shared wire codec. String sequences preserve all signed 64-bit values in browsers. */
public final class EventJson {
	private static final Gson GSON = new GsonBuilder().serializeNulls().create();

	private EventJson() {}

	public static String write(Object value) { return GSON.toJson(value); }

	public static <T> T read(String json, Class<T> type) { return GSON.fromJson(json, type); }
}
