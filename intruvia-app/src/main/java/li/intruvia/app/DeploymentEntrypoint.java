// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Container lifecycle and serialized offline privilege operations. */
public final class DeploymentEntrypoint {
	private DeploymentEntrypoint() {}

	public static void main(String[] args) {
		try {
			int status = run(args, System.getenv());
			if (status != 0) System.exit(status);
		} catch (Exception e) {
			System.err.println(e instanceof ConfigurationFailure ? e.getMessage() :
					"Container startup failed; preserve the runtime and database and follow recovery instructions");
			System.exit(1);
		}
	}

	static int run(String[] args, Map<String, String> environment) throws Exception {
		if (args.length < 4) throw ProvisioningFiles.failure();
		Path runtime = Path.of(args[0]).toAbsolutePath().normalize();
		Path templates = Path.of(args[1]).toAbsolutePath().normalize();
		int port = Integer.parseInt(args[2]);
		if (port < 0 || port > 65535) throw ProvisioningFiles.failure();
		Path jar = Path.of(DeploymentEntrypoint.class.getProtectionDomain().getCodeSource().getLocation().toURI())
				.toAbsolutePath().normalize();
		if (!Files.isRegularFile(jar)) throw ProvisioningFiles.failure();
		try (var lock = RuntimeLock.acquire(runtime)) {
			if (args[3].equals("serve") && args.length == 4) {
				Path password = Path.of(environment.getOrDefault("INTRUVIA_VIEWER_PASSWORD_FILE", "/run/secrets/viewer-password"));
				FirstStartProvisioner.ensureInitialized(lock, runtime, templates, password,
						environment.get("INTRUVIA_PUBLIC_ORIGIN"), environment, jar);
				IntruviaApplication.main(new String[]{runtime.toString(), Integer.toString(port)});
				return 0;
			}
			if (!args[3].equals("offline")) throw ProvisioningFiles.failure();
			FirstStartProvisioner.validateExisting(lock, runtime);
			List<String> command = Arrays.asList(Arrays.copyOfRange(args, 4, args.length));
			validateOffline(command);
			return PrivilegeToolRunner.offline(jar, runtime.resolve("config"), command);
		}
	}

	private static void validateOffline(List<String> command) throws Exception {
		if (command.isEmpty()) throw ProvisioningFiles.failure();
		int passwordIndex = switch (command.getFirst()) {
			case "hash" -> command.size() == 3 ? 1 : -1;
			case "issue" -> command.size() == 8 ? 2 : -1;
			case "revoke" -> command.size() == 4 ? 2 : -1;
			default -> -1;
		};
		if (passwordIndex < 0) throw ProvisioningFiles.failure();
		Path password = Path.of(command.get(passwordIndex)).toAbsolutePath().normalize();
		RuntimeLock.requireDirectory(password.getParent());
		RuntimeLock.requireFile(password);
	}
}