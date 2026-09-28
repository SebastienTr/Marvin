// SPDX-License-Identifier: MIT
package marvin.host.domain.presence.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import marvin.host.domain.shared.LocalDays;

/** The day statistics, as test_ui.py checks them in the Python host. */
class DayStatsTest {
    static final LocalDate DAY = LocalDate.of(2026, 3, 10);        // a Tuesday, far from DST changes
    static final LocalDays DAYS = new LocalDays(ZoneId.of("Europe/Paris"));

    static double at(String hhmm) {
        return at(hhmm, DAY);
    }

    static double at(String hhmm, LocalDate day) {
        return day.atTime(LocalTime.parse(hhmm)).atZone(DAYS.zone()).toEpochSecond();
    }

    static StoredEvent ev(String kind, String hhmm, Object... data) {
        return ev(kind, hhmm, DAY, data);
    }

    static StoredEvent ev(String kind, String hhmm, LocalDate day, Object... data) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (int i = 0; i < data.length; i += 2) {
            d.put((String) data[i], data[i + 1]);
        }
        return new StoredEvent(at(hhmm, day), kind, "", d);
    }

    static DayStats stats(List<StoredEvent> events, LocalDate day, double now) {
        return DayStats.compute(events, day, DAYS, now, true, List.of());
    }

    @Test
    void aWorkingMorning() {
        List<StoredEvent> events = List.of(ev("arrived", "09:00"), ev("sat_down", "09:05"), ev("stood_up", "10:05"),
                ev("sat_down", "10:05:30"), ev("stood_up", "11:00"), ev("sat_down", "11:10"),
                ev("still_long", "12:00"), ev("vitals_acquired", "11:12"), ev("stood_up", "12:30"),
                ev("left", "12:31"));
        DayStats d = stats(events, DAY, at("20:00"));
        assertThat(d.date()).isEqualTo(DAY);
        assertThat(d.seatedS()).isEqualTo(3600 + 3270 + 4800);
        assertThat(d.presentS()).isEqualTo(3.5 * 3600 + 60);
        assertThat(d.sessions()).isEqualTo(2);
        assertThat(d.breaks()).isEqualTo(1);
        assertThat(d.breakS()).isEqualTo(600);
        assertThat(d.longestS()).isEqualTo(115 * 60);
        assertThat(d.reminders()).isEqualTo(1);
        assertThat(d.timeline().reminders()).containsExactly(at("12:00"));
        assertThat(d.firstArrival()).isEqualTo(at("09:00"));
        assertThat(d.lastDeparture()).isEqualTo(at("12:31"));
        assertThat(d.now()).isEqualTo(at("20:00"));
        assertThat(d.timeline().seated()).hasSize(3);
        assertThat(d.timeline().breaks()).containsExactly(new Interval(at("11:00"), at("11:10")));
    }

    @Test
    void stillHereNow() {
        List<StoredEvent> events = List.of(ev("arrived", "14:00"), ev("sat_down", "14:02"));
        DayStats d = stats(events, DAY, at("15:02"));
        assertThat(d.seatedS()).isEqualTo(3600);
        assertThat(d.presentS()).isEqualTo(3720);
        assertThat(d.lastDeparture()).isNull();
        assertThat(d.now()).isEqualTo(at("15:02"));
        d = DayStats.compute(events, DAY, DAYS, at("15:02"), false, List.of(new Sample(at("14:30"), 1, 1, null, null)));
        assertThat(d.seatedS()).isEqualTo(28 * 60);
        assertThat(d.lastDeparture()).isEqualTo(at("14:30"));
    }

    @Test
    void acrossMidnight() {
        LocalDate next = DAY.plusDays(1);
        List<StoredEvent> events = List.of(ev("arrived", "23:00"), ev("sat_down", "23:30"),
                ev("stood_up", "00:30", next), ev("left", "00:40", next));
        DayStats d1 = stats(events, DAY, at("12:00", next));
        DayStats d2 = stats(events, next, at("12:00", next));
        assertThat(d1.seatedS()).isEqualTo(1800);
        assertThat(d2.seatedS()).isEqualTo(1800);
        assertThat(d1.presentS()).isEqualTo(3600);
        assertThat(d2.presentS()).isEqualTo(2400);
        assertThat(d1.lastDeparture()).isEqualTo(at("00:00", next));
        assertThat(d2.firstArrival()).isEqualTo(at("00:00", next));
        assertThat(d1.sessions()).isEqualTo(1);
        assertThat(d2.sessions()).isEqualTo(1);
    }

    @Test
    void aCrashClosesAtTheLastSignOfLife() {
        List<StoredEvent> events = List.of(ev("arrived", "09:00"), ev("sat_down", "09:05"),
                ev(HistoryKinds.HOST_STARTED, "11:00"), ev("arrived", "11:01"), ev("left", "11:02"));
        List<Sample> samples = List.of(new Sample(at("09:20"), 1, 1, 14.0, 60.0), new Sample(at("09:40"), 1, 1, 16.0, 70.0));
        DayStats d = DayStats.compute(events, DAY, DAYS, at("18:00"), true, samples);
        assertThat(d.seatedS()).isEqualTo(35 * 60);
        assertThat(d.presentS()).isEqualTo(40 * 60 + 60);
        assertThat(d.breathRate()).isEqualTo(15.0);
        assertThat(d.heartRate()).isEqualTo(65.0);
        d = stats(events, DAY, at("18:00"));
        assertThat(d.seatedS()).isZero();
        assertThat(d.presentS()).isEqualTo(5 * 60 + 60);
    }

    @Test
    void cleanStopAndRobotOffline() {
        List<StoredEvent> events = List.of(ev("arrived", "09:00"), ev("sat_down", "09:00"),
                ev(HistoryKinds.ROBOT_OFFLINE, "09:30"),
                ev(HistoryKinds.ROBOT_ONLINE, "10:00", "present", true, "seated", true),
                ev("stood_up", "10:30"), ev(HistoryKinds.HOST_STOPPED, "11:00"), ev(HistoryKinds.HOST_STARTED, "12:00"));
        DayStats d = stats(events, DAY, at("13:00"));
        assertThat(d.seatedS()).isEqualTo(3600);
        assertThat(d.presentS()).isEqualTo(90 * 60);
        assertThat(d.sessions()).isEqualTo(2);
        assertThat(d.breaks()).isEqualTo(1);
    }

    @Test
    void aSeatedPersonIsPresentEvenIfArrivedWasMissed() {
        DayStats d = stats(List.of(ev("sat_down", "09:00"), ev("left", "09:10")), DAY, at("12:00"));
        assertThat(d.seatedS()).isEqualTo(600);
        assertThat(d.presentS()).isEqualTo(600);
    }

    @Test
    void anEmptyDay() {
        DayStats d = stats(new ArrayList<>(), DAY, at("12:00"));
        assertThat(d.seatedS()).isZero();
        assertThat(d.sessions()).isZero();
        assertThat(d.firstArrival()).isNull();
        assertThat(d.breathRate()).isNull();
        assertThat(d.timeline().present()).isEmpty();
    }

    @Test
    void words() {
        assertThat(Words.duration(42)).isEqualTo("42 s");
        assertThat(Words.duration(12 * 60)).isEqualTo("12 min");
        assertThat(Words.duration(65 * 60)).isEqualTo("1 h 05");
        assertThat(Words.duration(3 * 3600)).isEqualTo("3 h");
        assertThat(new StoredEvent(0, "stood_up", "", Map.of("seated_s", 2520)).text()).isEqualTo("You stood up after 42 min");
        assertThat(new StoredEvent(0, "still_long", "", Map.of("seated_s", 3000)).text())
                .isEqualTo("Time for a break: seated for 50 min");
        Map<String, Object> vitals = new LinkedHashMap<>();
        vitals.put("breath_rate", 14.2);
        vitals.put("heart_rate", 68);
        assertThat(new StoredEvent(0, "vitals_acquired", "", vitals).text()).isEqualTo("Breathing 14/min, heart 68/min");
        assertThat(new StoredEvent(0, "left", "sensor restarted", null).text()).startsWith("Marvin lost track");
        assertThat(new StoredEvent(0, "something_new", "", null).text()).isEqualTo("Something new");
        assertThat(Words.duration(42.5)).isEqualTo("42 s");                // half to even, as Python

        Words.Status st = Words.status(true, true, true, 42 * 60, null, 14.0, 68.0, 3000, true);
        assertThat(st.text()).isEqualTo("You've been at your desk for 42 min");
        assertThat(st.detail()).isEqualTo("Breathing 14/min, heart 68/min");
        assertThat(Words.status(true, true, true, 55 * 60, null, null, null, 3000, true).mood()).isEqualTo("break");
        assertThat(Words.status(true, false, false, 0, 600.0, null, null, 3000, true).text())
                .isEqualTo("Marvin is asleep. Nobody around.");
        assertThat(Words.status(false, false, false, 0, null, null, null, 3000, false).mood()).isEqualTo("offline");
    }
}
