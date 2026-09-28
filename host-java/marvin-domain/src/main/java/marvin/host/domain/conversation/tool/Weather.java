// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.tool;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import marvin.host.domain.shared.PyNumbers;

/**
 * The {@code get_weather} tool's words and numbers (the Python host's {@code tools/weather.py}): its schema
 * and description, WMO weather codes in plain English, which geocoding result to use, and the small result
 * the model phrases in one or two spoken sentences. The requests themselves are the application's.
 */
public final class Weather {
    public static final String NAME = "get_weather";
    public static final String GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search";
    public static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    /** A forecast is reused this long for the same place. */
    public static final double CACHE_S = 600.0;
    /** Both requests together. */
    public static final double BUDGET_S = 4.0;
    public static final int MAX_PLACE = 80;
    public static final List<String> LANGUAGES = List.of("fr", "en", "de", "es", "it", "nl", "pt");

    public static final String DESCRIPTION = "Get the real weather for a place: the current conditions, or today's or "
            + "tomorrow's forecast. Use it whenever the person asks about the weather, temperature, rain or wind. When they "
            + "name no place, call it without one: it then uses the owner's home, or says which city to ask for.";

    static final Map<Integer, String> WMO = Map.ofEntries(
            Map.entry(0, "clear sky"), Map.entry(1, "mainly clear"), Map.entry(2, "partly cloudy"), Map.entry(3, "overcast"),
            Map.entry(45, "fog"), Map.entry(48, "freezing fog"),
            Map.entry(51, "light drizzle"), Map.entry(53, "drizzle"), Map.entry(55, "heavy drizzle"),
            Map.entry(56, "light freezing drizzle"), Map.entry(57, "freezing drizzle"),
            Map.entry(61, "light rain"), Map.entry(63, "rain"), Map.entry(65, "heavy rain"),
            Map.entry(66, "light freezing rain"), Map.entry(67, "freezing rain"),
            Map.entry(71, "light snow"), Map.entry(73, "snow"), Map.entry(75, "heavy snow"), Map.entry(77, "snow grains"),
            Map.entry(80, "light rain showers"), Map.entry(81, "rain showers"), Map.entry(82, "violent rain showers"),
            Map.entry(85, "light snow showers"), Map.entry(86, "heavy snow showers"),
            Map.entry(95, "thunderstorm"), Map.entry(96, "thunderstorm with light hail"),
            Map.entry(99, "thunderstorm with heavy hail"));

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE dd MMMM", Locale.ENGLISH);

    private Weather() {
    }

    /** The tool's parameters schema. */
    public static Map<String, Object> parameters() {
        Map<String, Object> place = new LinkedHashMap<>();
        place.put("type", "string");
        place.put("maxLength", (long) MAX_PLACE);
        place.put("description", "City or town, e.g. \"Nice\" or \"Lyon\". Leave it out for the owner's home.");
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("type", "string");
        day.put("enum", List.of("now", "today", "tomorrow"));
        day.put("description", "\"now\" for the current conditions (default), \"today\" or \"tomorrow\" for the day's forecast.");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("place", place);
        props.put("day", day);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "object");
        p.put("properties", props);
        p.put("required", List.of());
        return p;
    }

    public static ToolSpec spec() {
        return new ToolSpec(NAME, DESCRIPTION, parameters(), BUDGET_S + 1.0, true, true, null);
    }

    /** A WMO code in words. */
    public static String conditions(Object code) {
        Integer c = null;
        if (code instanceof Number n) {
            c = (int) n.doubleValue();
        } else if (code instanceof String s) {
            try {
                c = Integer.parseInt(s.strip());
            } catch (NumberFormatException e) {
                return "unknown";
            }
        }
        if (c == null) {
            return "unknown";
        }
        String w = WMO.get(c);
        return w != null ? w : "weather code " + (code instanceof Double d ? marvin.host.domain.shared.JsonText.repr(d) : code);
    }

    /** A place the geocoder found. */
    public record Place(String name, double latitude, double longitude) {
    }

    /** The geocoding request's parameters for {@code name} ("Paris, France": the name, and more results to choose from). */
    public static Map<String, Object> geocodingQuery(String name, String language) {
        int comma = name.indexOf(',');
        String head = comma < 0 ? name : name.substring(0, comma);
        String qualifier = comma < 0 ? "" : name.substring(comma + 1);
        String query = head.strip().isEmpty() ? name : head.strip();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", query);
        p.put("count", qualifier.isEmpty() ? 1 : 5);
        p.put("language", language);
        p.put("format", "json");
        return p;
    }

    /** Which result to use: the one matching the qualifier after a comma (country, code, region), else the first. */
    public static Place pickPlace(String name, Map<?, ?> geocoding) {
        int comma = name.indexOf(',');
        String head = comma < 0 ? name : name.substring(0, comma);
        String q = comma < 0 ? "" : name.substring(comma + 1).strip().toLowerCase(Locale.ROOT);
        String query = head.strip().isEmpty() ? name : head.strip();
        List<?> results = geocoding.get("results") instanceof List<?> l ? l : List.of();
        Map<?, ?> pick = null;
        if (!q.isEmpty()) {
            for (Object o : results) {
                if (o instanceof Map<?, ?> r && (q.equals(low(r.get("country"))) || q.equals(low(r.get("country_code")))
                        || q.equals(low(r.get("admin1"))))) {
                    pick = r;
                    break;
                }
            }
        }
        if (pick == null && !results.isEmpty() && results.get(0) instanceof Map<?, ?> first) {
            pick = first;
        }
        if (pick == null || !(pick.get("latitude") instanceof Number lat) || !(pick.get("longitude") instanceof Number lon)) {
            return null;
        }
        StringBuilder label = new StringBuilder();
        for (Object part : new Object[] {pick.get("name"), pick.get("country")}) {
            if (part != null && !String.valueOf(part).isEmpty()) {
                if (!label.isEmpty()) {
                    label.append(", ");
                }
                label.append(part);
            }
        }
        return new Place(label.isEmpty() ? query : label.toString(), lat.doubleValue(), lon.doubleValue());
    }

    private static String low(Object o) {
        return o == null ? "" : String.valueOf(o).toLowerCase(Locale.ROOT);
    }

    /** The forecast request's parameters for a place (coordinates rounded to 3 decimals: the cache key). */
    public static Map<String, Object> forecastQuery(double latitude, double longitude) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("latitude", PyNumbers.round(latitude, 3));
        p.put("longitude", PyNumbers.round(longitude, 3));
        p.put("current", "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation");
        p.put("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max");
        p.put("timezone", "auto");
        p.put("forecast_days", 2);
        p.put("wind_speed_unit", "kmh");
        return p;
    }

    private static Object num(Object v, int digits) {
        double d;
        if (v instanceof Number n) {
            d = n.doubleValue();
        } else if (v instanceof String s) {
            try {
                d = Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        return digits == 0 ? (Object) PyNumbers.roundToLong(d) : (Object) PyNumbers.round(d, digits);
    }

    private static Object at(Map<?, ?> daily, String key, int i) {
        return daily.get(key) instanceof List<?> l && i < l.size() ? l.get(i) : null;
    }

    private static Map<String, Object> day(Map<?, ?> daily, int i) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("date", at(daily, "time", i));
        d.put("conditions", conditions(at(daily, "weather_code", i)));
        d.put("min_c", num(at(daily, "temperature_2m_min", i), 0));
        d.put("max_c", num(at(daily, "temperature_2m_max", i), 0));
        d.put("rain_chance_percent", num(at(daily, "precipitation_probability_max", i), 0));
        return d;
    }

    /** The result the model reads: {@code day} is now, today or tomorrow. Missing values are left out. */
    public static Map<String, Object> summary(Place place, Map<?, ?> data, String day) {
        Map<?, ?> daily = data.get("daily") instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("place", place.name());
        if ("now".equals(day)) {
            Map<?, ?> cur = data.get("current") instanceof Map<?, ?> c ? c : Map.of();
            String local = cur.get("time") == null ? "" : String.valueOf(cur.get("time"));
            Map<String, Object> today = day(daily, 0);
            out.put("when", "now" + (local.length() >= 16 ? " (local time " + local.substring(11, 16) + ")" : ""));
            out.put("conditions", conditions(cur.get("weather_code")));
            out.put("temperature_c", num(cur.get("temperature_2m"), 0));
            out.put("feels_like_c", num(cur.get("apparent_temperature"), 0));
            out.put("wind_kmh", num(cur.get("wind_speed_10m"), 0));
            out.put("precipitation_mm", num(cur.get("precipitation"), 1));
            out.put("today_min_c", today.get("min_c"));
            out.put("today_max_c", today.get("max_c"));
            out.put("today_rain_chance_percent", today.get("rain_chance_percent"));
        } else {
            Map<String, Object> d = day(daily, "today".equals(day) ? 0 : 1);
            String when = day;
            Object date = d.remove("date");
            try {
                if (date instanceof String s) {
                    when += ", " + DAY.format(LocalDate.parse(s));
                }
            } catch (DateTimeParseException e) {
                // no date: just "tomorrow"
            }
            out.put("when", when);
            out.putAll(d);
        }
        out.values().removeIf(v -> v == null);
        return out;
    }
}
