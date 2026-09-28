// SPDX-License-Identifier: MIT
package marvin.host.domain.settings;

/** An update the settings refuse; the message is for the owner, as the app shows it. */
public final class InvalidSettingException extends IllegalArgumentException {
    public InvalidSettingException(String message) {
        super(message);
    }
}
