// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.auth;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Protected username-to-instance mapping; contains no credentials or verification material. */
public final class MachineIdentities {
	private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE);
	private final Path file;
	private volatile Map<String, String> instances;

	public MachineIdentities(Map<String, String> instances) {
		this(null, instances);
	}

	private MachineIdentities(Path file, Map<String, String> instances) {
		this.file = file;
		this.instances = validated(instances);
	}

	public Map<String, String> instances() { return this.instances; }

	public static MachineIdentities load(Path path) throws IOException {
		Path file = path.toAbsolutePath().normalize();
		if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
			return new MachineIdentities(file, Map.of());
		return new MachineIdentities(file, read(file));
	}

	public synchronized void setInstance(String username, String instanceId) throws IOException {
		if (!validId(username) || !validId(instanceId))
			throw new IllegalArgumentException("Invalid machine identity mapping");
		Map<String, String> updated = new HashMap<>(this.instances);
		if (updated.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(username) && entry.getValue().equals(instanceId)))
			throw new IllegalArgumentException("Instance identity is already assigned");
		updated.put(username, instanceId);
		updated = validated(updated);
		persist(updated);
		this.instances = updated;
	}

	public synchronized boolean remove(String username) throws IOException {
		if (!this.instances.containsKey(username))
			return false;
		Map<String, String> updated = new HashMap<>(this.instances);
		updated.remove(username);
		persist(updated);
		this.instances = Map.copyOf(updated);
		return true;
	}

	private void persist(Map<String, String> updated) throws IOException {
		if (this.file == null)
			return;
		Path parent = this.file.getParent();
		if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Machine identity directory is unavailable");
		if (Files.exists(this.file, LinkOption.NOFOLLOW_LINKS)) {
			if (!read(this.file).equals(this.instances))
				throw new IOException("Machine identity mapping changed outside the application");
		} else if (!this.instances.isEmpty()) {
			throw new IOException("Machine identity mapping was removed outside the application");
		}
		Path staged = Files.createTempFile(parent, ".machine-identities-", ".tmp");
		try {
			Files.setPosixFilePermissions(staged, FILE_PERMISSIONS);
			String content = new TreeMap<>(updated).entrySet().stream()
					.map(entry -> entry.getKey() + "=" + entry.getValue() + "\n")
					.collect(java.util.stream.Collectors.joining());
			byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
			try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) {
					if (channel.write(buffer) == 0) Thread.yield();
				}
				channel.force(true);
			} finally {
				Arrays.fill(bytes, (byte) 0);
			}
			Files.move(staged, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (UnsupportedOperationException | SecurityException e) {
			throw new IOException("Cannot persist protected machine identity mapping", e);
		} finally {
			Files.deleteIfExists(staged);
		}
	}

	private static Map<String, String> read(Path file) throws IOException {
		try {
			var permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !permissions.equals(FILE_PERMISSIONS))
				throw new IOException("Machine identity mapping must be an owner-only regular file");
			if (Files.size(file) > 65536)
				throw new IOException("Machine identity mapping exceeds limit");
			Map<String, String> instances = new HashMap<>();
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (line.isBlank() || line.startsWith("#"))
					continue;
				String[] fields = line.split("=", -1);
				if (fields.length != 2 || !validId(fields[0]) || !validId(fields[1]) ||
						instances.putIfAbsent(fields[0], fields[1]) != null)
					throw new IOException("Invalid or duplicate machine identity mapping");
			}
			return validated(instances);
		} catch (UnsupportedOperationException | SecurityException | IllegalArgumentException e) {
			throw new IOException("Cannot load protected machine identity mapping", e);
		}
	}

	private static Map<String, String> validated(Map<String, String> instances) {
		Map<String, String> copy = Map.copyOf(instances);
		if (copy.entrySet().stream().anyMatch(entry -> !validId(entry.getKey()) || !validId(entry.getValue())) ||
				new HashSet<>(copy.values()).size() != copy.size())
			throw new IllegalArgumentException("Invalid or ambiguous machine identity mapping");
		return copy;
	}

	public static boolean validId(String value) {
		return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
	}
}
