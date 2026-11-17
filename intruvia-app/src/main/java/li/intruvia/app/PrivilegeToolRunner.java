// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class PrivilegeToolRunner {
	private PrivilegeToolRunner() {}

	static String hash(Path applicationJar, Path config, char[] password, Path workspace) throws Exception {
		Path result = workspace.resolve(".viewer-hash");
		Path log = workspace.resolve(".privilege-tool.log");
		ProvisioningFiles.writeNew(log, new byte[0]);
		var builder = command(applicationJar, config, List.of("hash-stdin", result.toString()));
		builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
		ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
		byte[] input = new byte[encoded.remaining()];
		encoded.get(input);
		try {
			if (ManagedChild.run(builder, input, 60, TimeUnit.SECONDS) != 0) throw ProvisioningFiles.failure();
			RuntimeLock.requireFile(result);
			if (Files.size(result) > 4096) throw ProvisioningFiles.failure();
			String hash = Files.readString(result).strip();
			if (hash.isEmpty()) throw ProvisioningFiles.failure();
			return hash;
		} finally {
			Arrays.fill(input, (byte) 0);
			Files.deleteIfExists(result);
			Files.deleteIfExists(log);
		}
	}

	static int offline(Path applicationJar, Path config, List<String> arguments) throws Exception {
		return ManagedChild.run(command(applicationJar, config, arguments).inheritIO(), 120, TimeUnit.SECONDS);
	}

	private static ProcessBuilder command(Path applicationJar, Path config, List<String> arguments) {
		String classpath = applicationJar + File.pathSeparator + applicationJar.getParent().resolve("lib/*");
		var command = new ArrayList<String>();
		command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		command.add("-cp");
		command.add(classpath);
		command.add("PrivilegeTool");
		command.add(config.toString());
		command.addAll(arguments);
		var builder = new ProcessBuilder(command);
		builder.environment().remove("DB_PASSWORD");
		builder.environment().remove("POSTGRES_PASSWORD");
		return builder;
	}
}