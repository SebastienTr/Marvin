// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.tool;

/**
 * An expected failure of a tool; the message is given to the model as is, so it is written for the model:
 * "no place called 'Nicee' was found".
 */
public class ToolError extends RuntimeException {
    public ToolError(String message) {
        super(message);
    }
}
