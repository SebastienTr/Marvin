// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleSupplier;

import marvin.host.application.conversation.port.out.JsonFetcher;
import marvin.host.domain.conversation.tool.ToolError;
import marvin.host.domain.conversation.tool.Weather;
import marvin.host.domain.shared.PyNumbers;

/**
 * {@code get_weather}: the weather now, today or tomorrow, from Open-Meteo (free, no account, no key). Two
 * requests: geocoding (kept for the session), then the forecast (kept ten minutes per place, so "and
 * tomorrow?" costs nothing). Only the place name and its coordinates are sent.
 */
public final class WeatherTool implements ToolFunction {
    private final String homePlace;
    private final JsonFetcher fetch;
    private final DoubleSupplier clock;
    private final Map<String, Weather.Place> places = new HashMap<>();
    private final Map<String, Object[]> forecasts = new HashMap<>();

    public WeatherTool(String homePlace, JsonFetcher fetch, DoubleSupplier monotonicSeconds) {
        this.homePlace = homePlace == null ? "" : homePlace.strip();
        this.fetch = fetch;
        this.clock = monotonicSeconds;
    }

    /** The tool, for a registry. */
    public static ToolRegistry.Tool tool(String homePlace, JsonFetcher fetch, DoubleSupplier monotonicSeconds) {
        return new ToolRegistry.Tool(Weather.spec(), new WeatherTool(homePlace, fetch, monotonicSeconds));
    }

    @Override
    public Object call(Map<String, Object> arguments, Map<String, Object> context) {
        Object lang = context.get("language");
        String language = lang instanceof String s && Weather.LANGUAGES.contains(s) ? s : "en";
        Object p = arguments.get("place");
        String name = p instanceof String s && !s.strip().isEmpty() ? s.strip() : homePlace;
        if (name.isEmpty()) {
            throw new ToolError("no place was given and no home location is set: ask the person which city or town");
        }
        double deadline = clock.getAsDouble() + Weather.BUDGET_S;
        Weather.Place place = geocode(name, language, deadline);
        Map<?, ?> data = forecast(place, deadline);
        Object day = arguments.get("day");
        return Weather.summary(place, data, day instanceof String d && !d.isEmpty() ? d : "now");
    }

    private Map<?, ?> get(String url, Map<String, Object> params, double deadline) {
        double left = deadline - clock.getAsDouble();
        if (left <= 0.2) {
            throw new ToolError("the weather service did not answer in time");
        }
        Object data;
        try {
            data = fetch.get(url, params, left);
        } catch (JsonFetcher.Failed e) {
            if (e.timedOut() || String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("timed out")) {
                throw new ToolError("the weather service did not answer in time");
            }
            throw new ToolError("the weather service could not be reached (" + e.getMessage() + ")");
        }
        if (!(data instanceof Map<?, ?> m)) {
            throw new ToolError("the weather service gave an unexpected answer");
        }
        Object error = m.get("error");
        if (error != null && !Boolean.FALSE.equals(error) && !"".equals(error)) {
            Object reason = m.get("reason");
            throw new ToolError("the weather service refused the request (" + (reason == null ? "error" : reason) + ")");
        }
        return m;
    }

    private Weather.Place geocode(String name, String language, double deadline) {
        String key = String.join(" ", name.toLowerCase(Locale.ROOT).strip().split("\\s+"));
        synchronized (places) {
            if (places.containsKey(key)) {
                Weather.Place loc = places.get(key);
                if (loc == null) {
                    throw new ToolError("no place called '" + name + "' was found");
                }
                return loc;
            }
        }
        Map<?, ?> data = get(Weather.GEOCODING_URL, Weather.geocodingQuery(name, language), deadline);
        Weather.Place loc = Weather.pickPlace(name, data);
        synchronized (places) {
            places.put(key, loc);
        }
        if (loc == null) {
            throw new ToolError("no place called '" + name + "' was found");
        }
        return loc;
    }

    private Map<?, ?> forecast(Weather.Place place, double deadline) {
        String key = PyNumbers.round(place.latitude(), 3) + "," + PyNumbers.round(place.longitude(), 3);
        double now = clock.getAsDouble();
        synchronized (forecasts) {
            Object[] hit = forecasts.get(key);
            if (hit != null && now - (double) hit[0] < Weather.CACHE_S) {
                return (Map<?, ?>) hit[1];
            }
        }
        Map<?, ?> data = get(Weather.FORECAST_URL, Weather.forecastQuery(place.latitude(), place.longitude()), deadline);
        if (!data.containsKey("current") || !data.containsKey("daily")) {
            throw new ToolError("the weather service gave an incomplete answer");
        }
        synchronized (forecasts) {
            forecasts.put(key, new Object[] {now, data});
        }
        return data;
    }

    /** The built-in tools, switched on or off by the voice settings. */
    public static ToolRegistry registry(boolean enabled, boolean internet, String homePlace, JsonFetcher fetch,
                                        DoubleSupplier monotonicSeconds) {
        return new ToolRegistry(List.of(tool(homePlace, fetch, monotonicSeconds)), enabled, internet, monotonicSeconds);
    }
}
