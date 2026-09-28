// SPDX-License-Identifier: MIT
package marvin.host.application.face;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

import marvin.host.application.face.port.in.FaceImage;
import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.domain.face.Canvas;
import marvin.host.domain.face.Face;
import marvin.host.domain.face.FaceParams;
import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.shared.Clocks;

/**
 * Draws the face for the app from the same brain state as the robot's screen (the Python
 * {@code UIServer.face_png}): events are queued as they happen and applied at the next frame; a frame
 * is drawn only when asked for, at most every {@link #MIN_PERIOD_S}.
 */
public final class FaceService implements FaceImage {
    public static final double MIN_PERIOD_S = 1.0 / 15;
    private static final int ICON_TOP = 26;

    private final PresenceQuery presence;
    private final Clocks clocks;
    private final Deque<EventKind> events = new ArrayDeque<>();
    private Face face;
    private byte[] last;
    private double lastT = Double.NEGATIVE_INFINITY;
    private byte[] icon;
    private volatile String expression;

    public FaceService(PresenceQuery presence, Clocks clocks) {
        this.presence = Objects.requireNonNull(presence, "presence");
        this.clocks = Objects.requireNonNull(clocks, "clocks");
    }

    /** A brain event (called on the frame thread): applied at the next frame. */
    public void onPresenceEvent(PresenceEvent event) {
        synchronized (events) {
            events.addLast(event.kind());
            while (events.size() > 64) {
                events.removeFirst();
            }
        }
    }

    @Override
    public int width() {
        return Face.SCREEN_W;
    }

    @Override
    public int height() {
        return Face.SCREEN_H;
    }

    @Override
    public synchronized byte[] frame() {
        double t = clocks.monotonicSeconds();
        if (last != null && t - lastT < MIN_PERIOD_S) {
            return last;
        }
        if (face == null) {
            face = new Face();
        }
        synchronized (events) {
            while (!events.isEmpty()) {
                face.onEvent(events.removeFirst());
            }
        }
        Canvas c = face.update(presence.state(), t);
        expression = face.expression();
        last = c.toRgb888();
        lastT = t;
        return last;
    }

    @Override
    public synchronized byte[] icon() {
        if (icon == null) {
            Canvas c = Face.render(FaceParams.expression("content"), 0, 0, 0, 0, null);
            icon = c.rows(ICON_TOP, ICON_TOP + Face.SCREEN_W);
        }
        return icon;
    }

    @Override
    public String expression() {
        return expression;
    }
}
