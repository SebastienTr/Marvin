"""The few HTTPS requests the voice makes (Piper voice downloads, online tools), standard library only.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import json
import urllib.parse
import urllib.request


def https_context():
    """A verified TLS context. python.org builds of Python on macOS ship without CA certificates
    until "Install Certificates.command" is run; certifi's bundle (installed with the voice extra's
    dependencies) avoids that step."""
    import ssl
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        return ssl.create_default_context()


_CONTEXT = None


def fetch_json(url: str, params: dict | None = None, timeout: float = 4.0):
    """GET `url` (with `params` as a query string) and parse the JSON answer. Raises OSError
    (urllib.error.URLError, TimeoutError...) or ValueError."""
    global _CONTEXT
    if _CONTEXT is None:
        _CONTEXT = https_context()
    if params:
        url += "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "marvin-host"})
    with urllib.request.urlopen(req, timeout=timeout, context=_CONTEXT) as r:
        return json.loads(r.read().decode("utf-8"))
