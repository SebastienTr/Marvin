// SPDX-License-Identifier: MIT
package marvin.host.domain.system;

import java.util.Objects;

/**
 * How one part of the host is doing: {@code up}, {@code down} (with why), or {@code disabled}.
 *
 * @param state  up, down or disabled
 * @param detail one short line for the app (a version, an error), may be empty
 */
public record ComponentHealth(State state, String detail) {

    /** The state of a component. */
    public enum State { UP, DOWN, DISABLED }

    public ComponentHealth {
        Objects.requireNonNull(state, "state");
        detail = detail == null ? "" : detail;
    }

    public static ComponentHealth up(String detail) {
        return new ComponentHealth(State.UP, detail);
    }

    public static ComponentHealth down(String detail) {
        return new ComponentHealth(State.DOWN, detail);
    }

    public static ComponentHealth disabled(String detail) {
        return new ComponentHealth(State.DISABLED, detail);
    }

    public boolean isUp() {
        return state == State.UP;
    }
}
