"""The USB sensor bridge: a lidar or a radar on a USB-serial adapter, seen by the host as one more robot board.

No ESP32 needed: the sensor's UART goes to a USB-TTL adapter (CP2102, CH340, ...) plugged into this computer,
and this process reads it and speaks the robot's UDP protocol to the host on this computer (docs/usb-sensors.md):

- it finds the adapters (/dev/cu.usbserial-*, /dev/cu.SLAB_USBtoUART*, /dev/cu.wchusbserial*, /dev/ttyUSB*; never a
  Bluetooth port) and listens to each for about a second at 921600 baud (D800 / STL-27L), 230400 (D500 /
  STL-19P) and 256000 (HLK-LD2450), keeping the first speed that gives valid frames (header and CRC);
- each sensor found is a device of its own: `HELLO` with board id 6 (USB sensor bridge), its sensors real (no
  simulated flag), a stable locally administered MAC made from the adapter's serial number, `HOST_ACK` and the
  link timeout as the firmware's link_step (`HELLO` every 0.5 s until acknowledged, then every 2 s; no ack for
  6 s: searching again);
- the lidar's packets go out as the firmware sends them: framed and CRC-checked (sensor_framing.py, the mirror
  of firmware/src/sensor_framing.cpp), 10 per `LIDAR` datagram, a partial batch after 20 ms, stamped with the
  time the batch's first packet was read; LD2450 frames one per `LD2450` datagram; the counters every 5 s in a
  `LOG`, as the firmware's report_stats;
- an adapter unplugged is closed and found again when it comes back.

    marvin-host usb-sensors                 # bridge every sensor found, until Ctrl-C
    marvin-host usb-sensors --detect        # say what is plugged in and exit (1: nothing found)
    python -m marvin_host.usb_sensors ...   # the same, without the rest of the command line's imports

SPDX-License-Identifier: MIT
"""
from __future__ import annotations

import argparse
import errno
import glob
import hashlib
import json
import logging
import os
import socket
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import Callable, Protocol

from . import __version__, protocol
from .sensor_framing import TARGETS, Ld2450Framer, LidarFramer, ld2450_multi_target_sequence

log = logging.getLogger("marvin.usb_sensors")

BOARD = protocol.BOARD_USB_BRIDGE
FIRMWARE = f"usb-{__version__}"

# The serial ports a USB-TTL adapter shows up as. macOS: /dev/cu.* (never /dev/tty.*, which waits for carrier
# detect); Linux: /dev/ttyUSB* (CP210x, CH34x, FTDI). /dev/ttyACM* is left out: that is a board's own USB (an
# ESP32-S3), not an adapter.
PORT_PATTERNS = ("/dev/cu.usbserial-*", "/dev/cu.SLAB_USBtoUART*", "/dev/cu.wchusbserial*", "/dev/cu.usbmodem-ch34*",
                 "/dev/ttyUSB*")
NOT_ADAPTERS = ("bluetooth", "blth", "debug-console", "wlan-debug", "airpods")

LIDAR_BAUDS = {921600: 2, 230400: 1}        # baud -> LIDAR model id: D800 (STL-27L), D500 (STL-19P)
LIDAR_SHORT = {1: "STL-19P", 2: "STL-27L"}
RADAR_BAUD = 256000                         # HLK-LD2450
PROBE_S = 1.0                               # listening time per baud rate
PROBE_LIDAR_PACKETS = 3                     # valid packets that make a lidar
PROBE_RADAR_FRAMES = 2                      # valid target frames that make a radar

LIDAR_BATCH = 10                            # as the firmware: packets per datagram
LIDAR_FLUSH_S = 0.020                       # a partial batch goes out after this
LINK_TIMEOUT_S = 6.0
HELLO_SEARCHING_S = 0.5
HELLO_LINKED_S = 2.0
STATS_PERIOD_S = 5.0
SILENT_S = 30.0                             # no valid frame for this long: the sensor is listened to afresh
SCAN_PERIOD_S = 2.0                         # how often new adapters are looked for
STATUS_PERIOD_S = 0.5                       # how often the status file is written while bridging
RETRY_S = (10.0, 30.0)                      # an adapter with no sensor is listened to again after these, then
                                            # only once it is plugged in again
BIND_PORT_BASE = 47120                      # each sensor sends from 47120 + (its MAC % 64) when free


class SerialLike(Protocol):
    def read(self, n: int) -> bytes: ...
    def write(self, data: bytes) -> int | None: ...
    def close(self) -> None: ...


Opener = Callable[[str, int], SerialLike]


def open_serial(path: str, baud: int) -> SerialLike:
    """The adapter at `path`, 8N1, reads returning within 20 ms. Opened exclusively (a second reader, such as a
    serial monitor, fails instead of sharing it), with DTR and RTS held low so an ESP32 board on that port is not
    reset by the opening."""
    import serial  # pyserial: a dependency of marvin-host

    s = serial.Serial()
    s.port = path
    s.baudrate = baud
    s.timeout = 0.02
    s.dtr = False
    s.rts = False
    try:
        s.exclusive = True
    except (AttributeError, ValueError):
        pass                                # not on Windows
    s.open()
    try:
        s.reset_input_buffer()
    except Exception:                       # noqa: BLE001 - nothing to forget is fine
        pass
    return s


def candidate_ports(patterns=PORT_PATTERNS, env: dict | None = None) -> list[str]:
    """The serial ports that may be a USB-TTL adapter, sorted. MARVIN_USB_PORTS (comma-separated paths or globs)
    replaces the patterns."""
    env = os.environ if env is None else env
    wanted = env.get("MARVIN_USB_PORTS", "").strip()
    pats = [p.strip() for p in wanted.split(",") if p.strip()] if wanted else list(patterns)
    found: set[str] = set()
    for p in pats:
        found.update(glob.glob(p))
    return sorted(f for f in found if not any(w in f.lower() for w in NOT_ADAPTERS))


def port_identity(path: str) -> str:
    """What stays the same when the adapter is plugged in again: its USB serial number (and ids) when the OS
    says it, else the port's name."""
    try:
        from serial.tools import list_ports
        for p in list_ports.comports():
            if p.device == path and (p.serial_number or p.vid):
                return f"{p.vid or 0:04x}:{p.pid or 0:04x}:{p.serial_number or ''}:{os.path.basename(path)}"
    except Exception:                       # noqa: BLE001 - no pyserial tools: the name is enough
        pass
    return os.path.basename(path)


def pseudo_mac(identity: str) -> bytes:
    """A stable, locally administered, unicast MAC address for a sensor (bit 1 of the first byte set, bit 0
    clear), so the app knows the same sensor after a replug or a restart."""
    b = bytearray(hashlib.sha256(("marvin-usb:" + identity).encode()).digest()[:6])
    b[0] = (b[0] & 0xFC) | 0x02
    return bytes(b)


@dataclass
class Detection:
    """A sensor found on a port."""

    port: str
    baud: int
    kind: str                       # "lidar" or "radar"
    model: int = 0                  # LIDAR model id (lidar only)
    frames: int = 0                 # valid frames seen while probing

    @property
    def name(self) -> str:
        if self.kind == "lidar":
            return f"lidar {LIDAR_SHORT.get(self.model, '?')}"
        return "radar LD2450"

    @property
    def long_name(self) -> str:
        if self.kind == "lidar":
            return f"lidar {protocol.LIDAR_MODELS.get(self.model, self.model)}"
        return "radar HLK-LD2450"

    def describe(self) -> str:
        return f"{self.name} on {self.port} @{self.baud}"


def probe(path: str, opener: Opener = open_serial, seconds: float = PROBE_S, radar: bool = True,
          clock: Callable[[], float] = time.monotonic) -> Detection | None:
    """Listens to `path` at each speed in turn; the first that carries valid frames says what is there."""
    plan: list[tuple[int, str]] = [(b, "lidar") for b in LIDAR_BAUDS]
    if radar:
        plan.append((RADAR_BAUD, "radar"))
    for baud, kind in plan:
        try:
            s = opener(path, baud)
        except Exception as e:              # noqa: BLE001 - busy (a serial monitor has it), unplugged, no permission
            if isinstance(e, PermissionError) or getattr(e, "errno", None) == errno.EACCES:
                log.warning("usb-sensors: no permission to open %s (Linux: sudo usermod -aG dialout $USER, then log "
                            "in again)", path)
            else:
                log.debug("usb-sensors: cannot open %s at %d: %s", path, baud, e)
            return None
        try:
            framer = LidarFramer() if kind == "lidar" else Ld2450Framer()
            need = PROBE_LIDAR_PACKETS if kind == "lidar" else PROBE_RADAR_FRAMES
            end = clock() + seconds
            got = 0
            while clock() < end:
                data = s.read(4096)
                if not data:
                    continue
                for f in framer.feed(data):
                    if kind == "lidar" or f[0] == TARGETS:
                        got += 1
                if got >= need:
                    return Detection(path, baud, kind, LIDAR_BAUDS.get(baud, 0) if kind == "lidar" else 0, got)
        except Exception as e:              # noqa: BLE001 - unplugged while listening
            log.debug("usb-sensors: %s at %d: %s", path, baud, e)
            return None
        finally:
            try:
                s.close()
            except Exception:               # noqa: BLE001
                pass
    return None


# ---------------------------------------------------------------------------- the network side


class UsbDevice:
    """One sensor, as a robot board on the network: link, batching, counters. Not thread-safe: one loop calls it.

    `clock` gives monotonic seconds; the datagrams' t_us is microseconds since this device was made.
    """

    def __init__(self, det: Detection, host: str = "127.0.0.1", port: int = protocol.HOST_PORT,
                 identity: str | None = None, sock: socket.socket | None = None,
                 clock: Callable[[], float] = time.monotonic):
        self.det = det
        self.mac = pseudo_mac(identity or det.port)
        self.name = "marvin-" + self.mac[-3:].hex()
        self.clock = clock
        self.t0 = clock()
        self.target = (host, port)
        self.sock = sock if sock is not None else make_socket(self.mac)
        self.seq = 0
        self.linked = False
        self.last_ack = 0.0
        self.next_hello = 0.0
        self.batch: list[bytes] = []
        self.batch_t_us = 0
        self.batch_t = 0.0
        self.sent = 0                       # datagrams sent
        self.packets = 0                    # lidar packets or radar frames forwarded
        self.points = 0                     # lidar points forwarded (12 per packet)

    def now_us(self) -> int:
        return int((self.clock() - self.t0) * 1e6)

    def send(self, msg_type: int, payload: bytes, t_us: int | None = None) -> None:
        d = protocol.pack(msg_type, self.seq, self.now_us() if t_us is None else t_us, payload)
        self.seq = (self.seq + 1) & 0xFFFFFFFF
        try:
            self.sock.sendto(d, self.target)
            self.sent += 1
        except OSError as e:                # the host is not there (yet): the HELLOs keep trying
            log.debug("usb-sensors: send to %s failed: %s", self.target, e)

    def hello(self) -> None:
        h = protocol.Hello(self.mac, BOARD, 0, 0, int((self.clock() - self.t0) * 1000) & 0xFFFFFFFF, FIRMWARE)
        self.send(protocol.HELLO, h.encode())

    def send_log(self, text: str) -> None:
        self.send(protocol.LOG, text.encode()[:480])

    def poll_host(self) -> None:
        """Reads what the host sent: HOST_ACK links; the rest (face, sounds) is for boards that have them."""
        while True:
            try:
                data, addr = self.sock.recvfrom(2048)
            except (BlockingIOError, InterruptedError):
                return
            except OSError:
                return
            try:
                hdr, payload = protocol.unpack(data)
            except protocol.ProtocolError:
                continue
            if hdr.type == protocol.HOST_ACK and len(payload) >= 8:
                self.on_host_ack(addr)

    def on_host_ack(self, addr) -> None:
        self.last_ack = self.clock()
        if self.linked:
            return
        self.linked = True
        log.info("usb-sensors: %s linked to the host at %s:%d", self.name, addr[0], addr[1])
        self.send_log(f"USB sensor bridge {FIRMWARE} up, real {self.det.long_name} on {self.det.port} "
                      f"at {self.det.baud} baud")

    def link_step(self) -> None:
        """The firmware's link_step: host datagrams, link timeout, HELLO."""
        self.poll_host()
        now = self.clock()
        if self.linked and now - self.last_ack > LINK_TIMEOUT_S:
            self.linked = False
            self.batch.clear()
            log.warning("usb-sensors: %s lost the host, searching", self.name)
        if now >= self.next_hello:
            self.hello()
            self.next_hello = now + (HELLO_LINKED_S if self.linked else HELLO_SEARCHING_S)

    # lidar: the firmware's on_lidar_packet / flush_lidar
    def on_lidar_packet(self, packet: bytes) -> None:
        if not self.linked:
            return
        if not self.batch:
            self.batch_t_us = self.now_us()
            self.batch_t = self.clock()
        self.batch.append(packet)
        if len(self.batch) == LIDAR_BATCH:
            self.flush_lidar()

    def flush_lidar(self) -> None:
        if self.batch and self.linked:
            self.send(protocol.LIDAR, bytes([self.det.model]) + b"".join(self.batch), self.batch_t_us)
            self.packets += len(self.batch)
            self.points += 12 * len(self.batch)
        self.batch.clear()

    def flush_if_due(self) -> None:
        if not self.linked:
            self.batch.clear()
        elif self.batch and self.clock() - self.batch_t >= LIDAR_FLUSH_S:
            self.flush_lidar()

    def on_ld2450_frame(self, frame: bytes) -> None:
        if self.linked:
            self.send(protocol.LD2450, frame)
            self.packets += 1

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


def make_socket(mac: bytes, bind: str = "0.0.0.0") -> socket.socket:
    """A UDP socket that sends from the same port each time when it can (47120 + the MAC's last byte % 64), so
    a restarted bridge is the same device for the host, not a new one with the old one offline."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.bind((bind, BIND_PORT_BASE + mac[-1] % 64))
    except OSError:
        s.bind((bind, 0))
    s.setblocking(False)
    return s


# ---------------------------------------------------------------------------- one sensor, read and sent


@dataclass
class Counters:
    frames: int = 0
    crc_errors: int = 0
    dropped: int = 0
    bad: int = 0


class SensorLink:
    """Reads one sensor's serial line and forwards its frames through a UsbDevice, until `stop` or the adapter
    goes away. `run` returns why it ended: "stopped", "unplugged" or "silent"."""

    def __init__(self, det: Detection, serial: SerialLike, device: UsbDevice, stop: threading.Event | None = None,
                 configure_radar: bool = True, silent_s: float | None = SILENT_S):
        self.det = det
        self.serial = serial
        self.device = device
        self.stop = stop or threading.Event()
        self.lidar = LidarFramer() if det.kind == "lidar" else None
        self.radar = Ld2450Framer() if det.kind == "radar" else None
        self.configure_radar = configure_radar
        self.silent_s = silent_s            # None: never give up on a silent sensor
        self.clock = device.clock
        self._last = Counters()
        self._last_stats = 0.0
        self._last_frame = 0.0
        self.rate = 0.0                     # frames per second over the last stats period
        self.state = "starting"

    def counters(self) -> Counters:
        if self.lidar is not None:
            f = self.lidar
            return Counters(f.packets, f.crc_errors, f.dropped_bytes, 0)
        f = self.radar
        return Counters(f.frames, 0, f.dropped_bytes, f.bad_frames)

    def step(self) -> bool:
        """One pass of the loop: read what is there, forward it, keep the link. False once the adapter is gone."""
        try:
            data = self.serial.read(4096)
        except Exception as e:              # noqa: BLE001 - pyserial raises SerialException / OSError on unplug
            log.debug("usb-sensors: read failed: %s", e)
            return False
        now = self.clock()
        if data:
            if self.lidar is not None:
                for p in self.lidar.feed(data):
                    self._last_frame = now
                    self.device.on_lidar_packet(p)
            else:
                for kind, frame in self.radar.feed(data):
                    if kind == TARGETS:
                        self._last_frame = now
                        self.device.on_ld2450_frame(frame)
        self.device.flush_if_due()
        self.device.link_step()
        if now - self._last_stats >= STATS_PERIOD_S:
            self.report(now)
        return True

    def report(self, now: float) -> None:
        """The firmware's report_stats: one line here, and a LOG to the host when linked."""
        c, p = self.counters(), self._last
        s = max(1e-6, now - self._last_stats)
        self.rate = (c.frames - p.frames) / s
        if self._last_stats:
            if self.lidar is not None:
                pts = self.rate * 12
                msg = (f"lidar {self.rate:.0f} pkt/s ({pts:.0f} pts/s), {c.crc_errors - p.crc_errors} CRC err, "
                       f"{c.dropped - p.dropped} B dropped")
                line = f"usb-sensors: {self.det.name} on {self.det.port} @{self.det.baud}, {pts:.0f} pts/s"
            else:
                msg = f"LD2450 {self.rate:.1f} frame/s, {c.bad - p.bad} bad, {c.dropped - p.dropped} B dropped"
                line = f"usb-sensors: {self.det.name} on {self.det.port} @{self.det.baud}, {self.rate:.1f} frames/s"
            if not self.device.linked:
                line += ", waiting for the host on UDP %d" % self.device.target[1]
            log.info(line)
            if self.device.linked:
                self.device.send_log(msg)
        self._last = c
        self._last_stats = now

    def run(self) -> str:
        now = self.clock()
        self._last_frame = now
        self._last_stats = 0.0
        self.report(now)                    # counters from here
        self.state = "running"
        if self.radar is not None and self.configure_radar:
            try:
                self.serial.write(ld2450_multi_target_sequence())   # needs the adapter's TXD on the radar's RX
            except Exception:               # noqa: BLE001 - a read-only wiring is fine
                pass
        try:
            while not self.stop.is_set():
                if not self.step():
                    self.state = "unplugged"
                    return "unplugged"
                if self.silent_s is not None and self.clock() - self._last_frame > self.silent_s:
                    self.state = "silent"
                    return "silent"
            self.state = "stopped"
            return "stopped"
        finally:
            self.device.flush_lidar()
            try:
                self.serial.close()
            except Exception:               # noqa: BLE001
                pass


# ---------------------------------------------------------------------------- every adapter


@dataclass
class _PortState:
    attempts: int = 0
    retry_at: float | None = 0.0    # None: not until it is plugged in again
    had_sensor: bool = False        # a sensor was bridged on this port: keep listening for it
    link: SensorLink | None = None
    thread: threading.Thread | None = None


@dataclass
class Bridge:
    """Finds the adapters, probes them, and runs one SensorLink per sensor, each in its own thread.

    A port whose sensor stops (unplugged, or silent) is probed again when it is back, and every RETRY_S[0] while
    its adapter stays plugged in; a port that never had a sensor is listened to again after RETRY_S, then only once
    it is plugged in again (it may be a board, not a sensor: opening its port may reset it).
    """

    host: str = "127.0.0.1"
    port: int = protocol.HOST_PORT
    radar: bool = True
    opener: Opener = open_serial
    ports: Callable[[], list[str]] = candidate_ports
    identity: Callable[[str], str] = port_identity
    status_file: str | None = None
    clock: Callable[[], float] = time.monotonic
    stop: threading.Event = field(default_factory=threading.Event)
    states: dict = field(default_factory=dict)       # port path -> _PortState
    sockets: dict = field(default_factory=dict)      # MAC -> its socket, kept for the whole run

    def scan(self) -> list[Detection]:
        """One look at the ports: probes the new ones, starts a link per sensor found. Returns what it started."""
        now = self.clock()
        present = set(self.ports())
        for path in list(self.states):
            st = self.states[path]
            if path not in present and (st.thread is None or not st.thread.is_alive()):
                del self.states[path]       # unplugged: probed afresh when it comes back
        started = []
        for path in sorted(present):
            st = self.states.setdefault(path, _PortState())
            if st.thread is not None and st.thread.is_alive():
                continue
            if st.retry_at is None or now < st.retry_at:
                continue
            det = probe(path, self.opener, radar=self.radar, clock=self.clock)
            if det is None:
                st.attempts += 1
                if st.had_sensor:
                    st.retry_at = now + RETRY_S[0]      # its sensor is unpowered or loose: it may come back
                else:
                    st.retry_at = now + RETRY_S[st.attempts - 1] if st.attempts <= len(RETRY_S) else None
                if st.attempts == 1:
                    log.info("usb-sensors: no lidar or radar on %s (listening again in %.0f s)", path, RETRY_S[0])
                continue
            self.start(det, st)
            started.append(det)
        self.write_status()
        return started

    def start(self, det: Detection, st: _PortState) -> None:
        try:
            ser = self.opener(det.port, det.baud)
        except Exception as e:              # noqa: BLE001
            log.warning("usb-sensors: %s: cannot open it again: %s", det.port, e)
            return
        ident = self.identity(det.port)
        mac = pseudo_mac(ident)
        if mac not in self.sockets:
            self.sockets[mac] = make_socket(mac)
        dev = UsbDevice(det, self.host, self.port, identity=ident, sock=self.sockets[mac], clock=self.clock)
        st.link = SensorLink(det, ser, dev, self.stop)
        st.had_sensor = True
        st.attempts = 0
        st.retry_at = 0.0
        log.info("usb-sensors: %s found on %s @%d: %s, sending to %s:%d", det.long_name, det.port, det.baud,
                 dev.name, self.host, self.port)

        def run() -> None:
            why = st.link.run()
            if why == "unplugged":
                log.warning("usb-sensors: %s unplugged from %s, waiting for it", det.name, det.port)
            elif why == "silent":
                log.warning("usb-sensors: %s on %s sent nothing for %.0f s (power? wiring?), listening again",
                            det.name, det.port, st.link.silent_s)
            # keep the socket: the same sensor comes back as the same device
            self.write_status()

        st.thread = threading.Thread(target=run, name=f"usb-{os.path.basename(det.port)}", daemon=True)
        st.thread.start()

    def links(self) -> list[SensorLink]:
        return [st.link for st in self.states.values()
                if st.link is not None and st.thread is not None and st.thread.is_alive()]

    def status(self) -> list[dict]:
        out = []
        for st in self.states.values():
            ln = st.link
            if ln is None or st.thread is None or not st.thread.is_alive():
                continue
            out.append({"kind": ln.det.kind, "name": ln.det.name, "port": ln.det.port, "baud": ln.det.baud,
                        "device": ln.device.name, "linked": ln.device.linked, "rate": round(ln.rate, 1)})
        return out

    def write_status(self) -> None:
        if not self.status_file:
            return
        try:
            tmp = self.status_file + ".tmp"
            with open(tmp, "w") as f:
                json.dump({"pid": os.getpid(), "updated": time.time(), "sensors": self.status()}, f)
            os.replace(tmp, self.status_file)
        except OSError:
            pass

    def run(self, exit_if_none: bool = False, seconds: float | None = None) -> int:
        """Scans every SCAN_PERIOD_S until stopped. `exit_if_none`: return 1 if the first scan finds nothing."""
        start = self.clock()
        first = True
        try:
            while not self.stop.is_set():
                self.scan()
                if first:
                    first = False
                    if not self.links():
                        ports = sorted(self.states)
                        if exit_if_none:
                            log.info("usb-sensors: no lidar or radar found%s; not bridging",
                                     f" on {', '.join(ports)}" if ports else " (no USB serial adapter)")
                            return 1
                        log.info("usb-sensors: waiting for a USB adapter with a lidar or a radar%s",
                                 f" ({', '.join(ports)}: nothing yet)" if ports else "")
                if seconds is not None and self.clock() - start >= seconds:
                    break
                self.write_status()
                self.stop.wait(STATUS_PERIOD_S if self.links() else SCAN_PERIOD_S)
        finally:
            self.stop.set()
            for st in self.states.values():
                if st.thread is not None:
                    st.thread.join(2.0)
            for s in self.sockets.values():
                s.close()
            if self.status_file:
                try:
                    os.remove(self.status_file)
                except OSError:
                    pass
        return 0


# ---------------------------------------------------------------------------- command line


def detect(ports: list[str] | None = None, opener: Opener = open_serial, radar: bool = True,
           seconds: float = PROBE_S) -> list[Detection]:
    """What is plugged in now: one probe per port."""
    out = []
    for path in ports if ports is not None else candidate_ports():
        d = probe(path, opener, seconds=seconds, radar=radar)
        if d is not None:
            out.append(d)
    return out


def add_arguments(ap: argparse.ArgumentParser) -> None:
    ap.add_argument("--host", default="127.0.0.1", help="the Marvin host's address (default: this computer)")
    ap.add_argument("--port", type=int, default=protocol.HOST_PORT, help="the host's UDP port")
    ap.add_argument("--serial", action="append", metavar="PORT",
                    help="use this serial port (repeatable) instead of looking for adapters")
    ap.add_argument("--no-radar", action="store_true", help="look for lidars only (no 256000 baud probe)")
    ap.add_argument("--detect", action="store_true", help="say which sensors are plugged in, and exit (1: none)")
    ap.add_argument("--json", action="store_true", help="with --detect: one JSON object per sensor")
    ap.add_argument("--exit-if-none", action="store_true", help="exit (1) if the first look finds no sensor")
    ap.add_argument("--status-file", help="keep what is bridged in this JSON file (./marvin status reads it)")
    ap.add_argument("--seconds", type=float, help="stop after this many seconds")


def run_cli(args) -> int:
    ports = (lambda: list(args.serial)) if args.serial else candidate_ports
    if args.detect:
        found = detect(ports(), radar=not args.no_radar)
        for d in found:
            if args.json:
                print(json.dumps({"kind": d.kind, "name": d.name, "long_name": d.long_name, "port": d.port,
                                  "baud": d.baud, "model": d.model}))
            else:
                print(f"{d.long_name} on {d.port} at {d.baud} baud")
        if not found and not args.json:
            listed = ports()
            print("no lidar or radar found" + (f" on {', '.join(listed)}" if listed else " (no USB serial adapter)"))
        return 0 if found else 1
    bridge = Bridge(host=args.host, port=args.port, radar=not args.no_radar, ports=ports, status_file=args.status_file)
    try:
        return bridge.run(exit_if_none=args.exit_if_none, seconds=args.seconds)
    except KeyboardInterrupt:
        bridge.stop.set()
        return 0


def setup_logging(verbose: int = 0) -> None:
    logging.basicConfig(level=logging.DEBUG if verbose else logging.INFO,
                        format="%(asctime)s %(message)s", datefmt="%H:%M:%S", stream=sys.stdout, force=True)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="marvin-host usb-sensors", description=__doc__.split("\n\n")[0])
    ap.add_argument("-v", "--verbose", action="count", default=0)
    add_arguments(ap)
    args = ap.parse_args(argv)
    try:
        import serial  # noqa: F401
    except ImportError:
        print("usb-sensors: pyserial is missing: pip install pyserial (./marvin up installs it)", file=sys.stderr)
        return 2
    setup_logging(args.verbose)
    return run_cli(args)


if __name__ == "__main__":
    sys.exit(main())
