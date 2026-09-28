"""Tools the language model can call while it answers (docs/voice.md, "Tools").

    registry = default_registry(home_place="Nice")      # the built-in tools
    registry.ollama_tools()                             # Ollama's `tools` list (constant)
    registry.call("get_weather", {"day": "tomorrow"})   # a ToolResult, never an exception

To add a tool, write a function, describe it with a `Tool`, and add it in `default_registry`.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

from .base import Tool, ToolError, ToolRegistry, ToolResult, validate_arguments
from .weather import OpenMeteoWeather, weather_tool


def default_registry(enabled: bool = True, internet: bool = True, home_place: str = "") -> ToolRegistry:
    """Marvin's tools, switched on or off by the voice settings (`tools`, `internet`)."""
    return ToolRegistry([weather_tool(home_place)], enabled=enabled, internet=internet)


def catalog() -> list[dict]:
    """Every built-in tool (name, description, online), for the app's settings."""
    return [{"name": t.name, "description": t.description, "online": t.online}
            for t in default_registry().all()]


__all__ = ["Tool", "ToolError", "ToolRegistry", "ToolResult", "validate_arguments", "OpenMeteoWeather",
           "weather_tool", "default_registry", "catalog"]
