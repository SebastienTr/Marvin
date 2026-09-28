// SPDX-License-Identifier: MIT
package marvin.host.domain.face;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceState;

/**
 * The animated face (face.py {@code Face}): gaze that follows the person's head through a critically
 * damped spring, micro-saccades, seeded random blinks, eased transitions between expressions, and falling
 * asleep when nobody is there. Time is given by the caller (seconds, monotonic) and all randomness comes
 * from a seeded {@link XorShift32}, so the same seed and the same calls give the same frames. Not
 * thread-safe.
 */
public final class Face {
    public static final int SCREEN_W = 240;
    public static final int SCREEN_H = 280;
    static final double[] SCREEN_POS_MM = {0.0, -38.0, 92.0};

    public static final double[] BACKGROUND = {20, 18, 17};
    static final double[] EYE_WHITE = {236, 230, 218};
    static final double[] EYE_AMBER = {255, 190, 120};
    static final double[] ACCENT = {238, 118, 38};

    static final double EYE_CX = 120.0;
    static final double EYE_CY = 146.0;
    static final double CLOSED_H = 5.0;
    static final double CLOSE_DROP = 0.35;
    static final double PERSPECTIVE = 0.06;
    static final double LID_MARGIN = 3.0;
    static final double[] ACCENT_POS = {120.0, 228.0};
    static final double ACCENT_R = 4.5;

    static final double GAZE_X_PX = 28.0;
    static final double GAZE_Y_PX = 22.0;
    static final double GAZE_YAW_FULL = Math.toRadians(50.0);
    static final double GAZE_PITCH_FULL = Math.toRadians(60.0);
    static final double HEAD_Z_SEATED = 400.0;
    static final double HEAD_Z_STANDING = 950.0;
    static final double FAR_M = 2.2;

    static final double GAZE_OMEGA = 16.0;
    static final double SACCADE_OMEGA = 24.0;
    static final double EXPR_OMEGA = 7.0;
    static final double SLEEP_OMEGA = 1.6;
    static final double WAKE_OMEGA = 9.0;

    static final double[] BLINK_EVERY_S = {2.0, 6.0};
    static final double BLINK_DOUBLE_P = 0.15;
    static final double[] BLINK = {0.07, 0.04, 0.13};
    static final double[] SLOW_BLINK = {0.35, 0.30, 0.55};
    static final double[] MICRO_EVERY_S = {0.8, 2.8};
    static final double MICRO_PX = 3.0;
    static final double[] WANDER_EVERY_S = {1.2, 3.5};
    static final double[] WANDER_HOLD_S = {0.5, 1.4};

    static final double LOOK_AROUND_S = 4.0;
    static final double SLEEPY_AFTER_S = 4.0;
    static final double ASLEEP_AFTER_S = 20.0;
    static final double BREATH_PERIOD_S = 5.5;
    static final double BREATH_DEPTH = 0.25;

    static final double ACCENT_SHOW_S = 5.0;
    static final double ACCENT_FADE_S = 0.4;

    /** An expression held for a while after an event, before going back to the base expression. */
    public record Transient(String expression, double seconds) {
    }

    public static final Map<EventKind, Transient> TRANSIENTS = new EnumMap<>(EventKind.class);

    static {
        TRANSIENTS.put(EventKind.ARRIVED, new Transient("awake", 1.6));
        TRANSIENTS.put(EventKind.APPROACHED, new Transient("surprised", 1.0));
        TRANSIENTS.put(EventKind.SAT_DOWN, new Transient("content", 3.5));
        TRANSIENTS.put(EventKind.STOOD_UP, new Transient("attentive", 2.0));
        TRANSIENTS.put(EventKind.STILL_LONG, new Transient("concerned", 7.0));
    }

    private record Blink(double start, double[] timing) {
    }

    private final XorShift32 rng;
    private final Canvas canvas = new Canvas(SCREEN_W, SCREEN_H, BACKGROUND);
    private final List<EventKind> pending = new ArrayList<>();
    private Double lastT;

    private double[] p = FaceParams.expression("asleep").toArray();
    private double[] pv = new double[p.length];
    private double[] gaze = new double[2];
    private double[] gazeV = new double[2];

    private String expression = "asleep";
    private String transientName;
    private double transientUntil;
    private Double absentSince = Double.NEGATIVE_INFINITY;       // asleep until someone shows up
    private boolean awake;
    private double lastSide;

    private List<Blink> blinks = new ArrayList<>();
    private Double nextBlink;
    private double[] micro = new double[2];
    private double nextMicro;
    private double[] wander = new double[2];
    private double wanderUntil;
    private double nextWander;
    private double accentUntil = Double.NEGATIVE_INFINITY;
    private double accentLevel;
    private double accentT0;

    public Face(long seed) {
        this.rng = new XorShift32(seed);
    }

    public Face() {
        this(0);
    }

    /** The expression the face is heading to (a key of {@link FaceParams#EXPRESSIONS}). */
    public String expression() {
        return expression;
    }

    /** Queues an event; it takes effect at the next {@link #update}. */
    public void onEvent(EventKind kind) {
        pending.add(kind);
    }

    /** Advances the animation to {@code t} (seconds, monotonic) and draws the frame. */
    public Canvas update(PresenceState state, double t) {
        double dt = lastT == null ? 0.0 : Math.min(0.25, Math.max(0.0, t - lastT));
        lastT = t;
        if (nextBlink == null) {
            nextBlink = t + rng.uniform(BLINK_EVERY_S[0], BLINK_EVERY_S[1]);
        }
        trackPresence(state, t);
        for (EventKind ev : pending) {
            handle(ev, state, t);
        }
        pending.clear();

        String name = chooseExpression(state, t);
        expression = name;
        double[] target = FaceParams.expression(name).toArray();
        double omega = EXPR_OMEGA;
        if (name.equals("asleep") || name.equals("sleepy")) {
            omega = SLEEP_OMEGA;
        } else if (p[FaceParams.OPEN] < 0.5) {
            omega = WAKE_OMEGA;
        }
        double[][] s = springStep(p, pv, target, omega, dt);
        p = s[0];
        pv = s[1];

        Target g = gazeTarget(state, t, name);
        double[][] gs = springStep(gaze, gazeV, g.point, g.omega, dt);
        gaze = gs[0];
        gazeV = gs[1];

        double blink = blink(t, name);
        FaceParams params = FaceParams.fromArray(p);
        if (name.equals("asleep")) {
            double breath = 0.5 - 0.5 * Math.cos(2 * Math.PI * t / BREATH_PERIOD_S);
            params = params.withBrightness(params.brightness() * (1 - BREATH_DEPTH * breath));
        }
        return render(params, gaze[0], gaze[1], blink, accent(t, dt, state), canvas);
    }

    // ------------------------------------------------------------------ drawing

    /** Draws one frame from explicit parameters into {@code canvas} (a new one when {@code null}). */
    public static Canvas render(FaceParams params, double gx, double gy, double blink, double accent, Canvas canvas) {
        Canvas cv = canvas != null ? canvas : new Canvas(SCREEN_W, SCREEN_H, BACKGROUND);
        cv.fill(BACKGROUND);
        double[] color = eyeColor(params);
        double lean = Math.max(-1.0, Math.min(1.0, gx / GAZE_X_PX));
        double openness = Math.max(0.0, Math.min(1.0, params.open() * (1.0 - blink)));
        for (double side : new double[] {-1.0, 1.0}) {
            double k = 1.0 + side * PERSPECTIVE * lean;
            double w = params.width() * k;
            double h = params.height() * k;
            double cx = EYE_CX + side * params.spacing() / 2 + gx;
            double hEff = Math.max(CLOSED_H, h * openness);
            double cy = EYE_CY + params.dy() + gy + (h - hEff) * CLOSE_DROP;
            double top = cy - hEff / 2;
            double bottom = cy + hEff / 2;
            cv.fillRoundRect(cx, cy, w, hEff, params.radius() * k, color, 1.0);

            if (params.lidTop() > 1e-3 && hEff > CLOSED_H) {
                double yMid = top + params.lidTop() * hEff;
                double slope = params.lidTilt() * hEff / 2;
                double xIn = cx - side * (w / 2 + LID_MARGIN);
                double xOut = cx + side * (w / 2 + LID_MARGIN);
                double yCap = top - LID_MARGIN;
                double yIn = Math.max(yMid - slope, yCap + 1);
                double yOut = Math.max(yMid + slope, yCap + 1);
                cv.fillConvex(new double[][] {{xIn, yCap}, {xOut, yCap}, {xOut, yOut}, {xIn, yIn}}, BACKGROUND, 1.0);
            }
            if (params.lidBottom() > 1e-3 && hEff > CLOSED_H) {
                double rx = w * 0.56;
                double ry = hEff * 0.5;
                cv.fillEllipse(cx, bottom - params.lidBottom() * hEff + ry, rx, ry, BACKGROUND, 1.0);
            }
        }
        if (accent > 1e-3) {
            cv.fillCircle(ACCENT_POS[0], ACCENT_POS[1], ACCENT_R, ACCENT, Math.min(1.0, accent));
        }
        return cv;
    }

    static double[] eyeColor(FaceParams p) {
        double w = Math.min(1.0, Math.max(0.0, p.warmth()));
        double b = Math.min(1.0, Math.max(0.0, p.brightness()));
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            double bg = BACKGROUND[i];
            out[i] = bg + b * ((1 - w) * EYE_WHITE[i] + w * EYE_AMBER[i] - bg);
        }
        return out;
    }

    /** Exact step of a critically damped spring; stable for any dt. */
    static double[][] springStep(double[] x, double[] v, double[] target, double omega, double dt) {
        double e = Math.exp(-omega * dt);
        double[] nx = new double[x.length];
        double[] nv = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            double x0 = x[i] - target[i];
            double c = v[i] + omega * x0;
            nx[i] = target[i] + (x0 + c * dt) * e;
            nv[i] = (v[i] - omega * c * dt) * e;
        }
        return new double[][] {nx, nv};
    }

    static double smoothstep(double u) {
        u = Math.min(1.0, Math.max(0.0, u));
        return u * u * (3.0 - 2.0 * u);
    }

    static double blinkClosure(double t, double start, double[] timing) {
        double close = timing[0];
        double hold = timing[1];
        double open = timing[2];
        double u = t - start;
        if (u < 0 || u > close + hold + open) {
            return 0.0;
        }
        if (u < close) {
            return smoothstep(u / close);
        }
        if (u < close + hold) {
            return 1.0;
        }
        return 1.0 - smoothstep((u - close - hold) / open);
    }

    /** Eye offset in pixels (screen x right, y down) to look at a device-frame point. */
    static double[] gazeFromPoint(double[] pt) {
        double dx = pt[0] - SCREEN_POS_MM[0];
        double dy = pt[1] - SCREEN_POS_MM[1];
        double dz = pt[2] - SCREEN_POS_MM[2];
        double yaw = Math.atan2(dx, Math.max(-dy, 1.0));
        double pitch = Math.atan2(dz, Math.hypot(dx, dy));
        double gx = Math.max(-1.0, Math.min(1.0, yaw / GAZE_YAW_FULL));
        double gy = Math.max(-1.0, Math.min(1.0, pitch / GAZE_PITCH_FULL));
        return new double[] {GAZE_X_PX * gx, -GAZE_Y_PX * gy};
    }

    // ------------------------------------------------------------------ events and state

    private void trackPresence(PresenceState state, double t) {
        if (state.present()) {
            absentSince = null;
            boolean arriving = pending.contains(EventKind.ARRIVED);
            if (!awake && !arriving) {
                wake(t);
            }
            double[] pt = state.head() != null ? state.head() : state.position();
            if (pt != null) {
                lastSide = Math.abs(pt[0]) > 50 ? Math.copySign(1.0, pt[0]) : 0.0;
            }
        } else if (absentSince == null) {
            absentSince = t;
        }
    }

    private void wake(double t) {
        awake = true;
        transientName = "awake";
        transientUntil = t + TRANSIENTS.get(EventKind.ARRIVED).seconds();
        blinks.add(new Blink(t + 0.7, BLINK));
    }

    private void handle(EventKind kind, PresenceState state, double t) {
        if (kind == EventKind.ARRIVED) {
            wake(t);
        } else if (kind == EventKind.LEFT) {
            if (absentSince == null && !state.present()) {
                absentSince = t;
            }
            transientName = null;
        } else if (TRANSIENTS.containsKey(kind)) {
            Transient tr = TRANSIENTS.get(kind);
            transientName = tr.expression();
            transientUntil = t + tr.seconds();
            if (kind == EventKind.STILL_LONG) {
                blinks.add(new Blink(t + 0.4, SLOW_BLINK));
                blinks.add(new Blink(t + 1.9, SLOW_BLINK));
            } else if (kind == EventKind.APPROACHED) {
                blinks.add(new Blink(t + 0.9, BLINK));
            }
        } else if (kind == EventKind.VITALS_ACQUIRED) {
            accentUntil = t + ACCENT_SHOW_S;
            accentT0 = t;
            blinks.add(new Blink(t + 0.2, SLOW_BLINK));
        } else if (kind == EventKind.VITALS_LOST) {
            accentUntil = Math.min(accentUntil, t);
        }
    }

    private String chooseExpression(PresenceState state, double t) {
        if (absentSince != null) {
            transientName = null;
            double gone = t - absentSince;
            if (gone >= ASLEEP_AFTER_S) {
                awake = false;
                return "asleep";
            }
            if (gone >= SLEEPY_AFTER_S) {
                return "sleepy";
            }
            return "neutral";
        }
        if (transientName != null) {
            if (t < transientUntil) {
                return transientName;
            }
            transientName = null;
        }
        return state.seated() ? "calm" : "neutral";
    }

    // ------------------------------------------------------------------ gaze

    private record Target(double[] point, double omega) {
    }

    private Target gazeTarget(PresenceState state, double t, String name) {
        if (name.equals("asleep")) {
            return new Target(new double[] {0.0, 4.0}, SLEEP_OMEGA);
        }
        if (absentSince != null) {
            return search(t);
        }
        double[] pt = state.head();
        if (pt == null && state.position() != null) {
            double[] pos = state.position();
            double z = state.seated() ? HEAD_Z_SEATED : HEAD_Z_STANDING;
            pt = new double[] {pos[0], pos[1], z};
        }
        boolean far = state.distanceM() != null && state.distanceM() > FAR_M;
        if (pt == null || far) {
            double[] base = pt != null ? gazeFromPoint(pt) : new double[2];
            if (pt != null) {
                base[0] = base[0] * 0.6;
                base[1] = base[1] * 0.6;
            }
            double[] glance = idleGlance(t);
            return new Target(new double[] {base[0] + glance[0], base[1] + glance[1]}, SACCADE_OMEGA);
        }
        double[] g = gazeFromPoint(pt);
        double[] m = microSaccade(t);
        return new Target(new double[] {g[0] + m[0], g[1] + m[1]}, GAZE_OMEGA);
    }

    private double[] microSaccade(double t) {
        if (t >= nextMicro) {
            double x = rng.uniform(-MICRO_PX, MICRO_PX);
            double y = rng.uniform(-MICRO_PX, MICRO_PX) * 0.6;
            micro = new double[] {x, y};
            nextMicro = t + rng.uniform(MICRO_EVERY_S[0], MICRO_EVERY_S[1]);
        }
        return micro;
    }

    private double[] idleGlance(double t) {
        if (t >= nextWander) {
            double x = rng.uniform(-0.8, 0.8) * GAZE_X_PX;
            double y = rng.uniform(-0.4, 0.3) * GAZE_Y_PX;
            wander = new double[] {x, y};
            wanderUntil = t + rng.uniform(WANDER_HOLD_S[0], WANDER_HOLD_S[1]);
            nextWander = t + rng.uniform(WANDER_EVERY_S[0], WANDER_EVERY_S[1]);
        }
        return t < wanderUntil ? wander : new double[2];
    }

    /** After someone left: glance where they went, the other way, then settle down. */
    private Target search(double t) {
        double gone = t - absentSince;
        if (gone >= LOOK_AROUND_S) {
            return new Target(new double[] {0.0, 2.0}, EXPR_OMEGA);
        }
        double side = lastSide != 0.0 ? lastSide : 1.0;
        double[] steps = {side * 0.9, -side * 0.8, side * 0.3};
        int i = (int) Math.min(steps.length - 1, (long) (gone / (LOOK_AROUND_S / steps.length)));
        return new Target(new double[] {steps[i] * GAZE_X_PX, -0.1 * GAZE_Y_PX}, SACCADE_OMEGA);
    }

    // ------------------------------------------------------------------ blinks and accent

    private double blink(double t, String name) {
        if (name.equals("asleep")) {
            blinks.clear();
            nextBlink = t + rng.uniform(BLINK_EVERY_S[0], BLINK_EVERY_S[1]);
            return 0.0;
        }
        if (t >= nextBlink) {
            double[] timing = name.equals("sleepy") ? SLOW_BLINK : BLINK;
            blinks.add(new Blink(t, timing));
            if (rng.uniform(0.0, 1.0) < BLINK_DOUBLE_P) {
                blinks.add(new Blink(t + sum(timing) + 0.08, timing));
            }
            nextBlink = t + rng.uniform(BLINK_EVERY_S[0], BLINK_EVERY_S[1]);
        }
        List<Blink> kept = new ArrayList<>();
        for (Blink b : blinks) {
            if (t <= b.start + sum(b.timing)) {
                kept.add(b);
            }
        }
        blinks = kept;
        double closure = 0.0;
        boolean any = false;
        for (Blink b : blinks) {
            double c = blinkClosure(t, b.start, b.timing);
            closure = any ? Math.max(closure, c) : c;
            any = true;
        }
        return closure;
    }

    private static double sum(double[] timing) {
        return timing[0] + timing[1] + timing[2];
    }

    private double accent(double t, double dt, PresenceState state) {
        double target = t < accentUntil && state.present() ? 1.0 : 0.0;
        accentLevel += (target - accentLevel) * (1.0 - Math.exp(-dt / ACCENT_FADE_S));
        if (accentLevel < 1e-3) {
            return 0.0;
        }
        Double hr = state.heartRate();
        double bpm = hr != null && hr != 0.0 ? hr : 60.0;
        double pulse = 0.5 + 0.5 * Math.cos(2 * Math.PI * (t - accentT0) * bpm / 60.0);
        return accentLevel * (0.45 + 0.55 * pulse);
    }
}
