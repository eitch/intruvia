// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

/** Only fixed setting/file names are allowed here: safe to report at the process boundary without a cause. */
class ConfigurationFailure extends IllegalArgumentException {
	private final Throwable diagnosticCause;

	ConfigurationFailure(String message) { this(message, null); }
	ConfigurationFailure(String message, Throwable cause) {
		super(message);
		this.diagnosticCause = cause;
	}

	Throwable diagnosticCause() { return this.diagnosticCause; }
}
