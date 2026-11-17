// SPDX-License-Identifier: AGPL-3.0-only

import li.strolch.privilege.helper.PrivilegeInitializer;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Offline operator recipe: stop Intruvia first, protect inputs and run as its service user.
 */
public class PrivilegeTool {

	static void main(String[] args) throws Exception {
		java.util.logging.LogManager.getLogManager().reset();
		var quiet = PrivilegeTool.class.getResource("/li/intruvia/app/quiet-logback.xml");
		System.setProperty("logback.configurationFile",
				quiet == null ? "docs/deployment/quiet-logback.xml" : quiet.toExternalForm());
		if (args.length < 3)
			throw new IllegalArgumentException("Invalid privilege operation");
		String mode = args[1];
		if (!(mode.equals("hash") && args.length == 4 || mode.equals("hash-stdin") && args.length == 3 ||
				mode.equals("issue") && args.length == 9 || mode.equals("revoke") && args.length == 5))
			throw new IllegalArgumentException("Invalid privilege operation");
		var scheduler = Executors.newSingleThreadScheduledExecutor();
		char[] password = null;
		boolean failed = false;
		try {
			var handler = new PrivilegeInitializer(false, scheduler).initializeFromXml(
					Path.of(args[0], "PrivilegeConfig.xml").toFile());
			if (mode.equals("hash-stdin")) {
				password = readPassword();
				var encryption = handler.getEncryptionHandler();
				writeNew(Path.of(args[2]), encryption.hashPassword(password, encryption.nextSalt()).toString());
			} else {
				Path input = Path.of(args[mode.equals("hash") ? 2 : 3]);
				if (Files.isSymbolicLink(input)
						|| !Files.isRegularFile(input)
						|| Files.size(input) > 4096
						|| !PosixFilePermissions.toString(Files.getPosixFilePermissions(input)).matches("r[w-]-------"))
					throw new IllegalArgumentException("Password input must be an owner-only regular file");
				password = Files.readString(input).stripTrailing().toCharArray();
			if (password.length == 0)
				throw new IllegalArgumentException("Empty password");
			if (mode.equals("hash") && args.length == 4) {
				var encryption = handler.getEncryptionHandler();
				writeNew(Path.of(args[3]), encryption.hashPassword(password, encryption.nextSalt()).toString());
			} else {
				var certificate = handler.authenticate(args[2], password, false);
				try {
					if (mode.equals("issue") && args.length == 9) {
						Path output = Path.of(args[8]);
						if (Files.exists(output))
							throw new IllegalArgumentException("Output already exists");
						var from = ZonedDateTime.parse(args[6]);
						var to = ZonedDateTime.parse(args[7]);
						if (!to.isAfter(from))
							throw new IllegalArgumentException("Invalid validity window");
						String token = handler.createPersonalAccessToken(certificate, args[4], args[5], from, to,
								Set.of(), Set.of("event:ingest"));
						handler.persist(certificate);
						writeNew(output, token);
					} else if (mode.equals("revoke") && args.length == 5) {
						handler.removePersonalAccessToken(certificate, args[4]);
						handler.persist(certificate);
					} else
						throw new IllegalArgumentException("Invalid arguments");
				} finally {
					handler.invalidate(certificate);
				}
			}
			}
		} catch (Exception e) {
			// Framework exceptions can contain sensitive configuration; keep the operator boundary fixed.
			System.err.println("Privilege operation failed; check protected inputs, permissions and validity");
			failed = true;
		} finally {
			if (password != null)
				Arrays.fill(password, '\0');
			scheduler.shutdownNow();
		}
		if (failed)
			System.exit(1);
	}

	private static char[] readPassword() throws Exception {
		byte[] bytes = System.in.readNBytes(4097);
		CharBuffer chars = null;
		try {
			if (bytes.length == 0 || bytes.length > 4096)
				throw new IllegalArgumentException("Invalid password input");
			chars = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
			if (!chars.hasRemaining()) throw new IllegalArgumentException("Empty password");
			char[] password = new char[chars.remaining()];
			chars.get(password);
			return password;
		} finally {
			Arrays.fill(bytes, (byte) 0);
			if (chars != null && chars.hasArray()) Arrays.fill(chars.array(), '\0');
		}
	}

	private static void writeNew(Path path, String value) throws java.io.IOException {
		try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
			var data = java.nio.ByteBuffer.wrap((value + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
			while (data.hasRemaining())
				channel.write(data);
		}
	}
}
