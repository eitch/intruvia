// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

/** Only fixed setting/file names are allowed here: safe to report at the process boundary without a cause. */
final class ConfigurationFailure extends IllegalArgumentException {
	ConfigurationFailure(String message) { super(message); }
}
