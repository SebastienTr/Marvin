// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import java.net.ConnectException;

/** Network failures in the words people know ("Connection refused"), never a Java class name when a reason exists. */
final class NetErrors {

    private NetErrors() {
    }

    /**
     * Why {@code e} happened: "Connection refused" when a {@link ConnectException} is anywhere in its chain (the
     * JDK client often wraps a message-less {@code ClosedChannelException} in one), else the deepest message.
     */
    static String reason(Throwable e) {
        String deepest = null;
        Throwable last = e;
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ConnectException) {
                return "Connection refused";
            }
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                deepest = t.getMessage();
            }
            last = t;
        }
        return deepest != null ? deepest : last.getClass().getSimpleName();
    }
}
