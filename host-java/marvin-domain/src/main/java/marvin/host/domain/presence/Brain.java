// SPDX-License-Identifier: MIT
package marvin.host.domain.presence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;

/**
 * The brain: turns LD2450 targets and MR60BHA2 readings into events and a live picture of the person
 * in front of the robot. A faithful port of the Python host's {@code brain.py}: it follows the nearest
 * person, decides whether they are present, close, seated, still, and whether the vital signs can be
 * trusted, with every decision debounced or with hysteresis.
 *
 * <p>Time comes only from the frames' device clock, never from the wall clock, so a recording replays
 * to exactly the same events. If the device clock jumps back more than {@code clockResetS} (the robot
 * rebooted), the brain starts over; a frame slightly out of order is dropped.
 *
 * <p>A PIR motion sensor is real evidence when the radar is not ({@link #onMotion}): with no live radar, motion is
 * presence at once ({@code arrived}) and no motion for {@code motionHoldS} is {@code left}. It senses movement, not
 * where anyone is: no position, no sitting; {@link PresenceState#motionPresence()} says so. Next to a live radar the
 * PIR is only noted: the radar knows better.
 *
 * <p>Posture hysteresis (Java only, off in {@link BrainConfig#PYTHON}): while seated, the position compared with the
 * seat is a slower average ({@code seatTauS}); a stand-up must hold for {@code standConfirmS}; a new sit-down waits
 * {@code resitAfterS} after a stand-up; a break in stillness shorter than {@code stillGraceS} does not restart it. A real LD2450 close to a seated person jitters enough to flap otherwise.
 *
 * <p>One robot: frames from several devices share one state. The lidar is not used (see brain.py).
 * Not thread-safe: the application service serializes the calls.
 */
public final class Brain {
    private BrainConfig c;
    private final Deque<PresenceEvent> events = new ArrayDeque<>();
    private List<PresenceEvent> emitted = new ArrayList<>();

    // the state being built (PresenceState fields)
    private long tUs;
    private boolean present;
    private boolean seated;
    private double[] position;
    private double[] head;
    private Double distanceM;
    private double speedCms;
    private double stillS;
    private double seatedS;
    private Double breathRate;
    private Double heartRate;
    private boolean vitalsSensor;
    private boolean simulated;
    private int targets;
    private boolean motionPresence;

    // internals
    private Long lastTUs;
    private Double lastTargetsT;
    private Double seenSince;
    private Double lastSeen;
    private double[] pos;
    private double speed;
    private final Deque<double[]> window = new ArrayDeque<>();     // {t, x, y}
    private boolean still;
    private Double stillSince;
    /** When the current break in stillness began ({@code null}: still). */
    private Double notStillSince;
    /** Where the person was when this stillness began: a break must end there to be a blip. */
    private double[] stillAnchor;
    private Double fastSince;
    private boolean approachArmed;
    private double[] seat;
    private double seatDistance;
    private double seatedSince;
    /** While seated: the slow average of the position compared with the seat, and when it was last updated. */
    private double[] seatPos;
    private double seatPosT;
    /** When the stand-up condition started holding ({@code null}: it does not). */
    private Double standSince;
    /** When the last {@code stood_up} was. */
    private Double stoodAt;
    private boolean stillLongSent;
    private boolean vitalsOk;
    private Double vitalsGoodSince;
    private Double vitalsBadSince;
    private Double lastMotion;

    public Brain(BrainConfig config) {
        this.c = Objects.requireNonNull(config, "config");
        reset(0);
    }

    public Brain() {
        this(BrainConfig.DEFAULT);
    }

    /**
     * Changes the thresholds from the next frame on (the app's break interval changes
     * {@code stillLongS}); the state is kept.
     */
    public void reconfigure(BrainConfig config) {
        this.c = Objects.requireNonNull(config, "config");
    }

    public BrainConfig config() {
        return c;
    }

    /** The current presence state. */
    public PresenceState state() {
        return new PresenceState(tUs, present, seated, position, head, distanceM, speedCms, stillS, seatedS,
                breathRate, heartRate, vitalsSensor, simulated, targets, motionPresence);
    }

    /** The last {@code maxEvents} events, oldest first. */
    public List<PresenceEvent> events() {
        return List.copyOf(events);
    }

    // ------------------------------------------------------------ inputs

    /**
     * One LD2450 frame.
     *
     * @param sim     the device said its sensor data is simulated
     * @param sighted the people seen, in the device frame (may be empty)
     * @return the events it caused, in order
     */
    public List<PresenceEvent> onTargets(long frameTUs, boolean sim, List<TargetSighting> sighted) {
        emitted = new ArrayList<>();
        if (!clock(frameTUs)) {
            return List.of();
        }
        simulated = sim;
        motionPresence = false;                     // the radar is the presence
        double t = frameTUs / 1e6;
        double dt = lastTargetsT == null ? 0.0 : Math.max(0.0, t - lastTargetsT);
        lastTargetsT = t;
        targets = sighted.size();
        if (!sighted.isEmpty()) {
            int best = 0;
            for (int k = 1; k < sighted.size(); k++) {
                if (Math.hypot(sighted.get(k).x(), sighted.get(k).y())
                        < Math.hypot(sighted.get(best).x(), sighted.get(best).y())) {
                    best = k;
                }
            }
            track(t, dt, sighted.get(best));
        } else {
            seenSince = null;
        }
        boolean left = presence(t);
        if (present || left) {             // as brain.py: after a reset it still looks at the old state
            approach();
            sitting(t);
        }
        updateHead();
        return List.copyOf(emitted);
    }

    /**
     * One MR60BHA2 reading.
     *
     * @return the events it caused, in order
     */
    public List<PresenceEvent> onVitals(long frameTUs, boolean sim, VitalsReading v) {
        emitted = new ArrayList<>();
        if (!clock(frameTUs)) {
            return List.of();
        }
        double t = frameTUs / 1e6;
        vitalsSensor = true;
        simulated = simulated || sim;
        boolean good = v.valid() && present && still
                && c.breathMin() <= v.breathRate() && v.breathRate() <= c.breathMax()
                && c.heartMin() <= v.heartRate() && v.heartRate() <= c.heartMax();
        if (good) {
            vitalsBadSince = null;
            if (vitalsGoodSince == null) {
                vitalsGoodSince = t;
            }
            if (vitalsOk) {
                breathRate = v.breathRate();
                heartRate = v.heartRate();
            } else if (t - vitalsGoodSince >= c.vitalsAcquireS()) {
                vitalsOk = true;
                breathRate = v.breathRate();
                heartRate = v.heartRate();
                emit(EventKind.VITALS_ACQUIRED, frameTUs,
                        "breath " + fmt(breathRate, 0) + "/min, heart " + fmt(heartRate, 0) + "/min",
                        "breath_rate", breathRate, "heart_rate", heartRate);
            }
        } else {
            vitalsGoodSince = null;
            if (vitalsBadSince == null) {
                vitalsBadSince = t;
            }
            if (vitalsOk && t - vitalsBadSince >= c.vitalsLoseS()) {
                loseVitals(frameTUs, present && !still ? "movement" : "no valid reading");
            }
        }
        return List.copyOf(emitted);
    }

    /**
     * One PIR reading (the room sensors' {@code ENVIRONMENT}): motion now or not. Nothing is known while it warms up,
     * but the clock still moves.
     *
     * @param sim the readings are simulated
     * @return the events it caused, in order
     */
    public List<PresenceEvent> onMotion(long frameTUs, boolean sim, boolean motion, boolean warmingUp) {
        emitted = new ArrayList<>();
        if (!clock(frameTUs)) {
            return List.of();
        }
        double t = frameTUs / 1e6;
        if (lastTargetsT != null && t - lastTargetsT < c.leaveAfterS()) {
            if (motion && !warmingUp) {
                lastMotion = t;
            }
            return List.copyOf(emitted);            // a live radar: it knows better
        }
        motionPresence = true;
        simulated = sim;
        if (warmingUp) {
            return List.copyOf(emitted);
        }
        if (motion) {
            lastMotion = t;
        }
        if (!present) {
            if (motion) {
                present = true;
                stillS = 0.0;
                emit(EventKind.ARRIVED, frameTUs, "movement on the motion sensor");
            }
            return List.copyOf(emitted);
        }
        if (lastMotion == null || t - lastMotion >= c.motionHoldS()) {
            if (vitalsOk) {
                loseVitals(frameTUs, "person left");
            }
            emit(EventKind.LEFT, frameTUs, "no movement for " + fmt(c.motionHoldS(), 0) + " s");
            reset(frameTUs);                        // forget everything about them, keep the clock
            lastTUs = frameTUs;
            return List.copyOf(emitted);
        }
        stillS = t - lastMotion;
        return List.copyOf(emitted);
    }

    // ------------------------------------------------------------ internals

    private void reset(long at) {
        tUs = at;
        present = false;
        seated = false;
        position = null;
        head = null;
        distanceM = null;
        speedCms = 0.0;
        stillS = 0.0;
        seatedS = 0.0;
        breathRate = null;
        heartRate = null;
        targets = 0;        // vitalsSensor, simulated and motionPresence are kept
        lastTUs = null;
        lastTargetsT = null;
        seenSince = null;
        lastSeen = null;
        pos = null;
        speed = 0.0;
        window.clear();
        still = false;
        stillSince = null;
        notStillSince = null;
        stillAnchor = null;
        fastSince = null;
        approachArmed = true;
        seat = null;
        seatDistance = 0.0;
        seatedSince = 0.0;
        seatPos = null;
        seatPosT = 0.0;
        standSince = null;
        stoodAt = null;
        stillLongSent = false;
        vitalsOk = false;
        vitalsGoodSince = null;
        vitalsBadSince = null;
        lastMotion = null;
    }

    /** Checks the device clock; false for a frame to drop. */
    private boolean clock(long frameTUs) {
        Long last = lastTUs;
        if (last != null && frameTUs < last) {
            if (last - frameTUs < c.clockResetS() * 1e6) {
                return false;                       // slightly out of order
            }
            if (vitalsOk) {
                loseVitals(frameTUs, "sensor restarted");
            }
            if (present) {
                emit(EventKind.LEFT, frameTUs, "sensor restarted");
            }
            reset(frameTUs);
        }
        lastTUs = frameTUs;
        tUs = frameTUs;
        return true;
    }

    private void track(double t, double dt, TargetSighting target) {
        double x = target.x();
        double y = target.y();
        double z = target.z();
        boolean gap = lastSeen == null || t - lastSeen > c.stillWindowS();
        if (pos == null || gap) {                   // first sighting, or back after a gap: no smoothing across it
            pos = new double[] {x, y, z};
            speed = c.radarSpeedSign() * target.speedCms();
            window.clear();
        } else {
            double a = alpha(dt, c.positionTauS());
            pos = new double[] {pos[0] + a * (x - pos[0]), pos[1] + a * (y - pos[1]), pos[2] + a * (z - pos[2])};
            speed += alpha(dt, c.speedTauS()) * (c.radarSpeedSign() * target.speedCms() - speed);
        }
        if (seenSince == null) {
            seenSince = t;
        }
        lastSeen = t;

        window.addLast(new double[] {t, x, y});
        while (window.peekFirst()[0] < t - c.stillWindowS()) {
            window.removeFirst();
        }
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double[] w : window) {
            minX = Math.min(minX, w[1]);
            maxX = Math.max(maxX, w[1]);
            minY = Math.min(minY, w[2]);
            maxY = Math.max(maxY, w[2]);
        }
        double spread = Math.hypot(maxX - minX, maxY - minY);
        still = spread < c.stillMoveMm() && Math.abs(speed) < c.stillSpeedCms();
        if (still) {
            if (notStillSince != null && stillAnchor != null && c.stillGraceS() > 0
                    && Math.hypot(pos[0] - stillAnchor[0], pos[1] - stillAnchor[1]) > c.stillMoveMm()) {
                stillSince = null;                  // still again, but somewhere else: they moved
            }
            notStillSince = null;
            if (stillSince == null) {
                stillSince = t;
                stillAnchor = pos.clone();
            }
        } else {
            if (notStillSince == null) {
                notStillSince = t;
            }
            if (stillSince == null || t - notStillSince >= c.stillGraceS()) {
                stillSince = t;                     // a real movement (brain.py: any break)
                stillAnchor = pos.clone();
            }
        }
        if (Math.abs(speed) < c.standSpeedCms()) {
            fastSince = null;
        } else if (fastSince == null) {
            fastSince = t;
        }
        position = pos.clone();
        distanceM = Math.hypot(pos[0], pos[1]) / 1000;
        speedCms = speed;
        // in a break shorter than the grace, the stillness so far is kept but does not grow
        stillS = (notStillSince != null && c.stillGraceS() > 0 ? notStillSince : t) - stillSince;
    }

    /** Returns true when the person left and the state was reset. */
    private boolean presence(double t) {
        if (!present) {
            if (seenSince != null && t - seenSince >= c.arriveConfirmS()) {
                present = true;
                approachArmed = true;
                emit(EventKind.ARRIVED, tUs, fmt(distanceM, 2) + " m away", "distance_m", distanceM);
            }
        } else if (lastSeen == null || t - lastSeen >= c.leaveAfterS()) {
            if (vitalsOk) {
                loseVitals(tUs, "person left");
            }
            emit(EventKind.LEFT, tUs, "not seen for " + fmt(c.leaveAfterS(), 0) + " s");
            long keepT = tUs;
            int keepTargets = targets;
            reset(keepT);                           // forget everything about them, keep the clock
            lastTUs = keepT;
            lastTargetsT = t;
            targets = keepTargets;
            return true;
        }
        return false;
    }

    private void approach() {
        if (distanceM == null) {
            return;
        }
        if (approachArmed && distanceM < c.approachM()) {
            approachArmed = false;
            emit(EventKind.APPROACHED, tUs, fmt(distanceM, 2) + " m away", "distance_m", distanceM);
        } else if (distanceM > c.approachRearmM()) {
            approachArmed = true;
        }
    }

    private void sitting(double t) {
        if (!seated) {
            seatedS = 0.0;
            if (distanceM != null && distanceM <= c.sitMaxM() && stillS >= c.sitStillS()
                    && (stoodAt == null || t - stoodAt >= c.resitAfterS())
                    && (c.stillGraceS() <= 0 || notStillSince == null)) {     // with the grace: still now, too
                seated = true;
                seat = position;
                seatDistance = distanceM;
                seatedSince = t;
                seatPos = position.clone();
                seatPosT = t;
                standSince = null;
                stillLongSent = false;
                emit(EventKind.SAT_DOWN, tUs, fmt(distanceM, 2) + " m away", "distance_m", distanceM);
            }
            return;
        }
        seatedS = t - seatedSince;
        double[] at = position;
        double away = distanceM;
        if (c.seatTauS() > 0) {
            double a = alpha(t - seatPosT, c.seatTauS());
            seatPos = new double[] {seatPos[0] + a * (position[0] - seatPos[0]), seatPos[1] + a * (position[1] - seatPos[1]),
                seatPos[2] + a * (position[2] - seatPos[2])};
            seatPosT = t;
            at = seatPos;
            away = Math.hypot(seatPos[0], seatPos[1]) / 1000;
        }
        double moved = Math.hypot(at[0] - seat[0], at[1] - seat[1]);
        boolean fast = fastSince != null && t - fastSince >= c.standSpeedS();
        boolean standing = moved > c.standMoveMm() || away - seatDistance > c.standAwayM() || fast;
        if (!standing) {
            standSince = null;
        } else if (standSince == null) {
            standSince = t;
        }
        if (standing && t - standSince >= c.standConfirmS()) {
            seated = false;
            standSince = null;
            stoodAt = t;
            emit(EventKind.STOOD_UP, tUs, "after " + duration(seatedS) + " seated", "seated_s", seatedS);
            seatedS = 0.0;
            stillSince = t;                         // standing up is movement
            notStillSince = t;
            stillAnchor = pos.clone();
            stillS = 0.0;
            if (vitalsOk) {
                loseVitals(tUs, "movement");
            }
        } else if (!stillLongSent && seatedS >= c.stillLongS()) {
            stillLongSent = true;
            emit(EventKind.STILL_LONG, tUs, "seated for " + duration(seatedS), "seated_s", seatedS);
        }
    }

    private void updateHead() {
        if (present && position != null) {
            head = new double[] {position[0], position[1], seated ? c.headZSeatedMm() : c.headZStandingMm()};
        } else {
            head = null;
        }
    }

    private void loseVitals(long at, String why) {
        vitalsOk = false;
        vitalsGoodSince = null;
        breathRate = null;
        heartRate = null;
        emit(EventKind.VITALS_LOST, at, why);
    }

    private void emit(EventKind kind, long at, String detail, Object... data) {
        Map<String, Double> d = new LinkedHashMap<>();
        for (int i = 0; i + 1 < data.length; i += 2) {
            d.put((String) data[i], (Double) data[i + 1]);
        }
        PresenceEvent ev = new PresenceEvent(kind, at, detail, d);
        events.addLast(ev);
        while (events.size() > c.maxEvents()) {
            events.removeFirst();
        }
        emitted.add(ev);
    }

    /** Weight of an exponential moving average for a sample {@code dt} seconds after the previous one. */
    static double alpha(double dt, double tau) {
        return tau > 0 ? 1.0 - Math.exp(-dt / tau) : 1.0;
    }

    static String duration(double s) {
        if (s < 90) {
            return fmt(s, 0) + " s";
        }
        if (s < 90 * 60) {
            return fmt(s / 60, 0) + " min";
        }
        return fmt(s / 3600, 1) + " h";
    }

    /** Python's {@code f"{v:.Nf}"}: the exact binary value rounded half to even. */
    static String fmt(double v, int decimals) {
        String s = new BigDecimal(v).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString();
        return v < 0 && s.matches("-?0(\\.0*)?") ? "-" + s.replace("-", "") : s;
    }
}
