"""The voice sidecar's gRPC server: `marvin.voice.v1.Voice` and the standard health service.

- `Session`: one long-lived bidirectional stream with the core (session.py). One core at a time:
  a new session replaces the previous one, whose stream ends.
- `Transcribe`: batch speech recognition of a clip, with the current session's settings.
- `Options`: what the voice settings can offer here (backends, Whisper models, voices).
- `grpc.health.v1.Health`: SERVING for "" and "marvin.voice.v1.Voice" once the server listens.

With a token (`--token` or MARVIN_SIDECAR_TOKEN), every call must carry the metadata
`authorization: Bearer <token>`: the shared secret of design 10.4 for processes on one machine.

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import concurrent.futures
import hmac
import logging
import queue
import threading
import time

import grpc
import numpy as np
from grpc_health.v1 import health, health_pb2, health_pb2_grpc

from ...voice import control
from ...voice.control import VoiceUnavailable
from .contract import voice_pb2 as pb
from .contract import voice_pb2_grpc as pb_grpc
from .session import EngineFactory, VoiceSession, voice_config

log = logging.getLogger("marvin.sidecar.voice")

SERVICE = "marvin.voice.v1.Voice"


class VoiceService(pb_grpc.VoiceServicer):
    def __init__(self, factory: EngineFactory):
        self.factory = factory
        self._lock = threading.Lock()
        self._session: VoiceSession | None = None

    @property
    def session(self) -> VoiceSession | None:
        return self._session

    def Session(self, request_iterator, context):
        out: queue.Queue = queue.Queue()
        session = VoiceSession(self.factory, out.put)
        with self._lock:
            old, self._session = self._session, session
        if old is not None:
            log.info("a new session replaces the previous one")
            old.close()
        log.info("core connected (%s)", context.peer())

        def read():
            try:
                for m in request_iterator:
                    session.handle(m)
            except grpc.RpcError:
                pass
            except Exception:                       # noqa: BLE001 - a bad message must not kill the stream silently
                log.exception("session failed")
            finally:
                out.put(None)

        threading.Thread(target=read, name="marvin-sidecar-session", daemon=True).start()
        context.add_callback(lambda: out.put(None))
        try:
            while True:
                m = out.get()
                if m is None:
                    return
                yield m
        finally:
            session.close()
            with self._lock:
                if self._session is session:
                    self._session = None
            log.info("core disconnected")

    def Transcribe(self, request: pb.AudioClip, context):
        if len(request.pcm) % 2:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, "pcm must be 16-bit samples")
        s = self._session
        settings = dict(s._settings or {}) if s is not None else {}
        if request.language:
            settings["language"] = request.language
        c = voice_config(settings)
        try:
            if not self.factory.fake:
                control.audio_preflight(settings, microphone=False)
            stt = self.factory.stt(c)
        except VoiceUnavailable as e:
            context.abort(grpc.StatusCode.FAILED_PRECONDITION, f"{e}. {e.fix}")
        pcm = np.frombuffer(request.pcm, dtype="<i2").astype(np.int16)
        t = time.monotonic()
        with self.factory.stt_lock:
            tr = stt.transcribe(pcm, request.language or None)
        out = pb.Transcript(text=tr.text or "", language=tr.language or "", seconds=tr.seconds or time.monotonic() - t,
                            rejected=tr.rejected or "")
        for key in ("language_prob", "no_speech_prob", "avg_logprob", "compression_ratio"):
            v = getattr(tr, key)
            if v is not None:
                setattr(out, key, float(v))
        return out

    def Options(self, request, context):
        return options()


def options() -> pb.VoiceOptions:
    """What the settings can offer on this machine."""
    out = pb.VoiceOptions(stt_models=["auto", *control.STT_MODELS], languages=["fr", "en"])
    for name, ok in control.stt_available().items():
        out.stt.add(name=name, installed=ok, why="" if ok else _why_not("stt", name))
    tts = control.tts_available()
    for name, ok in tts.items():
        out.tts.add(name=name, installed=ok, why="" if ok else _why_not("tts", name))
    if tts["piper"]:
        for v in control.piper_voices():
            out.voices.add(id=v["name"], engine="piper", language=v["name"][:2], installed=v["installed"])
    for v in control.say_voices():
        out.voices.add(id=v["name"], engine="say", language=v["locale"][:2], installed=True, locale=v["locale"])
    return out


def _why_not(kind: str, name: str) -> str:
    return {
        ("stt", "auto"): 'no speech recognition installed: pip install -e ".[voice]"',
        ("stt", "mlx"): "needs an Apple Silicon Mac and mlx-whisper",
        ("stt", "faster-whisper"): "pip install faster-whisper",
        ("tts", "say"): "macOS only",
        ("tts", "piper"): "pip install piper-tts",
        ("tts", "espeak"): "install espeak-ng",
    }.get((kind, name), "not installed")


class _TokenCheck(grpc.ServerInterceptor):
    def __init__(self, token: str):
        self._expected = f"Bearer {token}".encode()

        def deny(request, context):
            context.abort(grpc.StatusCode.UNAUTHENTICATED, "a valid token is required")
        self._deny = grpc.unary_unary_rpc_method_handler(deny)
        self._deny_stream = grpc.stream_stream_rpc_method_handler(lambda it, context: deny(None, context))

    def intercept_service(self, continuation, details):
        got = dict(details.invocation_metadata).get("authorization", "")
        if hmac.compare_digest(got.encode(), self._expected):
            return continuation(details)
        return self._deny_stream if details.method.endswith("/Session") else self._deny


def serve(factory: EngineFactory, host: str = "127.0.0.1", port: int = 0, token: str = "") -> tuple[grpc.Server, int, VoiceService]:
    """Starts the server; returns it, the port it listens on, and the voice service."""
    interceptors = [_TokenCheck(token)] if token else []
    server = grpc.server(concurrent.futures.ThreadPoolExecutor(max_workers=16, thread_name_prefix="marvin-grpc"),
                         interceptors=interceptors, options=[("grpc.so_reuseport", 0)])
    service = VoiceService(factory)
    pb_grpc.add_VoiceServicer_to_server(service, server)
    hs = health.HealthServicer()
    health_pb2_grpc.add_HealthServicer_to_server(hs, server)
    bound = server.add_insecure_port(f"{host}:{port}")
    if bound == 0:
        raise OSError(f"cannot listen on {host}:{port}")
    server.start()
    for name in ("", SERVICE):
        hs.set(name, health_pb2.HealthCheckResponse.SERVING)
    return server, bound, service
