// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Protected username-to-instance mapping; contains no credentials or verification material. */
public record MachineIdentities(Map<String, String> instances) {
	public MachineIdentities {
		instances = Map.copyOf(instances);
		if (instances.entrySet().stream().anyMatch(entry -> !validId(entry.getKey()) || !validId(entry.getValue())) ||
				new HashSet<>(instances.values()).size() != instances.size())
			throw new IllegalArgumentException("Invalid or ambiguous machine identity mapping");
	}

	public static MachineIdentities load(Path path) throws IOException {
		if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
			return new MachineIdentities(Map.of());
		try {
			var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
			if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || permissions.stream().anyMatch(permission ->
					permission != PosixFilePermission.OWNER_READ && permission != PosixFilePermission.OWNER_WRITE))
				throw new IOException("Machine identity mapping must be an owner-only regular file");
			if (Files.size(path) > 65536)
				throw new IOException("Machine identity mapping exceeds limit");
			Map<String, String> instances = new HashMap<>();
			for (String line : Files.readAllLines(path)) {
				if (line.isBlank() || line.startsWith("#"))
					continue;
				String[] fields = line.split("=", -1);
				if (fields.length != 2 || !validId(fields[0]) || !validId(fields[1]) ||
						instances.putIfAbsent(fields[0], fields[1]) != null)
					throw new IOException("Invalid or duplicate machine identity mapping");
			}
			return new MachineIdentities(instances);
		} catch (UnsupportedOperationException | SecurityException | IllegalArgumentException e) {
			throw new IOException("Cannot load protected machine identity mapping");
		}
	}

	private static boolean validId(String value) {
		return value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
	}
}
