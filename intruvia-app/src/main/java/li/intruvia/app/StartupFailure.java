// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

final class StartupFailure extends ConfigurationFailure {
	private final String stage;
	private boolean logged;

	StartupFailure(String stage, Throwable cause) {
		super(safeMessage(stage, cause), cause);
		this.stage = stage;
	}

	String stage() {
		return this.stage;
	}

	synchronized boolean markLogged() {
		if (this.logged) return false;
		this.logged = true;
		return true;
	}

	private static String safeMessage(String stage, Throwable cause) {
		String message = null;
		Throwable current = cause;
		for (int depth = 0; current != null && depth < 16; depth++, current = nextCause(current)) {
			if (current instanceof ConfigurationFailure && current.getMessage() != null)
				message = current.getMessage();
		}
		return message != null ? message : "Startup failed during " + stage + "; see the protected application startup log";
	}

	static Throwable nextCause(Throwable failure) {
		return failure instanceof ConfigurationFailure configuration ? configuration.diagnosticCause() : failure.getCause();
	}
}
