// SPDX-License-Identifier: MIT
package marvin.host.application.presence.port.in;

/** The brain threshold the owner sets from the app: how long seated before it is time for a break. */
public interface ConfigurePresence {

    /** Seated this long, seconds: {@code still_long}. */
    double stillLongS();

    void setStillLongS(double seconds);
}
