// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.geo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only MMDB 2.0 encoder for invented records. No MaxMind dataset is copied or redistributed.
 */
final class SyntheticCityDatabase {
	static final long BUILD_EPOCH = 1_700_000_000L;

	private record Unsigned(long value, int type, int size) {
	}

	private static final class Node {
		final Object[] children = new Object[2];
		final int index;

		Node(int index) {
			this.index = index;
		}
	}

	static Path write(Path path, Map<String, Object> records) throws IOException {
		return write(path, records, "GeoIP2-City");
	}

	static Path write(Path path, Map<String, Object> records, String edition) throws IOException {
		List<Node> nodes = new ArrayList<>();
		nodes.add(new Node(0));
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		for (var entry : records.entrySet()) {
			byte[] address = InetAddress.ofLiteral(entry.getKey()).getAddress();
			byte[] ipv6 = new byte[16];
			System.arraycopy(address, 0, ipv6, 16 - address.length, address.length);
			Node node = nodes.getFirst();
			for (int bit = 0; bit < 128; bit++) {
				int branch = (ipv6[bit / 8] >>> (7 - bit % 8)) & 1;
				if (bit == 127) {
					node.children[branch] = data.size();
					encode(data, entry.getValue());
				} else {
					if (node.children[branch] == null) {
						Node child = new Node(nodes.size());
						nodes.add(child);
						node.children[branch] = child;
					}
					node = (Node) node.children[branch];
				}
			}
		}
		ByteArrayOutputStream file = new ByteArrayOutputStream();
		for (Node node : nodes) {
			for (Object child : node.children) {
				int pointer = child == null ? nodes.size() :
						child instanceof Node next ? next.index : nodes.size() + 16 + (Integer) child;
				file.write(pointer >>> 16);
				file.write(pointer >>> 8);
				file.write(pointer);
			}
		}
		file.write(new byte[16]);
		file.write(data.toByteArray());
		file.write(new byte[]{(byte) 0xab, (byte) 0xcd, (byte) 0xef});
		file.write("MaxMind.com".getBytes(StandardCharsets.US_ASCII));
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("node_count", nodes.size());
		metadata.put("record_size", new Unsigned(24, 5, 2));
		metadata.put("ip_version", new Unsigned(6, 5, 2));
		metadata.put("database_type", edition);
		metadata.put("languages", List.of("en"));
		metadata.put("binary_format_major_version", new Unsigned(2, 5, 2));
		metadata.put("binary_format_minor_version", new Unsigned(0, 5, 2));
		metadata.put("build_epoch", new Unsigned(BUILD_EPOCH, 9, 8));
		metadata.put("description", Map.of("en", "Intruvia synthetic test"));
		encode(file, metadata);
		Files.write(path, file.toByteArray());
		return path;
	}

	private static void encode(ByteArrayOutputStream out, Object value) throws IOException {
		switch (value) {
			case Map<?, ?> map -> {
				header(out, 7, map.size());
				// Stable byte output regardless of the input map's iteration order.
				for (String key : map.keySet().stream().map(String.class::cast).sorted().toList()) {
					encode(out, key);
					encode(out, map.get(key));
				}
			}
			case List<?> list -> {
				header(out, 11, list.size());
				for (Object item : list)
					encode(out, item);
			}
			case String text -> {
				byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
				header(out, 2, bytes.length);
				out.write(bytes);
			}
			case Double number -> {
				header(out, 3, 8);
				new DataOutputStream(out).writeDouble(number);
			}
			case Integer number -> encode(out, new Unsigned(number, 6, 4));
			case Unsigned(long value1, int type, int size) -> {
				header(out, type, size);
				for (int i = size - 1; i >= 0; i--)
					out.write((int) (value1 >>> (i * 8)));
			}
			case null, default -> throw new IllegalArgumentException("Unsupported synthetic value");
		}
	}

	private static void header(ByteArrayOutputStream out, int type, int size) {
		if (size >= 285)
			throw new IllegalArgumentException("Synthetic fixture value too large");
		out.write((type < 8 ? type << 5 : 0) | Math.min(size, 29));
		if (type >= 8)
			out.write(type - 7);
		if (size >= 29)
			out.write(size - 29);
	}
}
