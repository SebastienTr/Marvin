"""`get_weather`: the weather now, today or tomorrow, from Open-Meteo (https://open-meteo.com: free,
no account, no API key).

Two requests: the geocoding API turns a place name into coordinates (kept for the session), then
the forecast API gives the current conditions and today's and tomorrow's summary (kept for ten
minutes per place, so "and tomorrow?" costs nothing). Only the place name and its coordinates are
sent; nothing about the person.

The result is a small dict with the units in the key names and the WMO weather code turned into
plain English words, for the model to phrase in one or two spoken sentences.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import datetime as dt
import threading
import time
from typing import Callable

from .base import Tool, ToolError

GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
CACHE_S = 600.0             # a forecast is reused this long for the same place
BUDGET_S = 4.0              # both requests together
MAX_PLACE = 80

# WMO weather interpretation codes (Open-Meteo's `weather_code`), in plain words
WMO = {
    0: "clear sky", 1: "mainly clear", 2: "partly cloudy", 3: "overcast",
    45: "fog", 48: "freezing fog",
    51: "light drizzle", 53: "drizzle", 55: "heavy drizzle", 56: "light freezing drizzle", 57: "freezing drizzle",
    61: "light rain", 63: "rain", 65: "heavy rain", 66: "light freezing rain", 67: "freezing rain",
    71: "light snow", 73: "snow", 75: "heavy snow", 77: "snow grains",
    80: "light rain showers", 81: "rain showers", 82: "violent rain showers",
    85: "light snow showers", 86: "heavy snow showers",
    95: "thunderstorm", 96: "thunderstorm with light hail", 99: "thunderstorm with heavy hail",
}

PARAMETERS = {
    "type": "object",
    "properties": {
        "place": {"type": "string", "maxLength": MAX_PLACE,
                  "description": "City or town, e.g. \"Nice\" or \"Lyon\". Leave it out for the owner's home."},
        "day": {"type": "string", "enum": ["now", "today", "tomorrow"],
                "description": "\"now\" for the current conditions (default), \"today\" or \"tomorrow\" "
                               "for the day's forecast."},
    },
    "required": [],
}

DESCRIPTION = ("Get the real weather for a place: the current conditions, or today's or tomorrow's forecast. "
               "Use it whenever the person asks about the weather, temperature, rain or wind. When they name "
               "no place, call it without one: it then uses the owner's home, or says which city to ask for.")


def conditions(code) -> str:
    try:
        return WMO.get(int(code), f"weather code {code}")
    except (TypeError, ValueError):
        return "unknown"


def _num(v, digits: int = 0):
    if v is None:
        return None
    return round(float(v)) if digits == 0 else round(float(v), digits)


class OpenMeteoWeather:
    """The callable behind `get_weather`. ``fetch(url, params, timeout) -> dict`` does the HTTP (the
    real one by default; tests pass a fake); ``clock`` is for the cache."""

    def __init__(self, home_place: str = "", fetch: Callable | None = None,
                 clock: Callable[[], float] = time.monotonic, cache_s: float = CACHE_S, budget_s: float = BUDGET_S):
        self.home_place = (home_place or "").strip()
        if fetch is None:
            from ..net import fetch_json
            fetch = fetch_json
        self.fetch = fetch
        self.clock = clock
        self.cache_s = cache_s
        self.budget_s = budget_s
        self._places: dict[str, dict | None] = {}          # for the session, whatever the language
        self._forecasts: dict[tuple[float, float], tuple[float, dict]] = {}
        self._lock = threading.Lock()

    def __call__(self, place: str | None = None, day: str = "now", context: dict | None = None) -> dict:
        language = (context or {}).get("language")
        language = language if language in ("fr", "en", "de", "es", "it", "nl", "pt") else "en"
        name = (place or "").strip() or self.home_place
        if not name:
            raise ToolError("no place was given and no home location is set: ask the person which city or town")
        deadline = time.monotonic() + self.budget_s
        loc = self._geocode(name, language, deadline)
        data = self._forecast(loc, deadline)
        return self._summary(loc, data, day or "now")

    # -------------------------------------------------------- requests

    def _get(self, url: str, params: dict, deadline: float) -> dict:
        left = deadline - time.monotonic()
        if left <= 0.2:
            raise ToolError("the weather service did not answer in time")
        try:
            data = self.fetch(url, params, left)
        except TimeoutError:
            raise ToolError("the weather service did not answer in time") from None
        except (OSError, ValueError) as e:
            reason = getattr(e, "reason", None) or e
            if "timed out" in str(reason).lower():
                raise ToolError("the weather service did not answer in time") from None
            raise ToolError(f"the weather service could not be reached ({reason})") from None
        if not isinstance(data, dict):
            raise ToolError("the weather service gave an unexpected answer")
        if data.get("error"):
            raise ToolError(f"the weather service refused the request ({data.get('reason', 'error')})")
        return data

    def _geocode(self, name: str, language: str, deadline: float) -> dict:
        key = " ".join(name.lower().split())
        with self._lock:
            if key in self._places:
                loc = self._places[key]
                if loc is None:
                    raise ToolError(f"no place called '{name}' was found")
                return loc
        # "Paris, France": the geocoder searches names only, so search the name and use the rest to
        # choose between the results (Paris, France rather than Paris, Texas)
        head, _, qualifier = name.partition(",")
        query = head.strip() or name
        data = self._get(GEOCODING_URL, {"name": query, "count": 5 if qualifier else 1, "language": language,
                                         "format": "json"}, deadline)
        results = data.get("results") or []
        pick = None
        q = qualifier.strip().lower()
        if q:
            pick = next((r for r in results if q in (str(r.get("country", "")).lower(),
                                                     str(r.get("country_code", "")).lower(),
                                                     str(r.get("admin1", "")).lower())), None)
        pick = pick or (results[0] if results else None)
        loc = None
        if pick is not None and "latitude" in pick and "longitude" in pick:
            label = ", ".join(str(p) for p in (pick.get("name"), pick.get("country")) if p)
            loc = {"name": label or query, "latitude": float(pick["latitude"]), "longitude": float(pick["longitude"])}
        with self._lock:
            self._places[key] = loc
        if loc is None:
            raise ToolError(f"no place called '{name}' was found")
        return loc

    def _forecast(self, loc: dict, deadline: float) -> dict:
        key = (round(loc["latitude"], 3), round(loc["longitude"], 3))
        now = self.clock()
        with self._lock:
            hit = self._forecasts.get(key)
            if hit is not None and now - hit[0] < self.cache_s:
                return hit[1]
        data = self._get(FORECAST_URL, {
            "latitude": key[0], "longitude": key[1],
            "current": "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation",
            "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
            "timezone": "auto", "forecast_days": 2, "wind_speed_unit": "kmh",
        }, deadline)
        if "current" not in data or "daily" not in data:
            raise ToolError("the weather service gave an incomplete answer")
        with self._lock:
            self._forecasts[key] = (now, data)
        return data

    # -------------------------------------------------------- the result

    @staticmethod
    def _day(daily: dict, i: int) -> dict:
        def at(key):
            v = daily.get(key) or []
            return v[i] if i < len(v) else None
        return {"date": at("time"), "conditions": conditions(at("weather_code")),
                "min_c": _num(at("temperature_2m_min")), "max_c": _num(at("temperature_2m_max")),
                "rain_chance_percent": _num(at("precipitation_probability_max"))}

    def _summary(self, loc: dict, data: dict, day: str) -> dict:
        daily = data.get("daily") or {}
        out = {"place": loc["name"]}
        if day == "now":
            cur = data.get("current") or {}
            local = str(cur.get("time", ""))
            today = self._day(daily, 0)
            out.update({
                "when": "now" + (f" (local time {local[11:16]})" if len(local) >= 16 else ""),
                "conditions": conditions(cur.get("weather_code")),
                "temperature_c": _num(cur.get("temperature_2m")),
                "feels_like_c": _num(cur.get("apparent_temperature")),
                "wind_kmh": _num(cur.get("wind_speed_10m")),
                "precipitation_mm": _num(cur.get("precipitation"), 1),
                "today_min_c": today["min_c"], "today_max_c": today["max_c"],
                "today_rain_chance_percent": today["rain_chance_percent"],
            })
        else:
            d = self._day(daily, 0 if day == "today" else 1)
            when = day
            try:
                when += f", {dt.date.fromisoformat(d.pop('date')):%A %d %B}"
            except (TypeError, ValueError):
                d.pop("date", None)
            out.update({"when": when, **d})
        return {k: v for k, v in out.items() if v is not None}


def weather_tool(home_place: str = "", fetch: Callable | None = None, **kw) -> Tool:
    """The `get_weather` tool. ``home_place``: used when no place is asked for."""
    return Tool("get_weather", DESCRIPTION, PARAMETERS, OpenMeteoWeather(home_place, fetch, **kw),
                timeout=BUDGET_S + 1.0, online=True, wants_context=True)
