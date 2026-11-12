// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

final class ManagedChild {
	private ManagedChild() {}

	static int run(ProcessBuilder builder, long timeout, TimeUnit unit) throws IOException, InterruptedException {
		var owner = new Owner();
		var hook = new Thread(owner::shutdown, "intruvia-privilege-child-shutdown");
		boolean interrupted = false;
		Runtime.getRuntime().addShutdownHook(hook);
		try {
			Process process = owner.start(builder);
			if (!process.waitFor(timeout, unit)) throw ProvisioningFiles.failure();
			return process.exitValue();
		} catch (InterruptedException e) {
			interrupted = true;
			throw e;
		} finally {
			interrupted = owner.terminate(interrupted);
			try {
				Runtime.getRuntime().removeShutdownHook(hook);
			} catch (IllegalStateException ignored) {
				// The shutdown hook is responsible for terminating the child.
			}
			if (interrupted) Thread.currentThread().interrupt();
		}
	}

	private static final class Owner {
		private Process process;
		private boolean shuttingDown;

		synchronized Process start(ProcessBuilder builder) throws IOException {
			if (this.shuttingDown) throw ProvisioningFiles.failure();
			this.process = builder.start();
			return this.process;
		}

		void shutdown() {
			synchronized (this) { this.shuttingDown = true; }
			if (terminate(false)) Thread.currentThread().interrupt();
		}

		boolean terminate(boolean alreadyInterrupted) {
			Process child;
			synchronized (this) { child = this.process; }
			boolean interrupted = Thread.interrupted() || alreadyInterrupted;
			if (child == null || !child.isAlive()) return interrupted;
			child.destroy();
			try {
				if (!child.waitFor(30, TimeUnit.SECONDS)) {
					child.destroyForcibly();
					interrupted |= awaitExit(child);
				}
			} catch (InterruptedException e) {
				interrupted = true;
				child.destroyForcibly();
				interrupted |= awaitExit(child);
			}
			return interrupted;
		}

		private boolean awaitExit(Process child) {
			boolean interrupted = false;
			while (child.isAlive()) {
				try { child.waitFor(); } catch (InterruptedException e) { interrupted = true; }
			}
			return interrupted;
		}
	}
}