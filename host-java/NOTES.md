<!-- SPDX-License-Identifier: CC-BY-4.0 -->
# Java host: engineering log

One section per migration phase ([docs/design.md](../docs/design.md), section 11): what was built,
the decisions taken, where they depart from the design and why, known gaps, and hints for the next
phase. Later phases read this before starting.

## Phase 0: foundation

### What exists

- **Maven multi-module build** (`host-java/`, wrapper 3.3.4 with Maven 3.9.11): parent on
  `spring-boot-starter-parent` 4.1.1, modules as in design 3.3 minus `marvin-adapter-mcp` and
  `marvin-adapter-notify` (nothing to put in them before phase 4). Pinned: Spring AI 2.0.1 (BOM),
  ArchUnit 1.5.1, gRPC 1.84.0 (BOM), protobuf 4.36.2, zonky embedded-postgres 2.2.2 with
  PostgreSQL 18.6 binaries, `io.github.ascopes:protobuf-maven-plugin` 5.1.10. Boot manages the rest
  (Flyway 12.4, Testcontainers 2.0.5, Jackson 3.1, JUnit 5, PostgreSQL JDBC 42.7). Enforcer:
  Java 25+, Maven 3.9+, upper-bound dependencies, and banned dependencies for the domain (none at
  all) and the application layer (the domain only).
- **A host that starts**: `MarvinHostApplication` (virtual threads on), `GET /api/health`
  (`{status, version, mode, components: {database: {state, detail}}}`, 503 when a component is
  down) through a plain-Java use case (`ReportHealth` / `HealthService`) and a `ComponentProbe`
  port implemented by the persistence adapter; Actuator at `/actuator/health`.
- **Persistence**: `ContextMigrations` runs Flyway once per bounded context, each in its own schema
  with its own history table (`platform`, `robot`, `presence`, `conversation`, `settings`);
  `platform` creates the `vector` extension in `public` when it is available; `settings.setting`
  (design 5.4) is the first table. `marvin.db.mode=external` uses `spring.datasource.*` (the Docker
  container); `embedded` starts zonky's PostgreSQL with its data in `marvin.data-dir/pg`.
- **ArchUnit** (`marvin-app` tests): design 3.4 plus the bounded-context rule, a Spring-free
  application layer, stricter domain purity, no system clock, ports are interfaces, no module
  depends on `marvin.host.app`. `ArchitectureRulesBiteTest` proves the context rule fails on a
  fixture that reaches into another context's entity.
- **marvin-contracts**: generators under `tools/` and their output under `golden/` (see its
  [README](marvin-contracts/README.md)): 47 protocol vectors plus 3 invalid datagrams, 4 golden
  recordings with events, states, revolutions and receiver counters, 62 API exchanges and both SSE
  streams recorded from the Python demo, face vectors (9 expressions, 973 scenario steps, icon);
  `voice.proto` compiled to Java (`marvin.host.contracts.voice.v1`) with the gRPC stubs.
- **`./marvin`** (POSIX sh, repository root): `up`, `demo`, `down`, `status`, `logs`, `doctor`.
- **CI** (`.github/workflows/ci.yml`): Python tests and a golden-files freshness check; Java
  `./mvnw verify` on Temurin 25 (Testcontainers on the runner's Docker), `./marvin doctor`.

### Decisions and deviations from the design

1. **A `system` bounded context** (not in the design's list) holds the host's own health. It will
   also hold the sidecar supervisor's view (design 4.2) and the System section of the app (design 9).
2. **The application layer is plain Java too**, not only the domain: no Spring annotations on use
   cases; `marvin-app/HostWiring` builds them. Enforced twice (Maven enforcer, ArchUnit). This keeps
   a context extractable into a service with any framework.
3. **Bounded-context rule, precisely**: a class in `domain.X` or `application.X` may use another
   context `Y` only through `application.Y.port.in..`, `application.Y.port.out..` or
   `domain.Y.event..`; `domain.shared` is a shared kernel open to all (does not exist yet: keep it
   tiny, e.g. `Clock`, ids). Adapters may call any context's ports.
4. **Domain purity is stricter than design 3.4**: also bans `javax`, `java.io` (except
   `Serializable`, `UncheckedIOException`), `java.nio.channels`, SLF4J, Jackson, protobuf and the
   generated contracts, and `Instant.now()` / `LocalDateTime.now()` / `System.nanoTime()`. The domain
   has no logger: return what happened, the adapters log.
5. **Flyway without Boot's auto-configuration** (`flyway-core`, not `spring-boot-starter-flyway`):
   Boot's single Flyway cannot give each context its own schema and history table (design 3.3,
   10.4). New migrations go to `marvin-adapter-persistence/src/main/resources/db/migration/<context>/`;
   a new context is added to `ContextMigrations.SCHEMAS`.
6. **Embedded PostgreSQL**: zonky `embedded-postgres` (maintained, darwin-arm64v8 binaries), used
   at runtime, not only in tests. Only the binaries of the building machine are packaged (Maven
   profiles by OS and architecture in the persistence POM): the jar is 60 MB instead of 170 MB, and
   `./marvin` always builds locally. A distributed jar would need one build per platform.
   **No pgvector** in the embedded mode: `platform/V1` creates the extension only if available, and
   the health detail says "no pgvector". PostgreSQL refuses to run as root, so the embedded mode
   (and its test) does not work as root; `./marvin` says so. Port 5434 (Docker uses 5433).
7. **`GET /api/health` is new** (the Python host has no health endpoint). It is not behind the access
   key yet because nothing is (phase 1): keep it open to localhost at least, `./marvin` polls it.
8. **`voice.proto` extends the design's sketch** (design 4.3): `Filler` (fillers are core-side
   decisions, spoken by the sidecar), `RobotLink` (which robot has audio), `Utterance` and
   `RobotSound` (the app's `utterance` SSE and earcons), `ReplySpoken` (the speech half of the
   latency breakdown). `Configure` carries `VoiceSettings` (the `voice.json` keys the sidecar uses)
   and an `AudioRoute` (local sound card or the robot). Health is the standard `grpc.health.v1`.
   Package `marvin.voice.v1`; only additive changes, v2 for anything else.
9. **Python for sidecars**: `./marvin` uses `host/.venv` (created with the `voice` extra by
   `./marvin up --voice`) and passes `MARVIN_PYTHON` and `MARVIN_REPO` to the host for the phase 2
   supervisor. The design says `uv` and `sidecars/voice`; that comes with phase 2, when the sidecar
   package exists. `./marvin doctor` checks Python, the venv, the voice extra, Ollama and the model
   (`llm_model` from `voice.json`, default `qwen3:4b-instruct`).
10. **Golden recordings are gzip `.mvrec` v1** (the reader detects gzip), about 1.3 MB in total.
    Their meta header and record times are pinned (fixed wall clock and monotonic clock in
    `recordings.py`), so regenerating gives identical bytes on the same zlib.
11. **Spotless is not set up** (design 3.3 lists it): formatting rules can come with the first real
    code; it would only slow phase 0's builds down.

### Facts from the Python host that the Java host must reproduce (found while making the contracts)

- Python's `round()` rounds halves to even (`FACE_STATE`, `VITALS` encoding): use `Math.rint`. The
  vectors `face_state_rounds_half_to_even` and `vitals_rounds_half_to_even` pin it.
- `HOST_ACK` goes out with header sequence 0 and header clock 0; the host clock (µs since the
  receiver started) is only in the payload. Other host → robot messages use a per-destination
  sequence and the host clock in the header (`Receiver.send`).
- `frames.lidar_to_device` computes in **float32** (numpy), the LD2450 conversion in float64. The
  brain uses only the LD2450 and the vitals, so the golden states are float64-exact; revolution point
  counts depend on the float32 range filter (30 mm to 12 m).
- The receiver ignores everything from an address before its first `HELLO`, counts sequence gaps
  below 1 000 000 as lost, counts LDROBOT CRC failures per packet (`crc_errors`) and malformed
  `LD2450` / `VITALS` / `AUDIO_IN` payloads as `bad`. `damaged_link` exercises all of it.
- The brain drops a frame whose device clock goes back less than 1 s, and restarts (vitals lost,
  `left` "sensor restarted", state reset) when it goes back more (`robot_reboot`).
- A lidar revolution is emitted when a packet's start angle is lower than the previous packet's,
  stamped with the header clock of the datagram that closed it.
- The app: access cookie `marvin_key`; the key is accepted as `?token=`, `Authorization: Bearer` or
  the cookie; a page request with `?token=` answers 303 with the cookie and without the token in
  the URL; `/static/*`, `/icon.png`, `/favicon.ico`, `/manifest.webmanifest` are public; unknown
  `Host` names get 403; POST needs `Content-Type: application/json`, a same-origin `Origin` if any,
  at most 16 KiB. Every response carries `Cache-Control`, `X-Content-Type-Options`,
  `Referrer-Policy`, `X-Frame-Options`, and HTML a strict CSP (see the snapshots' `headers`).
- SSE: `retry: 3000` on the first message (`hello`); `/api/stream` sends `hello`, `today`, `voice`,
  `devices`, then `state` twice a second, `today` again after each `event`; compact JSON
  (`separators=(",", ":")`).
- The app shows `/face.png`, drawn on the host by `face.py` at most every `FACE_MIN_PERIOD_S`, and
  `/icon.png`. Parity needs a Java port of `face.py` + `raster.py` (the face vectors are there for
  that), or a deliberate change: draw the Home face in the browser, as the Talk panel already does
  (design 9 plans exactly that).

### Verified

- `cd host-java && ./mvnw verify`: 28 tests, 0 failures, 1 skipped (unit tests, the 11 ArchUnit
  rules and the bite test, the golden-files test; `ContextMigrationsIT` and `MarvinHostApplicationIT` on
  `pgvector/pgvector:pg18` through Testcontainers; `EmbeddedDatabaseIT` skipped as root, checked by
  hand as a normal user with `MARVIN_DB=embedded ./marvin demo`: health up, "PostgreSQL 18.6, no
  pgvector").
- `./marvin doctor` runs here (Linux x64, root, Docker, no Ollama): ready, with notes.
- `./marvin up` with no JDK 25 in the environment: downloaded Temurin 25.0.4.1 into
  `~/.local/share/marvin/jdk`, built, started `marvin-postgres`, health answered
  `{"status":"ok",...,"database":{"state":"up","detail":"PostgreSQL 18.6 ..., pgvector 0.8.6"}}`;
  `./marvin status`, `./marvin logs -n 3`, `./marvin demo` (mode `demo`) and `./marvin down`
  (host stopped, container stopped, volume kept) as expected.
- Python host: `cd host && python3 -m pytest -q`: 285 passed, 3 skipped (unchanged: nothing in
  `host/` was modified).
- The deterministic generators (`generate_all.py --no-api`) give identical bytes when run twice.

### Known gaps

- The host serves no app yet, has no UDP socket, no access key, no demo behaviour: `MARVIN_MODE`
  is only reported. All of that is phase 1.
- Not tested on macOS arm64 here (no Mac in this environment): the launcher uses only POSIX sh,
  BSD-compatible `find`/`sed`/`tar`, and the Adoptium archive's `Contents/Home` layout; the Maven
  profile `embedded-postgres-macos-arm64` selects the right binaries. Try `./marvin doctor` and
  `./marvin up` on the Mac first thing in phase 1.
- CI compares only the golden JSON and JSON Lines files: `.gz` and `.png` bytes depend on the zlib
  version. API snapshots are not regenerated in CI (their values change with time; compare shapes).
- `mvnw`, `mvnw.cmd` and `.mvn/wrapper/maven-wrapper.properties` are generated by the Maven Wrapper
  plugin (Apache-2.0) and carry no SPDX header of ours.
- `get/voice_options.json` was recorded on a machine without Ollama or voice backends: its values
  are thin; compare its shape only.

### Hints for phase 1

- Start from the contracts: port `protocol.py`, `ldrobot.py`, `ld2450.py`, `frames.py` into
  `domain.robot` and make every vector in `golden/protocol/vectors.json` pass; then the receiver
  (adapter) and `brain.py` / `events.py` (`domain.presence`) against `golden/recordings/*`, replayed
  from the `.mvrec.gz` files through a Java `.mvrec` reader. The brain's config is in
  `golden/recordings/index.json`.
- The brain is a domain service fed with device time only: keep it that way (the no-clock rule).
- The web adapter: copy `host/marvin_host/ui/static/` into
  `marvin-adapter-web/src/main/resources/static/` (served at `/static/*`, `/` and
  `/manifest.webmanifest` as today), implement the access rules above as a servlet filter, and
  compare every response with `golden/api/**` by status, content type, headers and shape.
- Put stored events and minute samples (`store.py`) in the `presence` schema, settings in
  `settings.setting`; the import of an existing `marvin.db` is a phase 1 deliverable.
- `./marvin demo` should make the Java host play `ui/demo.py`'s simulated robot and seed a past
  week, the way `marvin-host ui --demo` does, into a throwaway schema or database.
- Only one host may own UDP 47100: `./marvin doctor` already flags it.

## Phase 1a: robot link and presence

### What exists

- **Protocol v1 in the domain** (`domain.robot`, plain Java): `Wire` (header, pack/unpack),
  `Hello`, `Vitals`, `FaceState`, `AudioIn`, `AudioOut`, `HostMessages` (`HOST_ACK`, `FACE_EVENT`,
  `AUDIO_CTRL`, `SOUND`), `Ldrobot` (CRC-8, packets), `Ld2450`, `Board` (ids, names, screens,
  roles), `Extrinsics` (frames.py mounts and conversions), `SensorCalibration`.
- **`RobotLinkProcessor`** (domain): `Receiver.handle` without the socket. Checks each datagram,
  keeps the devices (keyed by sender address, so the MR60BHA2 bridge is a device of its own) and
  their counters (`LinkStats`: datagrams, lidar packets, CRC errors, radar frames, lost, bad, shed),
  assembles lidar revolutions, parses frames into `SensorFrame`s, says when to `HOST_ACK`.
- **`DeviceMonitor`** (domain): the device half of `ui/sink.py` (`UISink`): rates over 5 s, loss %,
  online/offline after 6 s, connected/disconnected/reconnected/log notices, `DeviceStatus` per device.
- **The brain** (`domain.presence.Brain`, `BrainConfig`): brain.py line by line, device clock only.
  Its published language is `domain.presence.event`: `EventKind` (moved there), `PresenceEvent`,
  `PresenceState` (immutable snapshot).
- **Use cases**: `PresenceService` (`ObservePresence`, `PresenceQuery`; events out through
  `PresenceEventPublisher`); `RobotLinkService` (`RobotInbound`, `RobotLinkQuery`, `MonitorRobotLink`;
  frames out to `SensorFrameListener`s, notices to `LinkNoticeListener`s, time from `HostClock`);
  `FaceLinkService` (`FaceLink`: `FACE_EVENT` at once, `FACE_STATE` on each tick, to screens only,
  through `RobotOutbound`).
- **Robot adapter**: `UdpRobotLink` (socket thread: record, check, `HOST_ACK` right away, tap,
  audio listeners, queue; `send` with a sequence per destination and the host clock in the header),
  `FrameDispatcher` (the Python dispatch thread: oldest scans shed first, others only when 2000 are
  queued, per-kind timings), `DatagramTap`, `mvrec.RecordingReader/Writer/Replay`, `CalibrationFile`,
  `RobotLinkRunner` (lifecycle; 1 Hz monitor tick, 10 Hz face state), `marvin.robot.*` properties.
- **Wiring** (`marvin-app`): `PresenceFeed` (robot frames → brain inputs), `PresenceEventBus`
  (events → face link, logged at info), `RobotLinkProbe` (`robot` in `/api/health`).
- **`./marvin`**: refuses to start when UDP 47100 is taken; documents `MARVIN_UDP_PORT`,
  `MARVIN_TAP`, `MARVIN_RECORD`, `MARVIN_REPLAY` (all passed through to the host).

### Decisions and deviations

1. **The receiving logic is a domain object** (`RobotLinkProcessor`), the socket and threads an
   adapter. A replay and the golden tests call the processor directly, synchronously, so they are
   deterministic, as `Receiver.handle` outside `serve()` is in Python.
2. **Robot and presence meet only in `marvin-app`** (`PresenceFeed`, through `SensorFrameListener`
   and `ObservePresence`); the brain's inputs are its own types (`TargetSighting`, `VitalsReading`).
   `FaceLinkService` (robot) reads presence through `PresenceQuery` and `domain.presence.event`, which
   the context rule allows. The event bus subscribes the face link after construction (it would
   otherwise be a bean cycle: link → inbound → presence → bus → face → link).
3. **No `SO_REUSEADDR` on the Java socket** (the Python receiver sets it). On Linux two sockets that
   both set it share a UDP port silently; without it, a Java host started beside `marvin-host run`
   fails with a clear message instead of stealing half the robot's datagrams.
4. **The tap uses one socket per robot** (loopback, ephemeral port), so the Python side still tells
   the robot from the MR60BHA2 bridge. The design names a `marvin-host viewer --tap` command; the
   existing `marvin-host run --port 47110 --no-ui` already does the job (it answers `HOST_ACK`s to the
   tap sockets, which ignore them), so the Python host is unchanged. Only valid protocol v1 datagrams
   are forwarded.
5. **Recordings**: records are byte-identical to the Python writer (tested by rewriting three golden
   files). The meta header is Jackson's JSON (not Python's `json.dumps` spacing) with
   `"marvin_host": <Java version>` and an extra `"host": "java"`; `host_start` has the same
   `+HH:MM` offset form.
6. **Lidar points** are computed with double trigonometry rounded to float32; numpy computes in
   float32 throughout, so coordinates agree within float32 rounding, not bit for bit. The range
   filter (what decides the point counts, the only thing the brain and the goldens depend on) is the
   same float32 comparison.
7. **Python number formatting** in event details (`f"{x:.2f}"`) is reproduced with `BigDecimal` on
   the exact double, half to even (`Brain.fmt`): `Locale`/`String.format` would round half up.
8. **Event data** values are all numbers, so `PresenceEvent.data` is `Map<String, Double>` in the
   Python key order.
9. **Replay inside the host** (`MARVIN_REPLAY`) goes through the dispatcher like live data: at full
   speed (`MARVIN_REPLAY_SPEED=0`) scans are shed and, past 2000 queued frames, radar frames too. Use
   speed ≥ 1 for a faithful brain; the tests use the synchronous path.
10. **Startup fails** if the UDP port cannot be bound (another host); the launcher checks it first.
11. **Calibration is read only** (`calibration.json` as the Python host writes it): lidar yaw and
    LD2450 x sign go into `Extrinsics`, the speed sign into `BrainConfig`. `calibrate` stays Python.

### Verified

- `cd host-java && ./mvnw verify`: all green (106 tests counted by Surefire/Failsafe with the
  dynamic ones; 1 skipped: the embedded-database IT as root). New: `ProtocolVectorsTest` (constants
  + every vector of `vectors.json`, both directions, byte for byte, 49), `GoldenRecordingsTest`
  (the 4 recordings: same events with same device time, detail and data; same states at every tick
  within 1e-6, 1264 states; same 1276 revolutions with point counts and intensity sums; same
  counters incl. `damaged_link`'s lost 8, crc 8, bad 2), `MvrecCompatibilityTest`,
  `PythonReadsJavaRecordingTest` (Python `record.py` replays a Java recording to the same events),
  `UdpRobotLinkTest` (handshake with `HOST_ACK` seq 0 / clock 0, frames before `HELLO` ignored, face
  link to screens only with per-destination sequence numbers, tap, shedding), `SimulatorLinkIT`
  (the Python `marvin-host sim` against the Java link over UDP: linked, >1000 lidar packets, >50
  revolutions, radar, vitals, `arrived`), domain tests, `MarvinHostApplicationIT` (a robot says
  `HELLO` to the whole host, gets its ack, the brain sees it arrive, `/api/health` lists it), ArchUnit.
  A mutation check (one character of a detail string) makes `GoldenRecordingsTest` fail.
- `MARVIN_TAP=47110 ./marvin up`, then `marvin-host sim --host 127.0.0.1` and
  `marvin-host run --port 47110 --no-ui --no-viewer --stats`: the Java host linked
  (`marvin-53494d (simulator, sim-0.1.0, simulated)`), logged `6.97 s arrived 2.73 m away`, health
  `"robot":{"state":"up","detail":"UDP 47100: marvin-53494d (simulator, simulated)"}`; the Python
  receiver behind the tap saw 10 scans/s, 10 radar/s, lost 0, and the same arrival.
- `MARVIN_REPLAY=.../robot_reboot.mvrec.gz MARVIN_REPLAY_SPEED=0 MARVIN_RECORD=/tmp/x.mvrec.gz
  ./marvin up`: the golden event sequence (reboot: `vitals_lost`/`left` "sensor restarted", then
  `arrived`); the Python `record.summarize` reads the Java recording (1384 datagrams, device record).
- `cd host && python3 -m pytest -q`: 285 passed, 3 skipped (the Python host is unchanged).

### Known gaps

- **No real hardware here**: the D1 mini (simulated sensors, board 1) and the ESP32-S3 DevKitC
  (board 2, face) were not connected; they are covered by the protocol vectors, the fake robots of
  `UdpRobotLinkTest` (board 2 gets `FACE_STATE`/`FACE_EVENT`, board 1 does not) and the simulator.
  First thing on the real LAN: `./marvin up`, power the robot, `./marvin status`.
- The app, its API and SSE streams are phase 1b: nothing shows devices, events or state yet beyond
  the log and `/api/health`.
- Audio: codecs and a socket-thread audio listener hook exist; `AUDIO_IN` relaying, `AUDIO_OUT`
  pacing and `AUDIO_CTRL`/`SOUND` use come with the voice sidecar (phase 2). The camera URL is not used.
- `UISink.scene()` (lidar reduced to 360 bins, radar trails, vitals waves and rate history) is not
  ported: phase 1b, as another `SensorFrameListener`.
- Not tested on macOS; the socket code is plain `java.net`.

### Hints for phase 1b

- `/api/robot` and the `devices` SSE: map `RobotLinkQuery.devices()` (`DeviceStatus`) to the
  Python keys (`id`, `name`, `role`, `label` = `role().label()`, `board`, `firmware`, `ip`, `rssi`,
  `rssi_bars`, `uptime_s`, `simulated`, `camera`, `audio`, `online`, `age_s`, `connected_at`,
  `rates`, `loss_pct`, `datagrams`, `lost`, `crc_errors`, `bad`, `shed` (= `stats().shedTotal()`),
  `points`). Device notices: add a `LinkNoticeListener` in `HostWiring.robotLinkService`.
- The `state` SSE: `PresenceQuery.state()`, events.py names (`t_us`, `distance_m`, `speed_cms`, ...).
  The `event` SSE and the store: `PresenceEventBus.subscribe(...)` (called on the dispatch thread:
  keep it short, hand off to the store).
- The sensor views: a new `SensorFrameListener` in the same list as `PresenceFeed`; lidar points via
  `LidarRevolution.points(extrinsics)` off the socket thread (it is already on the dispatch thread).
- `FrameDispatcher.timings()` and `maxQueueDepth()` are the System page's receiver numbers.
- Demo mode: feed `UdpRobotLink.receive(...)` (or `RobotInbound`) from `ui/demo.py`'s scene
  equivalent, or replay a golden recording in a loop (`marvin.robot.replay`) as a first step.

## Phase 1b: persistence, the app and the demo

### What exists

- **History in the domain** (`domain.presence.history`, plain Java, from `ui/stats.py`):
  `StoredEvent`, `HistoryKinds` (`host_started`, `host_stopped`, `robot_offline`, `robot_online`),
  `Folded.fold` (present and seated intervals, closed at the last sign of life after a crash),
  `Interval` (merge, clip, total), `DayStats.compute` (the day and its week-chart summary), `Sample`,
  `Words` (`duration`, `describe`, `status`). `domain.shared` now holds `PyNumbers` (Python's
  half-to-even `round` and `f"{x:.nf}"`), `LocalDays` (local days in the owner's zone) and `Clocks`
  (the clock port every context uses).
- **Settings** (`domain.settings.AppSettings`): `validate_settings` with the same messages (Python's
  `repr` of the offending value included), quiet hours; `SettingsService` (stored values that no
  longer validate are ignored and logged; each change goes to the brain's `still_long` and the app).
- **Conversation** (`domain.conversation.ConversationEntry`, schema 2 of `store.py`) and
  `ConversationService` (by local day, search); `VoiceControl` port with `UnavailableVoice` for now.
- **Face** (`domain.face`: `Canvas`, `FaceParams`, `Face`, `XorShift32`): `face.py` and `raster.py`
  ported in float32 as numpy computes them; `FaceService` draws on demand, at most 15 times a second,
  from the brain's events and state.
- **Sensor views** (`domain.robot.SensorScene`, `UISink.scene()`): lidar reduced to 360 ranges (cached
  per scan), LD2450 targets and trails, MR60BHA2 waves and rate history; `RobotLinkQuery.scene()`.
- **Use cases**: `PresenceHistoryService` (the Python `UIServer`'s store half: events stored with the
  wall clock, online after an event or a new brain state, offline after 15 s without one, a sample per
  minute, today cached 2 s), `HostLogService` (the Log panel, 500 lines), `SettingsService`,
  `ConversationService`, `FaceService`.
- **Persistence**: schemas `presence` (`event`, `sample`), `conversation` (`entry`), `settings`
  (`setting`), `platform.import`. `JdbcPresenceHistoryStore`, `JdbcConversationStore`,
  `JdbcSettingsStore` (Spring `JdbcClient`), `SqliteImporter` (sqlite-jdbc 3.53.4.0, overriding Boot's
  managed version). Demo mode: database `<name>_demo` on the same server (created on first use,
  every context schema dropped at start, only in a database named `*_demo`), embedded or Docker.
- **Web adapter**: the Python app's files byte for byte (`resources/app`), `AccessFilter` (the access
  key rules, same-origin JSON POSTs of at most 16 KiB, security headers), `AccessKey` (`ui_token` in
  the data directory, shared with the Python host), `ApiController` (every endpoint of `server.py`),
  `StreamController` (both SSE streams), `AppController` (pages, `/face.png`, `/icon.png`), `EventHub`
  (bounded client queues), `WebTicker` (today every 5 s, devices every second, while someone looks),
  `LiveState` (the `state` snapshot), `PyJson` (compact JSON with Python's float `repr`), `Png`.
- **Sidecar adapter**: `PythonRuntime` (`MARVIN_PYTHON`, `host/.venv`, `python3`: the first that
  imports the Python host), `DemoSeed` (runs `demo_seed.py`, a resource: `seed_history`,
  `seed_conversations` and today's scripted conversation into a SQLite file), `SimulatorSidecar`
  (`marvin-host sim` to the bound UDP port, restarted 5 s after it stops).
- **Wiring** (`marvin-app`): `StartupImport` (the one-time import, or the demo's seed, before the
  settings and the history are read), `HistoryLifecycle` (`host_started`, sampler, `host_stopped`),
  `DemoRobot`, `DeviceNotices` (device lines and connections in the Log panel), `HistoryLog`,
  `LogPanelAppender` (warnings and errors of any logger in the Log panel, as the Python host's
  logging handler), `SystemClocks`.
- **`./marvin`**: prints the phone address with the access key; the demo gets a Python that can run
  the Python host (a base `host/.venv` if needed); `--enable-native-access` for sqlite-jdbc.

### Decisions and deviations

1. **The face is still drawn on the host** (`/face.png`), not in the browser as design 9 plans: the
   app does not change before phase 3 (design 11). The port is pixel-exact: all 9 expressions, the
   icon, and the 973 frames of the scripted scenario have the Python CRCs (`FaceVectorsTest`).
2. **Times stay Unix seconds** (`double precision`), not `timestamptz` (design 5.4): the app and the
   history functions work in wall-clock seconds, and imported rows keep their exact values. Event data
   and conversation entries are `json`, not `jsonb`: `jsonb` reorders keys, and the app shows some
   (the latency breakdown) in their order. `settings.setting` stays `jsonb`.
3. **Setting defaults are the defaults.** The Python host reports the *current* break interval as
   the default (it reads the brain's live config); the app does not use `defaults`, the Java host
   reports 50.
4. **Import**: once per file (by absolute path, `platform.import`), in one transaction; event ids are
   kept when the event table is empty (the normal first start) so ids seen by the app stay valid;
   settings and conversation entries already in PostgreSQL win. The import runs before anything reads
   the database.
5. **Header formatting**: Tomcat writes `text/html;charset=utf-8` (Python: `text/html; charset=utf-8`);
   the icon gets one `Cache-Control: max-age=86400` (Python sends it after a `no-store` header, so it is
   never cached). A bad JSON body gets 400 with `invalid JSON: ...` instead of Python's parser message.
   Conversation search uses `ILIKE` (case-insensitive beyond ASCII; SQLite's `LIKE` is ASCII-only).
6. **No voice yet**: `GET /api/voice` answers as the Python host does without a voice (state
   `unavailable`, with its own error text), `/api/voice/options` and every voice POST answer 404
   `voice control is not available`, and `/api/stream` sends no `voice` message at start. The app
   shows "Voice needs marvin-host run", which is true until phase 2.
7. **The demo** runs at real time (the Python demo: 10 times faster), with one device, the Python
   simulator (board 255, "Simulated robot"), where the Python demo shows a robot and an MR60BHA2 radar
   fed in-process. The past week, past conversations and today's scripted conversation come from the
   Python demo code through the importer, so both demos show the same days (same seeds). The demo's
   own voice (`DemoVoice`) is not ported.
8. **The Log panel** gets warnings and errors from every logger (a Logback appender), as the Python
   host's root logging handler does; Flyway's "extension already exists" notice is silenced.
9. **Shutdown**: streams end first (phase just below the web server's), the last minute and
   `host_stopped` are written before the database goes; the embedded PostgreSQL no longer has its own
   JVM shutdown hook (it raced the host's last write); Hikari waits 5 s for a connection, not 30.
10. **`/api/health` and `/actuator/*` follow the access rules** (open from this computer).

### Verified

- `cd host-java && ./mvnw verify`: 134 tests, 0 failures, 1 skipped (the embedded-database IT as root).
  New: `DayStatsTest` (test_ui.py's statistics and words), `AppSettingsTest`,
  `PresenceHistoryServiceTest` (markers, online/offline, minute samples), `FaceVectorsTest`,
  `AppFilesTest` (the app's files are the Python host's), `PyJsonTest`, `StoresAndImportIT` (stores,
  key order, search escaping, a Python-schema SQLite imported once with ids and settings rules, the
  demo database emptied only when named `*_demo`), and `ApiContractIT`: the Java host in demo mode
  (simulator and seed from the Python host, access key `contract-key`) answers every recorded GET, POST
  and access-rule request of `golden/api` with the same status, headers (but for the two formatting differences of item 5) and shape, the
  same error messages, settings and pages; the access rules are checked from the machine's LAN
  address; both SSE streams start with the same events (`retry: 3000`) and every message matches a
  recorded shape. The voice endpoints answer as the Python host without a voice.
- `./marvin demo` (Docker PostgreSQL): the past week imported (269 events, 2682 samples, 38
  conversation entries) in under a second, the simulated robot linked, `arrived`/`sat_down`/vitals in
  the app. Screenshots of Home, Talk (desktop and phone), Robot, History and Settings from the Java and
  the Python demo side by side: same layout, same week, same timeline, same sensor views; the
  differences are the ones above (voice, one device).
- Embedded PostgreSQL, as a normal user: `MARVIN_MODE=demo MARVIN_DB_MODE=embedded` works (database
  `marvin_demo`, "no pgvector"); in live mode a Python-written `marvin.db` (events, sample, settings,
  a conversation entry) was imported once, the settings applied (30 min, 12 h clock), the Python
  host's `ui_token` accepted from the LAN address, and a restart added `host_stopped`/`host_started`
  without importing again.
- `cd host && python3 -m pytest -q`: 285 passed, 3 skipped (nothing in `host/` changed).

### Known gaps

- **The voice** (phase 2): Talk panel, voice settings and options, the transcript and live messages
  (`voice`, `transcript`, `level`, `utterance`, `partial`, `say`), the `voice` app setting.
- **Not tested on macOS** here; `AccessFilter` uses `InetAddress.getLocalHost()` once at start for
  the Host check (it can be slow on a Mac with a broken hostname resolution).
- The Settings panel's "Your data" says the history is in the data directory; with Docker it is in
  the `marvin-pgdata` volume (the app's text is the Python host's; phase 3 can say it).
- If the host is killed hard (`kill -9`), the embedded PostgreSQL keeps running until the next start
  finds its port taken; `./marvin down` stops the host cleanly.
- The Python demo's two in-process devices and its 10x clock are not reproduced (see 7).
- `FrameDispatcher.timings()` (the receiver numbers for a future System panel) is not exposed yet.

### Hints for phase 2

- **Voice**: implement `VoiceControl` (conversation context) with the gRPC sidecar, and replace
  `UnavailableVoice` in `HostWiring`. `ApiController.voicePayload` and `voicePost` are the places
  to extend (payloads in `golden/api/get/voice*.json`, `post/voice_*.json`); `/api/stream` must then
  send `voice` right after `today` (golden `first`: hello, today, voice, devices, state), and
  `ApiContractIT.NO_VOICE_GETS` and the voice test become a normal contract check.
- **Transcript**: keep entries through `ConversationHistory.add` (ids: milliseconds, above
  `ConversationStore.maxId()`); publish `transcript` and the live kinds through `EventHub.publish`.
  `/api/voice/on` and `/off` also set the app setting `voice` (the Python host does), which the
  contract's `settings_break_50` then matches without `ApiContractIT.withoutVoice`.
- **Demo voice**: `demo_seed.py` already writes today's scripted conversation; a scripted
  `VoiceControl` (the Python `DemoVoice`) is what the demo needs when there is no language model.

## Phase 2a: the Python voice sidecar

### What exists

- **`host/marvin_host/voice/engine.py`**: `VoiceEngine`, the audio machine extracted from
  `assistant.py` without changing its behaviour: capture thread and echo gate, VAD segmenter, wake
  word and listening windows, speculative STT and the filters, the mouth and speaker threads
  (chunks, synthesis, playback), barge-in, the live signals. `_answer(job)` is the only abstract
  step. `VoiceAssistant(VoiceEngine)` (assistant.py) keeps the in-process conversation: Ollama,
  tools, persona and context, history, `</think>` handling. `marvin-host run` and `talk` are
  unchanged; every existing test passes untouched.
- Small, behaviour-neutral hooks in the engine for the sidecar: `_prepare(job)` (a question exists
  before "heard" is emitted, so an answer can come back at once), `_dropped(job)` (a queued job was
  dropped by an interruption), `_interrupt(reason)` (`barge-in` or `stop`, kept on the job),
  `say(..., reply_id=)`, `busy()`, `closed`, and `uid` in the "heard" event (a question id; the
  controller ignores it). "say" events carry `reply_id` only when the job has one (never in the
  Python host, so the app's SSE payloads are unchanged).
- `control.audio_preflight(settings, microphone=)` (the `voice` extra and the microphone, without
  Ollama), `tts_available()`, `stt_available()`: shared by `preflight`/`options` and the sidecar.
- **`host/marvin_host/sidecar/voice/`**, run as `python -m marvin_host.sidecar.voice`:
  - `server.py`: `marvin.voice.v1.Voice` (Session, Transcribe, Options) and `grpc.health.v1`
    (SERVING for "" and the service), optional shared token (`--token`, `MARVIN_SIDECAR_TOKEN`,
    metadata `authorization: Bearer <token>`). One core at a time: a new Session replaces the old
    one (which gets `Status` STOPPED and ends).
  - `session.py`: `VoiceSession` (Configure → engine built in a background thread, rebuilt when
    the settings, the route or the robot change; STT and TTS reused when their own settings did
    not change; ERROR with a one-line fix when something is missing) and `EngineFactory`.
  - `engine.py`: `SidecarVoice(VoiceEngine)`: `Heard` → waits for `ReplyStart` for that uid →
    `TextPiece`s through the same `SentenceSplitter` → `SayProgress`, `ReplySpoken` (text,
    latencies) or `Interrupted`. Proactive `Say` and streamed `ReplyStart` without a question.
  - `relay.py`: `RobotRelay`, the receiver that `robot_audio.py` expects: relayed `AudioFrame`s in,
    AUDIO_OUT / AUDIO_CTRL / SOUND out as `SpeakerFrame` / `RobotAudioCtrl` / `RobotSound`.
    `RobotMicSource` and `RobotSpeakerSink` are used unchanged (gap filling, restarts, MIC_START
    every second, 150 ms lead, stream ids, PLAY_STOP).
  - `fake.py`: the test mode (`--fake --say SECONDS:TEXT --fake-speed X`): scripted microphone,
    a Whisper that recognises the script by pitch (phrase k at 110 + 20k Hz), `FakeTTS`,
    `EnergyVad`.
  - `cli.py`: prints `READY port=<port>` on stdout when listening (`--port 0` lets the system
    choose), logs on stderr, SIGTERM/SIGINT stop gracefully (STOPPED status, grace 2 s).
  - `contract/`: generated from `voice.proto` by `host/scripts/gen_voice_contract.py` (committed;
    `--check` in a test keeps them in step). Needs grpcio ≥ 1.84 and protobuf 7.x (the generated
    code checks both at import).
- `pyproject.toml`: extra `sidecar` (grpcio, grpcio-health-checking, protobuf); `dev` includes it
  and grpcio-tools, so the Python CI job runs the sidecar tests. The Java CI job installs
  `host[sidecar]` for phase 2b's tests.
- Docs: docs/voice.md "The voice sidecar" (messages of one turn, commands, robot route, test mode),
  host/README.md layout.

### Decisions and deviations

1. **`marvin_host/sidecar/voice` instead of `sidecars/voice`** (design 4.1, 11). The sidecar must
   share the audio machine with `marvin-host run/talk` (the Python host stays for the simulator,
   the viewer and replay, and keeps its in-process voice). A separate distribution would duplicate
   `voice/`, `audio.py`, `robot_audio.py` and `protocol.py` or depend on the whole host anyway. One
   package, one test suite, one `pip install -e ".[sidecar,voice]"`; the extra keeps gRPC optional
   for the Python host. The process boundary (and Piper's GPL isolation, design 4.1) is the same.
   A `uv`-managed environment can still point at this package.
2. **Contract additions** (additive, still `marvin.voice.v1`): `CoreToVoice.ask` (`Ask`: typed
   questions go through the sidecar, which guesses the language and emits `Heard` source "typed",
   exactly as `VoiceAssistant.ask`), `Status.listen_s` (seconds left in a listening window, the
   app's `listen_s`), `Interrupted.utterance_uid` and reason "busy"/"off",
   `ReplySpoken.text` and `ReplySpoken.utterance_uid`. `Heard.uid` is a question id unique in the
   session (not the segmenter's utterance uid, which `Utterance`/`Partial` carry): typed questions
   need ids too.
3. **Where `</think>` and tool payloads are handled**: in the core (design 4.3). `TextPiece.text`
   is the answer text only; the sidecar splits and cleans it (`SentenceSplitter`,
   `clean_for_speech`). The Java conversation service must port the held-back/`</think>` logic of
   `VoiceAssistant._answer` (and `looks_like_payload`, `payload_tool_calls`, `strip_thinking`).
4. **Errors**: `ReplyEnd.error` ("llm_down" or "error") with nothing said makes the sidecar say the
   persona sentence (`persona.phrase`), as `VoiceAssistant` does. No `ReplyStart`/piece for 30 s
   (`--reply-timeout`) is treated as "error".
5. **Latencies**: `Heard.latency` has the listening stages (endpoint, queue, stt, speculative,
   wake); `ReplySpoken.latency` the speaking ones measured in the sidecar: `reply_start` and
   `first_chunk` (from the start of the answer job, so they include the core's model time),
   `filler_start`, `tts`, `audio_start` and `total` (from the end of the question, as in Python).
   The core adds its own (`llm_first_token`, `tools`, `llm_first_token_2`) and computes
   `first_word_s = endpoint + audio_start` as `VoiceController` does.
6. **Proto3 defaults**: `VoiceSettings` cannot tell 0 from unset. Empty strings, and 0 for
   `echo_tail_s`, `listen_window_s`, `end_silence_ms`, mean the default; `follow_up_s` 0 is kept (no
   follow-up window). The core must send every bool (`wake`, `speculative_stt`, `chime`...)
   explicitly.
7. **Robot route without a robot**: `Status` ERROR "No robot with a microphone and a speaker is
   connected" with a fix; the engine is built as soon as a `RobotLink` with `has_audio` arrives,
   and rebuilt (ERROR again) when it leaves.
8. **Proactive speech**: `Say` or a `ReplyStart` without `utterance_uid` follow
   `VoiceAssistant.say`: skipped during a conversation (`Interrupted` "busy") unless `force`; a
   forced one is queued after the answer in progress.
9. **Test mode by pitch**: the fake Whisper recognises phrases by their fundamental frequency, so
   speculation, the wake-word pre-filter and the echo gate all run on real audio; a Java test can
   feed the robot route with the same synthetic speech.

### Verified

- `cd host && python3 -m pytest -q`: 302 passed, 3 skipped (285 before + 17 sidecar tests);
  `test_voice.py`, `test_voice_tools.py`, `test_voice_control.py`, `test_ui_*` untouched and green.
- `tests/test_voice_sidecar.py` (real gRPC on localhost, a fake core): contract up to date;
  settings mapping; fake script; relay translation; health, options, Transcribe (and a bad clip);
  a heard question → `Heard` (text without the name, raw, language, latency) → reply streamed in
  two pieces → two `SayProgress` with envelopes → `ReplySpoken` (text, latencies) → THINKING,
  SPEAKING, IDLE; typed question with filler and `llm_down`; a new question and Stop interrupt
  (reasons, uids, the late answer dropped); proactive say, streamed proactive reply, "busy",
  forced say after the answer; mute before Configure, Talk now (`listen_s`), stop listening;
  **robot route**: ERROR without robot, MIC_START after `RobotLink`, relayed AUDIO_IN heard, speaker
  frames contiguous in one stream, ≤ 480 samples, paced over the audio's duration, MIC_STOP and
  ERROR when the robot leaves; session replacement; commands while off; reply timeout; token;
  the process (`READY port=`, health SERVING, SIGTERM exit 0); a voice barge-in while thinking.
  Five repeated runs and runs under CPU load: stable (~8 s for the file).
- `./mvnw -q -o -pl marvin-contracts -am verify`: green with the proto additions.

### Known gaps

- **No real audio or models here**: MicSource/SpeakerSink, faster-whisper, MLX, Piper and `say`
  were not exercised through the sidecar in this container (the code paths are those of
  `marvin-host run`); macOS not tested.
- The core side does not exist yet: no supervisor, no Java client, no conversation service.
- `Transcribe` uses the current session's settings (or the defaults) and the live loop's STT
  under one lock: a long batch clip delays live recognition while it runs.
- `Options` reports backends and voices only; Ollama models stay a core concern (phase 2b).
- The chime is PCM on both routes (the robot's `SOUND` "wake" earcon is not used, as in Python).

### Hints for phase 2b

- **Supervisor**: spawn `$MARVIN_PYTHON -m marvin_host.sidecar.voice --port 0` (plus `--token`),
  read `READY port=N` from stdout, then gRPC health; stderr lines are the logs. Missing gRPC prints
  a one-line fix and exits 2. `./marvin up --voice` should install `host[sidecar,voice]`.
- **Session**: open `Voice.Session`, send `Configure` (all bools explicit, `contract_version` 1,
  route LOCAL or ROBOT), then `RobotLink` for each robot with the audio flag and relay AUDIO_IN as
  `AudioFrame` (device name, sample index, header clock); send `SpeakerFrame`/`RobotAudioCtrl`/
  `RobotSound` back as AUDIO_OUT/AUDIO_CTRL/SOUND to that device.
- **Answering**: on `Heard`, build the messages (persona, context, history), stream the model, and
  send `ReplyStart(reply_id, language, utterance_uid=Heard.uid)`, `TextPiece`s, `Filler` for online
  tools (`persona.phrase("checking")`), `ReplyEnd(error)`. Stop generating on `Interrupted` for that
  uid/reply. Record the transcript from `Heard`, `Ignored` and `ReplySpoken` (+ core latencies);
  forward `Level`, `Utterance`, `Partial`, `SayProgress` to SSE as `level`, `utterance`, `partial`,
  `say` (drop `reply_id` from `say` for payload parity) and map `Status` to `/api/voice`.
- **End-to-end tests**: run the sidecar with `--fake --say ...` (or feed `fake.speech(k, s)` on the
  robot route) and a stub model; typed questions only need `Ask`.

## Phase 2b: the conversation in Java, the supervisor, parity

### What exists

- **Conversation domain** (`domain.conversation`, plain Java, ported line for line and checked
  against the Python host's own output, `golden/conversation/vectors.json` made by
  `marvin-contracts/tools/conversation_vectors.py`): `Persona` (system prompt per language, with or
  without tools; context block with simulated sensors, vitals, recent events, the home place; the
  user message; the phrases said without the model), `SpeechText` (`looks_like_payload`,
  `strip_payload`, `payload_tool_calls`, `strip_thinking`, `clean_for_speech`, `guess_language`),
  `SentenceSplitter`, `ToolCall`, `ChatMessage`, `ConversationMemory` (append-only, halved by turns,
  forgotten after 3 minutes), `ProactiveSpeech` (break reminders, welcome back, one per 10 minutes),
  `VoiceSettings` (voice.json keys, `validate` with the Python messages, app settings and defaults,
  `VoiceConfig`), `VoiceSnapshot`; `tool.ToolSpec`, `ToolArguments`, `ToolResult`, `ToolError`,
  `Weather` (schema, WMO words, place choice, the result the model reads). `domain.shared.JsonText`:
  JSON for the domain (`raw_decode`, and `json.dumps` with Python's separators and float `repr`).
- **Use cases** (`application.conversation`): `AnswerLoop` (`VoiceAssistant._answer`: the model
  streamed, held-back payloads, the streaming `</think>` guard, tool rounds with fillers, the
  no-answer sentence, models without tools asked again without them, latency), `VoiceService`
  (`VoiceController` + the conversation half of `VoiceAssistant`: Ollama check and the rehearsal at
  start, the session, turns, transcript entries with latency and inspector data, interruptions,
  proactive speech, settings, commands, options), `tools.ToolRegistry` (offered tools, constant
  schemas, safe calls with timeouts), `tools.WeatherTool` (Open-Meteo, places kept for the session,
  forecasts 10 minutes). Ports: `LanguageModel`, `VoiceSidecar` (settings, signals, commands,
  options), `VoiceSettingsStore`, `JsonFetcher`, `VoiceListener`; `VoiceControl` is the app's.
- **Adapters**: `OllamaLanguageModel` (Spring AI's low-level `OllamaApi`: streamed `/api/chat` with
  `think: false`, `temperature` 0.6, `num_predict` 200, `num_ctx` 8192, `keep_alive` 30m; errors
  worded as `llm.py`), `HttpJsonFetcher` (the tools' HTTPS, system proxy); `VoiceSettingsFile`
  (`voice.json` in `$MARVIN_CONFIG_DIR`, `$XDG_CONFIG_HOME/marvin` or `~/.config/marvin`, written as
  Python writes it); `SupervisedProcess` (start, READY line, logs by level, backoff 1 s → 60 s,
  SIGTERM then SIGKILL after 5 s, JVM shutdown hook), `GrpcVoiceSidecar` (the process, health check,
  a random token, one session kept open while wanted and re-opened after a restart, robot links
  replayed, `AUDIO_IN` relayed, speaker frames / controls / sounds out, options),
  `SimulatorSidecar` on the same supervisor.
- **Web**: every voice endpoint of `server.py` (`/api/voice`, `/options`, `/settings`, `/on`
  and `/off` also set the app's `voice` setting, `/ask`, `/listen`, `/mute`, `/stop-speaking`, the
  same messages and statuses), `voice` right after `today` on `/api/stream`, the voice's `voice`,
  `transcript`, `level`, `utterance`, `partial`, `say` messages through `EventHub`.
- **Wiring** (`VoiceWiring`): the sidecar starts with the host, the voice too if it was on; the
  voice's errors and notes in the Log panel; `RobotAudioRelay` (robot link ↔ sidecar); `voice` in
  `/api/health`.
- **`./marvin up`** sets up `host/.venv` with `host[sidecar,voice]` the first time (`--no-voice`
  skips it), `./marvin demo --voice` likewise; `doctor` checks gRPC too. Docs: README, host/README,
  host-java/README, docs/voice.md ("Two hosts run this voice", "With the Java host").

### Decisions and deviations

1. **Sentences are cut in Java too.** The `</think>` guard and the held-back logic decide on
   sentences (what "was said" when the tag comes), and the history keeps what the model said, so
   `AnswerLoop` runs the same `SentenceSplitter` as `assistant.py` and sends each sentence as a
   `TextPiece` ending with `\n`; the sidecar's splitter then releases each piece at once (a line
   break ends a sentence). Sentences that clean to nothing (JSON, markup) are not sent.
2. **Spring AI's `OllamaApi`, not `ChatModel`/`ChatClient`**: those run tools themselves; the tool
   loop, fillers, held-back payloads and `</think>` are Marvin's. Differences from `llm.py`'s body:
   `"tools": []` when there are none (Ollama reads it as none; warm-up and questions still send the
   same body), key order, no `tool_call_id` (Ollama uses `tool_name`). Spring AI throws on a final
   chunk without `done_reason` and ignores an in-stream `{"error": ...}` line; both surface as
   "cannot reach Ollama" (Ollama sends `done_reason` and reports errors by status).
3. **The sidecar process runs with the host**; the voice on or off opens or closes its session. The
   settings panel can list the voices while the voice is off, and turning it on skips a process
   start. Health reports it `disabled` (not `down`) while it does not serve: the host is healthy
   without a voice.
4. **Start checks** as `control.preflight`: Ollama must answer and have the model; the audio checks
   (voice extra, microphone, robot) come from the sidecar's `Status` ERROR with its fix. Start, stop
   and restart run one at a time, in order; a newer one supersedes an older one still running.
5. **Latency breakdown** = the sidecar's listening stages (`Heard`), the core's (`llm_first_token`,
   `tools`, `llm_first_token_2`, `first_chunk`), the sidecar's speaking stages (`tts`, `audio_start`,
   `filler_start`, `total`); `reply_start` is dropped; `first_word_s` = endpoint + audio_start.
   The reply's text is what the sidecar said (`ReplySpoken.text`). On `llm_down` the sentence is said
   only when nothing else was (Python appends it). A question dropped before it was answered
   (`Interrupted` with no `ReplySpoken` within 3 s) leaves no reply entry, as in Python.
6. **Proactive speech** is skipped in the core while a turn runs or the voice thinks or speaks
   (Python asked the engine); a skipped reminder may come at the next `still_long`.
7. **Commands answer with the new state**: `/api/voice/listen` (0.5 s), `/ask` and `/mute` (0.3 s)
   wait for the sidecar's next `Status`, so the answer shows `listening` and `listen_s` as Python's
   synchronous engine did.
8. **New voice.json keys**: `audio_route` (`computer`, the default and Python's behaviour; `robot`;
   `auto`: the robot when one with audio is connected) and `chime` (read by the sidecar); the Python
   loader knows both (no "unknown keys" warning).
9. **Contract addition**: `VoiceChoice.locale` (the app shows macOS voices with their locale); the
   Python sidecar fills it; the generated code was refreshed.
10. **The application layer logs** through `java.util.logging` (no dependency; Boot bridges it to
    SLF4J): model failures, tool calls, proactive skips, warm-up timings, as the Python modules log.
11. **Process pipes are read on platform threads.** A blocking native read pins a virtual thread's
    carrier; with two CPUs, the voice sidecar's two pipes took both carriers and the web server
    (virtual threads) stopped answering. Found by running `./marvin up`; `SupervisedProcessTest`
    checks it.
12. **Build**: `grpc-netty-shaded` and `grpc-services` (health client; without its Gson and
    protobuf-util), Guava pinned to `33.6.0-jre` (gRPC mixes flavours), Gson 2.14.0 (gRPC needs more
    than Boot manages); `marvin-contracts` is now a runtime dependency of the app.
13. **The contract test runs the real voice** (the sidecar in `--fake` mode, a stand-in for Ollama,
    canned Open-Meteo): every recorded POST in the recorded order with the voice on, then Talk now, a
    typed weather question and the after-turn GETs. Two tolerances, both in the test: latency objects
    are compared as objects of numbers (their stages depend on the path), and a reply's `tools` is
    accepted (the Python recording's scripted answer had no tool call; the real Python voice adds it).
14. **Demo**: the voice is the real one; the Python demo's scripted voice (`DemoVoice`) is not
    ported. The Talk panel's "Voice needs marvin-host run" no longer shows.

### Verified

- `cd host-java && ./mvnw verify`: 183 tests, 0 failures, 1 skipped (embedded database as root).
  New: `ConversationVectorsTest` (domain vs Python: 8 prompts, 21 phrases, 32 context blocks, text
  cleaning, 48 splitter runs, languages, settings checks and messages, the tools list byte for byte,
  memory halving, proactive speech), `ToolVectorsTest` (12 weather answers, errors, record, cache),
  `AnswerLoopTest` (test_voice_tools.py's loop cases: one tool call, offline tool, unknown tool,
  capped rounds, tool call as text, `</think>` after a tool result and while streaming, first clause,
  model down, no tool support, cancel), `VoiceServiceTest` (13: Ollama checks, rehearsal, turn and
  entries, failure, interruption and dropped question, reminders, live signals, commands while off,
  settings and restart, robot route, options, today's entries back), `OllamaLanguageModelTest`
  (stub Ollama: body and options, streaming, tool calls, cancel, 400 tools / 404 / 500 / refused),
  `VoiceSettingsFileTest`, `SupervisedProcessTest` (backoff, logs, SIGTERM, SIGKILL after 5 s,
  platform threads), `GrpcVoiceSidecarIT` (real Python sidecar: heard question answered with
  streamed text, options, **robot route** with relayed synthetic `AUDIO_IN` heard and speaker frames
  out, MIC_START/MIC_STOP, restart after a kill), `VoiceEndToEndIT` (host + real sidecar + stub
  Ollama: a spoken question → heard entry, streamed reply said and kept with latency, context,
  prompt, model; the rehearsal body equals the question's; SSE `level`, `utterance`, `partial`,
  `say`, `transcript`, `voice`), `ApiContractIT` with the voice (above), ArchUnit green.
- `cd host && python3 -m pytest -q`: 302 passed, 3 skipped. `conversation_vectors.py` is
  deterministic (in `generate_all.py --no-api`, so CI checks it).
- `./marvin demo` with the sidecar's test arguments (`marvin.sidecar.voice-args`) and a stand-in Ollama: scripted
  questions heard, answered and spoken; a weather question called the real Open-Meteo (tools 1.7 s)
  with the filler first; SSE carried ~16 `level`/s, `say` with envelopes, `transcript`, `voice`.
- `./marvin up` from scratch (as root, Docker): JDK downloaded, `host/.venv` created with
  `host[sidecar,voice]`, host healthy (`voice` up after start), voice on → "No microphone (PortAudio
  library not found)" with the fix (no sound card here), options list Ollama's models; one line per
  voice error in the Log panel.

### Known gaps

- **No real audio or models here**: microphone, speakers, faster-whisper, MLX, Piper and `say` were
  not exercised (no sound card, no Ollama model in this container); macOS not tested.
- **Robot audio** tested with the sidecar's test mode and synthetic frames through the real relay,
  not with the robot's firmware.
- `marvin-host run --voice-*` command-line overrides have no Java equivalent (voice.json and the
  app only). `Transcribe` is not used by the core yet. Quiet hours do not silence reminders (as in
  Python).
- README.fr.md still describes `marvin-host` as the way to run the host.
- `/api/voice/options` answers without backends and voices (only `auto`) while the sidecar is not
  serving yet (the first seconds after start).

### Hints for phase 3

- The context block is `Persona.contextBlock`; the memory context assembler (design 5.3) replaces it
  and `ConversationMemory` (in-memory, per host run) becomes the event log + episodes. Keep the
  system prompt constant and the history append-only (the rehearsal in `VoiceService.warmUp` must
  keep matching real questions).
- New tools: a `ToolRegistry.Tool` (a `ToolSpec` + `ToolFunction`) added where `WeatherTool.registry`
  builds the list; their schemas must stay byte-stable for a given setting.
- The app may change from phase 3: the face drawn in the browser, a System panel from
  `SupervisedProcess` (state, restarts, last lines) and `FrameDispatcher.timings()`.

## Review fixes

A review of phases 0 to 2 (parity, architecture, operability) found 1 blocker, 11 majors and 9 minors.
All were addressed; the few parts done differently from the suggestion say why.

### Fixed

- **DNS rebinding (blocker)**: the Host check compared only the first DNS label with the machine's
  short name, so `<hostname>.attacker.example` pointed at 127.0.0.1 got the whole API. Both hosts now
  accept only whole names: IP literals, `localhost`, `*.localhost`, the full hostname, the short name
  and `<short>.local` (`AccessFilter.ownNames`, Python `server._own_names`). **Deliberate change in both
  hosts**, so they stay at parity. Tests: `ApiContractIT.aRebindingDomainNamedLikeThisMachineIsRefused`
  (GET and a same-origin POST with `Host: <short>.evil.example` get 403, `<short>` and `<short>.local`
  get 200), `test_ui.py::test_access_key`. Checked on the running host: `vm.evil.example` 403, `vm` and
  `vm.local` 200.
- **Demo isolation**: in demo mode `voice.json` is a copy in `<data-dir>/demo/voice.json`, remade at
  every start from the owner's (or `{}`), as the Python `demo_voice` does
  (`VoiceSettingsFile.demoCopy`). `ApiContractIT.theDemoLeavesTheOwnersVoiceSettingsAlone`; checked with
  `./marvin demo`: a POST of `follow_up_s` created no `~/.config/marvin`. `MarvinHostApplicationIT` now
  uses a temporary data directory too.
- **Voice turns in order**: each turn waits for the previous one to be over (said or dropped, and
  remembered) before it builds its messages, as the Python host's single worker; a question the voice
  dropped while it waited is not answered. While a newer question waits, the grace for an interrupted
  turn's `ReplySpoken` is 1 s instead of 3 s (the sidecar sends it right after `Interrupted`; only a
  dropped question gets none). Tests: `aQuestionThatInterruptsAnAnswerIsAskedWithThatAnswerInItsHistory`
  (the second request carries the first question and the partial answer ending with " …", and the
  second model call waits), `aQuestionDroppedWhileWaitingIsNotAnswered`.
- **`listen_s`** counts down from the last `Status` (monotonic time of arrival), `null` unless
  listening (`listenSecondsCountDownFromTheLastStatus`).
- **Start checks in Python's order**: the session opens first; when Ollama or the model is missing,
  the voice's first status (up to 2 s) wins if it is an error, then the session is closed. Here:
  "No microphone (PortAudio library not found)" with its fix, as the Python host
  (`theAudioProblemComesBeforeTheModelServer`).
- **History off the frame thread**: `HistoryWriter` (application layer), a bounded queue (10 000)
  drained in order by one thread that retries a failing write with backoff (0.5 s to 30 s); the brain's
  thread only enqueues. Minute samples are taken and reset under the lock and written outside it, so an
  outage no longer merges minutes. At stop the queue gets 5 s to drain. Test:
  `aStoreOutageNeitherHoldsUpTheBrainNorLosesEventsOrMixesMinutes`. Checked with `docker stop
  marvin-postgres` during the demo: no "subscriber failed", no scans dropped, one "retrying" warning;
  after `docker start` the events were stored and readiness went back to 200.
- **Robot audio behind ports**: `RobotAudioService` (application, robot context) implements
  `RobotAudioIn` (port in) and `LinkNoticeListener`, sends through `RobotOutbound`, finds devices through
  `RobotLinkQuery`, and talks to the voice through the new `conversation.port.out.VoiceRobotAudio`
  (robot links, microphone frames, a `RobotSpeaker` callback). `RobotAudioRelay` and the adapter's
  `GrpcVoiceSidecar.RobotAudio` are gone. New ArchUnit rules: adapters depend on the application only
  through `port.in`/`port.out`; classes of `marvin.host.app` use adapters only in `@Configuration`
  classes, except the four lifecycle classes that predate the rule (`DemoRobot`, `DeviceNotices`,
  `RobotLinkProbe`, `StartupImport`: they start/stop adapters or probe the socket; listed by name, so
  any new glue fails).
- **`HostLog.CAPACITY`** replaces `HostLogService.SIZE` in the web adapter.
- **Observability**: `spring-boot-starter-opentelemetry` (Boot 4.1.1 managed: OpenTelemetry 1.62,
  Micrometer Tracing 1.7). A `Tracing` port (`system.port.out`) with a Micrometer adapter
  (`MicrometerTracing`): one span `marvin.question` per turn, from the question to the end of the
  reply, with the utterance id, source, audio route, language, interruption and every latency stage as
  attributes; the utterance id etc. in the MDC, trace and span ids in every log line (Boot's
  correlation pattern). Sampling 1.0; nothing is exported unless
  `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` is set; OTLP metrics and logs export off.
- **Readiness**: the readiness group is `readinessState,db` (503 without the database; liveness has no
  external dependency). Every `ComponentProbe` is an Actuator indicator under `marvin/<name>`
  (disabled → `UNKNOWN`, which does not bring the host down); `show-components: always` (the endpoint
  is open to this computer only, as the whole API).
- **Import per context**: `presence`, `settings` and `conversation` each import their own tables in
  their own transaction and record it in their own `<schema>.import` (Flyway V2 in each); the
  orchestrator keeps the summary in `platform.import` (an older `platform.import` row still means
  "done"). `SchemaOwnershipTest` fails when a store or a context's import names another context's schema.
- **Launcher (`./marvin`)**: the voice's packages are checked with `importlib.util.find_spec` (no more
  reinstall on every start where PortAudio is missing); a failed voice install warns and starts without
  the voice; the venv is set up before PostgreSQL, so a failed start leaves nothing running; a venv
  without pip is rebuilt; `python3 -m venv` failures and a missing `python3-venv` are named (doctor
  too); doctor notes a missing PortAudio on Linux. The database choice is recorded in
  `<data-dir>/db_mode` on the first successful start: with `docker` recorded and Docker down, macOS
  gets `open -ga Docker` (60 s), elsewhere a clear error; with `embedded` recorded and Docker up, it
  says where the data is. `start_postgres` checks `docker port` even when the container runs and
  recreates a container without its port (data in the volume); `docker start` failures get an
  `error:` line; `MARVIN_PG_PORT`. `./marvin restart`; `up` says when the code is newer than the
  running jar; `status` reads `DB=` and the recorded mode; `host.log` rotates past 10 MB; `port_busy`
  binds the port (any listener, HTTP or not). A stale `host.pid` also kills leftover sidecars.
- **Failed database connection at start**: `DatabaseUnreachableAnalyzer` (a Boot `FailureAnalyzer`)
  prints "cannot reach PostgreSQL at <url> (Connection refused ...)" and "./marvin status, then
  ./marvin doctor" instead of the Flyway/Hikari trace in the launcher's output.
- **Sidecars exit with the host**: `PythonRuntime` sets `MARVIN_EXIT_WITH_PARENT=stdin` and keeps the
  child's stdin pipe open; `marvin_host/parent.py` reads it to the end in a daemon thread and exits
  (voice sidecar and `marvin-host`, hence the simulator). `tests/test_parent.py` kills a stand-in host
  with SIGKILL and checks the child is gone within 2 s; checked with `kill -9` on the Java host: both
  sidecars gone within 3 s.
- **Error text**: `NetErrors.reason` walks the whole cause chain: "Connection refused" when any link is
  a `ConnectException`, else the deepest message (`OllamaLanguageModel`, `HttpJsonFetcher`).
- **Memory**: default JVM options `-XX:+UseSerialGC -Xmx384m` (`MARVIN_JAVA_OPTS` overrides).
  Measured in live mode, idle, 90 s after start: 406 MB RSS with ZGC and `-Xmx512m`
  (`SoftMaxHeapSize=192m`), 250 MB with SerialGC 384 MB, 209 MB adding `-XX:TieredStopAtLevel=1`
  (not kept: C1 only). Idle CPU under 1 %.
- **A racy test**: `SupervisedProcessTest` expected two restarts when the second exit had just been
  seen (the second restart comes 2 s later); it now expects at least one.
- **Build output**: `host-java/.mvn/jvm.config` has `--sun-misc-unsafe-memory-access=allow`: no more
  `sun.misc.Unsafe` warnings from Maven's Guice on JDK 25.

### Not done, and why

- **Live `event` SSE with a provisional id** (suggested): the app de-duplicates and orders events by id
  (`app.js` `addEvent`: `if (e.id <= app.lastEventId) return`), so a provisional id followed by the real
  one would hide or duplicate events. The event is published by the writer right after its insert
  (milliseconds normally); during an outage it is published when the store takes it, not lost.
- **W3C `traceparent` to the voice sidecar**: the voice runs on one long-lived bidirectional
  `Session` stream, so call metadata carries one context for the whole session, not one per question.
  Per-question propagation needs a `trace_parent` field on `ReplyStart`/`Heard` (an additive
  `marvin.voice.v1` change) and the Python side reading it: phase 3, with the sidecar's own spans.
- **Device name in the MDC**: `Heard` does not say which device heard it; the span carries the audio
  route (`computer` or `robot`) instead.
- **One database role per context** (GRANTs limited to its schema): with a single deployable this adds
  setup for no isolation gain yet; `SchemaOwnershipTest` enforces the boundary in code. Do it when a
  context is extracted.
- **`/api/state` and `today` during a database outage** answer 500 after Hikari's 5 s (the day
  statistics are read from the store). Not in the review; a cached last day would fix it.
- **The broken-venv and offline-install paths of the launcher** were checked by reading and by the
  normal path only (rebuilding `host/.venv` takes minutes and the network); the stale-port container,
  the stale jar notice, restart, `kill -9` and the database outage were run for real.

### Verified

- `cd host-java && ./mvnw verify`: 200 tests, 0 failures, 1 skipped (embedded database as root). New:
  `NetErrorsTest`, `SchemaOwnershipTest`, `DatabaseUnreachableAnalyzerTest`, 5 `VoiceServiceTest`
  cases, the outage case of `PresenceHistoryServiceTest`, 2 `ApiContractIT` cases, 2 ArchUnit rules.
- `cd host && python3 -m pytest -q`: 305 passed, 3 skipped (new: `test_parent.py`, the rebinding case).
- `./marvin demo`, `./marvin up`, `./marvin restart`, `./marvin status`, `./marvin doctor` and the
  scenarios above, on Linux x64 as root with Docker.

### Hints for phase 3

- The outbox shape is there (`HistoryWriter`): the event log can replace the store behind it.
- Add `trace_parent` to the voice contract and spans in the sidecar; the core's span is already open
  when `ReplyStart` is sent.
- Move the four listed lifecycle classes behind ports when the System panel comes, then empty
  `ArchitectureTest.BOOT_GLUE_KNOWN`.

## Final verification

An end-to-end run of both entry points from a clean state (no container, no volume, a data directory
with only a Python host's `marvin.db`), with the Python simulator as the robot, a second simulated
device, a stand-in Ollama and the voice sidecar in its test mode (`MARVIN_VOICE_ARGS=--fake`). The
scripts are kept in `host-java/e2e/` (see its README). Nothing was broken, so no production code
changed in this stage.

### What exists

- `host-java/e2e/`: `stub_ollama.py` (`/api/tags`, `/api/show`, streamed `/api/chat` with a
  `get_weather` tool call, a lone `</think>` after the tool result and a `<think>` block),
  `second_device.py` (an ESP32-S3 DevKitC that counts `HOST_ACK`, `FACE_STATE`, `FACE_EVENT`), `e2e.py`
  (Playwright walk of every section at 390x844 DPR 2 and 1440x900, fails on any console error).
- `docs/test-plan.md`: the owner's manual plan on a Mac with the real boards, voice and model.
- `docs/review-guide.md`: reading order, module map, where parity is proven, known gaps.

### Verified

- `./marvin demo` (voice in test mode, scripted "Marvin bonjour"): `e2e.py` 23/23 (the lidar check
  was made robust between runs: the canvas is compared, not the point count). The demo keeps its own
  database and `voice.json`.
- `./marvin up` from a clean state: PostgreSQL created, the SQLite copy imported once
  (`269 events, 2682 samples, 30 conversation`), the imported break interval (42 min) and a marker
  sentence found by the conversation search. `e2e.py` 23/23 at both sizes, no console errors:
  Home, Robot (the lidar redrawn from `/api/robot/stream`), History (search, previous day), Settings,
  Talk (voice switched on in the app, typed question answered over SSE, weather tool with the real
  Open-Meteo forecast and the `</think>` leak removed, `<think>` block removed, inspector, Talk now,
  mute and unmute, stop while speaking), a voice setting applied and written to `voice.json`.
  Screenshots: 36 files, `demo-*` and `live-*`, phone and desktop.
- SSE: `/api/stream` sends `hello`, `state`, `today`, `devices`, `voice`, `level`; `/api/robot/stream`
  about 10 messages a second.
- Two devices: the S3 got `HOST_ACK` each second, `FACE_STATE` at 10.1 Hz and the brain's
  `FACE_EVENT`s; both listed (`2 of 2 connected`).
- Wake word: the sidecar's scripted "Marvin bonjour" was heard (`bonjour`, source `voice`) and
  answered, first word 0.72 s (end of speech 0.56 s, stub model).
- Restart persistence (`./marvin restart`): conversation, settings and `voice.json` kept, the voice
  came back on (remembered state), no second import.
- Parity of the imported history: the Python host (`marvin-host ui`) on a copy of the same SQLite
  file and the Java host give equal `/api/day` and `/api/conversation` for every past day and equal
  search results; `/api/history` equal except `present_s`/`seated_s` of an empty day (`0.0` vs `0`,
  the same JSON number).
- Failures: Ollama stopped: the answer is the persona's `llm_down` phrase with the fix, Settings says
  Ollama is not running, the next question after it is back is answered; the simulator killed: the
  state goes `Marvin is offline` after about 10 s (as the Python host); the voice sidecar killed
  with SIGKILL: restarted in 1 s, voice `on` again within 2 s; the real sidecar without PortAudio:
  "No microphone (PortAudio library not found)" with its fix.
- Resources, live, robot linked, voice on: 260 MB RSS.
- `cd host-java && ./mvnw verify`: 200 tests, 0 failures, 1 skipped. `cd host && python3 -m pytest -q`:
  305 passed, 3 skipped.

### Known gaps

- Not run on macOS, with the real boards, a real model, a microphone or Piper: that is
  `docs/test-plan.md`. Real first-word latency is therefore unmeasured on the Java host.
- A typed English question with no clear language gets the French filler (`Je regarde…`): the
  conversation's default language is French until someone speaks, as in the Python host.
- Going back to the Python host shows only its SQLite history; the import is one-shot (no sync in
  either direction).
- Everything listed under "Review fixes, Not done" still stands.

### Hints for phase 3

- `host-java/e2e/e2e.py` can become a CI job (demo mode, stub Ollama, fake sidecar) once Playwright
  is in the CI image.
- An export of the PostgreSQL history back to SQLite would make going back to the Python host lossless.

# Memory v1

Design phase 3 ([docs/design.md](../docs/design.md) section 5), built in stages. How memory works, for a reader:
[docs/memory.md](../docs/memory.md). One section per stage.

## Stage: write path

### What exists

- **A `memory` bounded context.** Domain (`marvin.host.domain.memory`, plain Java): `MemoryEvent`, `Sensitivity`,
  `MemorySources`, `EventFeeds` (conversation lines and brain events as log events, labelled at the source),
  `Redaction` (cards with Luhn, IBANs, values said after password/PIN/code words; `redactLikelyCodes` when the model
  flags a secret the rules missed), `Fact` (bi-temporal, `current`/`validAt`/`believedAt`), `FactCandidate` (checks:
  secrets dropped, subjects normalised, dates parsed in the owner's zone, health and money `sensitive` by rule),
  `Operation` (ADD/UPDATE/INVALIDATE/NOOP, read safely from the model), `Reconciliation` (a plan of new facts, ends
  and more sources; the owner's word wins), `Batches`, `Episode`/`EpisodeLevel`, `Block`/`BlockVersion`,
  `ProfileText` (kept lines, the size limit, diffs, removing a forgotten fact's lines), `TokenEstimator`,
  `DecayRules`, `MemorySettings`, `Vectors`.
- **Use cases** (`marvin.host.application.memory`): `MemoryLogService` (`RecordMemory`: redaction, per-source switches,
  a writer thread with batches and retries, the one-time backfill per source), `Consolidator` (one batch: extraction,
  checks, embeddings, similar facts, the model's operation, all-or-nothing writes), `NightlyPass` (missing embeddings,
  day episodes, week and month roll-ups, the profile rewrite, decay, retention, orphans), `MemoryWorker`
  (`ConsolidateMemory`: idle and nightly scheduling, yielding to the voice, backoff after failures, warm-up, state
  and reports), `MemoryAdminService` (`ManageFacts`, `ForgetMemory`, `BrowseMemory`, `MemoryHealth`),
  `MemoryExportService` (`ExportMemory`: JSON tables and Markdown), `MemorySettingsService` (`ConfigureMemory`, the
  models), `Embeddings` (the embedder's state and a size check). Out-ports: `EventLog`, `FactStore` (with a
  bi-temporal `asOf`), `EpisodeStore`, `ProfileStore`, `MemoryStateStore`, `Embedder`, `MemoryModel`,
  `VoiceActivity`, `VoiceModel`, `ModelWarmUp`, `MemoryListener`, `BackfillSource`.
- **Persistence**: schema `memory` (Flyway `memory/V1__memory.sql`): `event_log`, `fact`, `fact_source`, `episode`,
  `block_version`, `state`; HNSW indexes on the embeddings with pgvector; `real[]` and an exact scan without it
  (`ContextMigrations` chooses with a placeholder, `marvin.memory.vector=off` forces it). `JdbcEventLog`,
  `JdbcFactStore`, `JdbcEpisodeStore`, `JdbcProfileStore`, `JdbcMemoryState`, `MemoryRows`.
- **Ollama**: `OllamaMemoryModel` (streamed `/api/chat` with a JSON schema in `format`, cancelled by closing the
  stream; prompts `marvin/memory/prompts/v1/*.txt`, version `memory-prompts/1`) and `OllamaEmbedder` (`/api/embed`,
  batches of 32, a missing model says `ollama pull <model>`), both through Spring AI's `OllamaApi`.
- **Feeding the log** (`marvin-app`, `MemoryFeeds`): the conversation store every user gets is a decorator that also
  records each kept line; the presence history has one more listener; at start the conversation and presence
  histories are backfilled once through their contexts' new `after(afterId, limit)` paging (`ConversationHistory`,
  `PresenceHistory`, their stores). `VoiceActivityTracker` tells the worker when the voice is busy; the worker warms
  the voice up again through `VoiceControl.rewarm()` (new).
- **Web** (the first two routes of the memory API): `GET /api/memory/worker` (state, last report, embeddings, counts)
  and `POST /api/memory/consolidate` (`{"pass": "idle" | "nightly"}`, 202), allowed by `AccessFilter.POST_PATHS`.
- **Health**: a `memory` component (`up` with counts, the search mode and the embedding model; `disabled` with the
  fix when the embedding model is missing). `./marvin doctor` checks `bge-m3` (`MARVIN_EMBED_MODEL`).
- **Test support**: `marvin-application`'s test jar has in-memory stores with the SQL semantics
  (`memory.testing.InMemoryMemory`), a scripted `FakeMemoryModel`, `WordEmbedder`, `MemoryFixture`;
  `marvin-adapter-llm`'s test jar has `StubOllama` (chat streaming, tool calls, structured answers, deterministic
  `/api/embed`), now also used by `marvin-app`'s tests (its own copy is gone). `host-java/e2e/stub_ollama.py` learned
  `/api/embed` (the same embeddings), structured answers for memory's jobs, and chunked request bodies.
- **The evaluation set**: `marvin-adapter-llm/src/test/resources/memory-eval/` (13 conversations, French and English,
  expected facts, operations, a date and a sensitivity) and `MemoryEvaluationTest`, against the stub by default, a real
  Ollama with `MARVIN_EVAL_OLLAMA` (docs/memory.md says how).
- Docs: [docs/memory.md](../docs/memory.md) (new), design.md 5.4 "As built" and the embedding model, README (the
  context), this section.

### Decisions and deviations from docs/design.md

1. **Contexts meet in `marvin-app`, not in memory.** Memory's domain knows the other contexts' kinds by name only
   (`EventFeeds`); the glue passes plain values. Memory could have consumed `domain.conversation.event` and
   `domain.presence.event` (the context rule allows it), but the stored presence event is a `domain.presence.history`
   type and the conversation publishes no event: a decorator and a listener in the boot module keep both contexts
   unchanged and memory independent of both. The backfill goes through the contexts' in-ports, never their tables.
2. **The conversation is fed from what is kept**, not from `Heard`/`ReplySpoken` directly: the stored entries already
   carry the language, the tool calls and the errors, and the Talk panel and memory then agree by construction. The
   voice's thread pays mapping, redaction and a queue offer: **20.6 µs per line** measured
   (`MemoryLogServiceTest.recordingCostsTheVoiceMicrosecondsNotADatabaseRoundTrip`, with the writer stuck on a down
   database); the database write is on the writer thread.
3. **Times are `timestamptz`** in `memory` (design 5.4), unlike the older contexts' Unix seconds; `EventFeeds.instant`
   converts to the microsecond.
4. **Schema differences** (design 5.4 "As built"): `sensitivity` has no `secret` (never stored); `fact.embedding` may be
   `NULL` (the owner's `remember` works while the embedding model is missing; the nightly pass embeds later);
   `fact.extracted_by`; `episode.day` and `events`; `block_version` status `superseded` and `kept_lines`; a `state`
   table (worker progress, backfill markers, the owner's memory settings: the settings context's `setting` table
   belongs to another schema).
5. **Owner lines of the profile** are explicit (`kept_lines`): the lines the owner added or changed in an edit, plus
   the ones pinned; every rewrite keeps them verbatim and first. The design said "lines the owner wrote or pinned";
   without a column there is no way to know them after the next rewrite.
6. **The owner's word wins over extraction**: a pinned fact is never updated or invalidated by the model (the candidate
   is added beside it, for the owner to see); an owner-written fact is never reworded (an update becomes a NOOP with
   more sources), but the world may still end it (INVALIDATE). Owner-written and pinned facts never decay.
7. **A batch is all or nothing.** Plans are decided in memory first (later candidates see the earlier ones, and facts
   ended earlier in the batch are hidden), then written in order, then the events are marked read. A pass cut by the
   voice writes nothing of the batch in progress. The writes are not one transaction across the plans (each plan is);
   a crash between two plans can leave a half-written batch that the next pass reprocesses (then mostly NOOPs).
8. **Yielding to the voice cancels the model call in flight**: memory's requests are streamed and the stream is closed
   as soon as the voice is busy (listening, thinking, speaking, or a sign of conversation in the last 15 s); Ollama
   stops generating when the client goes. A question can still wait for the prompt evaluation Ollama already started.
9. **Same `num_ctx` as the voice (8192)**: a different context size would reload the voice's model. Memory's own
   budget follows: at most 40 lines per batch, about 3500 tokens of a day's events per summary.
10. **The warm-up** runs after a pass that used the voice's own model (its cached prompt is gone: Ollama keeps one
    prompt cache per loaded model) or changed the profile. Today nothing reads the profile into the prompt; the read
    path stage makes the profile part of the system prompt and relies on this hook.
11. **Skipping the model when nothing is similar**: a candidate with no current fact at cosine ≥ 0.3 among its 10
    nearest is added without a reconciliation call (most candidates, most of the time). bge-m3 gives unrelated
    sentences about 0.3 to 0.45, so the model still sees anything plausibly related.
12. **Unreadable model output** is retried once, then the batch is skipped (marked read, logged): a model that always
    fails would otherwise block every later event.
13. **Failures back off**: a failed scheduled pass waits 1 minute, doubling to 30 minutes, before the next scheduled
    try ("Consolidate now" is not held back); the log says it once per streak.
14. **Idle counts from the host's start**: the first pass after a start waits `idle_minutes`, and a new install's first
    nightly pass runs at the first idle moment after that (the most recent night hour counts as due).
15. **Sensitive data**: vital-sign events are `sensitive` at the source; sensitive events are left out of day
    summaries; sensitive facts never enter the profile (it will be in every prompt, whoever is in the room);
    health/money words make a fact `sensitive` by rule.
16. **Forgetting a fact also rewrites the profile at once** (a new version without the lines that state it), and the
    owner event it leaves has no content. The source conversation lines stay in the log (they may hold other facts):
    forgetting them is `forgetEvents`.
17. **Retention** deletes only brain events (older than `retention_days`, their day summarised, no fact from them);
    the per-minute samples live in the presence context, which memory cannot touch (see gaps).
18. **Settings**: memory's own (`MemorySettings`, in `memory.state`): per-source switches, `memory_model` (empty: the
    voice's), `night_model` (empty: the memory model), `embed_model` (bge-m3), `night_hour` (3), `idle_minutes` (10),
    `retention_days` (365), `worker`. The embedding size is a host property fixed at schema creation
    (`marvin.memory.embedding-dimensions`, 1024); a model with another size is refused with a clear message.
19. **ArchUnit ignores test jars** (`ArchitectureTest.NoTestJars`): the shared test helpers are on `marvin-app`'s test
    class path as jars, which `DoNotIncludeTests` does not recognise.

### Verified

- `cd host-java && ./mvnw verify`: 263 tests, 0 failures, 1 skipped (the embedded database as root). New: domain
  `FactCandidateTest`, `ReconciliationTest`, `ProfileTextTest`, `RulesTest` (redaction, feeds, batches, decay,
  periods, settings, tokens); application `ConsolidatorTest`, `MemoryWorkerTest` (idle wait, busy voice, night hour,
  first night, yield and resume, failure with fix and backoff, off), `NightlyPassTest` (days without sensitive events,
  roll-ups, stale episodes rewritten after a forget, profile with kept lines and the size limit, decay, retention),
  `MemoryLogServiceTest` (switches, redaction, outage retried, backfill once and without duplicates, the voice's cost),
  `MemoryAdminServiceTest` (remember, edit as a version, pin, forget a fact and its profile line, forget events with
  cascade, forget everything, profile edit/restore/limit, export, health); persistence `MemoryStoresIT` (pgvector:
  schema and HNSW index used by the planner, idempotent log, filters, invalidation on both clocks with `asOf`,
  version chains, NOOP sources, forgetting cascade and orphans, retention keeps fact sources, episodes, profile
  versions and the single active one, state; without pgvector: `real[]`, exact search, no index);
  `OllamaMemoryModelTest` (request body: schema, `num_ctx` 8192, temperature 0, `think` false; prompts; parsing;
  bad output; missing model; refused connection; cancellation within 2 s; embeddings in batches and missing model);
  `MemoryEvaluationTest` (stub: precision 0.90, recall 0.90, operations 3/3, dates 1/1, sensitivity 1/1, the two
  misses on purpose); `MemoryEndToEndIT` (the whole host: backfill of conversation and presence with labels and
  without notes or host markers, a new line fed live, idle pass with facts and sources, nightly pass with a day and a
  profile, every memory request with `format` and `num_ctx` 8192, embeddings bge-m3, `/api/health` memory component,
  the two web routes, forgetting a fact and its profile line); ArchUnit green with the new context.
- `cd host && python3 -m pytest -q`: 305 passed, 3 skipped (the Python host is unchanged).
- `./marvin demo` with `host-java/e2e/stub_ollama.py` on 11434: `/api/health` memory "247 events (247 not
  consolidated yet), pgvector HNSW index (cosine), embeddings bge-m3", the demo week backfilled; `POST
  /api/memory/consolidate {"pass":"nightly"}`: done in 1.4 s, 249 events, 12 batches, 5 day episodes, 1 week.
  (The voice could not be turned on here: no voice extra in this container.)
- The embedding-model-missing path was seen for real: the first demo start met a stub that could not read chunked
  request bodies; health said `embeddings bge-m3 unavailable: ... Run ollama pull bge-m3`.

### Latency

- The voice path gains 20.6 µs per kept line on the voice's threads (above) and nothing else: no memory in the
  prompt yet, so the system prompt, the history and the request body are byte-identical to before (the
  `VoiceEndToEndIT` and `ApiContractIT` checks of the rehearsal and question bodies are unchanged and green).
- What the owner may feel is the model being shared: a memory pass only starts after `idle_minutes` of quiet, its
  call in flight is abandoned when the voice wakes, and the voice's prompt cache is refilled after a pass that used
  the voice's model. Worst case, the first question after a pass waits for the prompt evaluation Ollama had already
  started plus the refill if it was not finished: to be measured on the Mac (`docs/test-plan.md` has no step for it
  yet; hint below).

### Known gaps

- **No real model here**: extraction quality is unmeasured (the stub checks the harness). Run the evaluation set on
  the Mac with `qwen3:4b-instruct` and with the night model before trusting the defaults; tune the prompts with it.
- **Nothing reads memory yet**: no context assembler, no `recall`/`remember`/`forget` tools, no Memory screen; the
  profile is written but not in the prompt (next stages).
- Retention does not reduce the presence context's per-minute samples (another context's table); the presence
  context needs its own retention rule, or a port for it.
- A database first made without pgvector keeps `real[]` columns after pgvector appears (no migration to `vector`), and
  changing the embedding model's size needs a new database (the design's "new column, then swap" is not built).
- Forgotten facts can be learned again if the owner says them again (by design) and, until the nightly rewrite, a
  summary can still mention a forgotten event's content only if its day was not marked stale (days are; weeks and
  months overlapping them are too).
- The HNSW search filters after the index scan (pgvector's default); with many archived or ended facts near a
  question, fewer than `k` current facts may come back. `hnsw.iterative_scan` (pgvector 0.8) would fix it.
- `Consolidator`'s plans are written one transaction each (decision 7).
- The golden recordings (`marvin-contracts/golden/recordings/`) are ignored by the repository's `.gitignore`
  (`recordings/`): a fresh clone must run `python3 marvin-contracts/tools/recordings.py` before `./mvnw verify`
  (found here after a restore; not changed in this stage).
- The worker's reports and state are not pushed over SSE yet (`MemoryListener` exists; the app stage decides the
  message, the contract test's SSE shapes must allow it).

### Hints for the next stages

- **Read path**: implement the conversation's memory port in `marvin-app` over memory's in-ports (a new
  `RecallMemory`-style in-port: `FactStore.nearest` with `Filter` for the path, `touch` for used facts, `asOf` for
  "when" questions, episodes by day). Put the profile in the system prompt (it changes only when
  `BlockVersion` changes: nightly or owner edits; `MemoryWorker` already calls `rewarm` after a profile change, and
  owner edits in `MemoryAdminService` should trigger it too). Volatile memory goes in the last user message. Compute
  the question embedding from the speculative transcript (`Partial`) where possible. Calibrate `TokenEstimator`
  from `prompt_eval_count` (the chat responses carry it; `OllamaLanguageModel` does not surface it yet).
- **Tools**: `remember` → `ManageFacts.remember` (owner origin, confidence 1); `forget` → propose with
  `ManageFacts.list`/nearest, confirm, then `ForgetMemory.forgetFact`; `recall` → facts (including archived and past,
  `asOf`), episodes, `BrowseMemory.log`.
- **API and app**: extend `MemoryController`; add every POST route to `AccessFilter.POST_PATHS`; export is
  `ExportMemory.export()` (zip it in the adapter); publish `MemoryListener` events on SSE once the app knows them.
- Measure on the Mac: the first-word latency of a question asked right after a pass (forced with
  `POST /api/memory/consolidate`), with the memory model equal to the voice model and with a separate night model.

## Stage: read path

### What exists

- **The conversation's memory port**: `application.conversation.port.out.MemoryContext` (profile, a question's
  candidates as prompt lines, `used`, the three tools, `NONE`). The conversation never sees memory's types;
  `marvin-app`'s `MemoryForConversation` translates to memory's in-ports and holds no behaviour.
- **Memory's read in-ports**: `RecallMemory` (the cached profile, `recollect`, `used`, `recall`) and
  `ConfirmForgetting` (proposals with a code, confirmation by a later voice turn or the app, everything with a typed
  phrase, pending list, cancel), implemented by `MemoryRecallService` and `ForgetConfirmations`. `ManageFacts.review`
  (new) marks suggested facts reviewed. `CachedProfiles` keeps the active profile in memory (every memory service
  shares it, so its cache is dropped exactly when a version is written).
- **Domain**: `RetrievalScoring` (1.0 relevance + 0.5 recency + 0.7 importance, min-max relevance, floor 0.45),
  `MemoryText` (context lines with validity, dates in words, sentences, word overlap, search terms), `RecallWindow`
  (periods as local days); in the conversation, `ContextAssembler` (sections with hard budgets, cut by score, report)
  and `Persona.systemPrompt`/`contextItems`/`context` (the persona text itself unchanged); `ConversationMemory` got
  an optional token budget; `TokenEstimator` moved to the shared kernel with `LanguageTokens` (per-language
  calibration); `Fact.reviewedAt` (and `memory/V2__review.sql`); `MemoryToolSpecs`.
- **The voice** (`VoiceService`): the profile and the memory tools' rules in the system prompt (warm-up included);
  the memory search started on the speculative transcript (`Partial`) and reused by the final one when the words are
  the same; at most 300 ms of waiting; the sections cut to 200/150/300 tokens; the facts sent marked used; the tool
  context (turn, others present); Ollama's counts (`LanguageModel.Usage`, new) logged at info, traced, shown and used
  for calibration; a `memory` report in each reply entry (profile version, sections with every candidate, scores,
  kept or not, tokens, timings, problem, usage).
- **The tools** (`MemoryTools`, same registry as `get_weather`, local, no filler): `remember`, `recall`, `forget`.
- **The memory API** (`MemoryController`, docs/memory.md has the table): overview, facts (filters with counts,
  detail with quoted sources and the conversation they were said in, remember, edit, pin, archive, review, forget with
  a code), pending forget proposals (confirm, cancel), forget everything (code and phrase), profile (current, versions
  with diffs, edit, restore), episodes, raw log, export (JSON or Markdown file), settings (the per-source switches),
  worker (now with the models), consolidate. Every POST in `AccessFilter.POST_PATHS`. `EventHub` is a
  `MemoryListener`: `memory` messages on `/api/stream`.
- **Adapters**: `OllamaLanguageModel` reports `prompt_eval_count`/durations; `JdbcFactStore.nearest` finds the k ids
  first (HNSW with `hnsw.iterative_scan = relaxed_order`, or the exact scan) and reads only those rows; the reviewed
  filter. The Java `StubOllama` counts prompt tokens as a cache would (only the messages after the shared prefix).

### Decisions and deviations from docs/design.md

1. **"Now" budget 200 tokens, not 120.** Measured: the brain's longest context (simulated sensors, seated, vital
   signs, recent events, home place) is 282 estimated tokens before calibration (about 230 after). The line saying
   the sensors are simulated is about 100 alone; with 120 it was cut and Marvin would have given simulated readings as
   real. The lines are scored (clock 1.0, simulated 0.95, no sensors 0.9, presence 0.85, vitals 0.75, seated 0.6 ...
   home 0.35, recent events 0.3), so the budget takes the recent events and the home place first.
2. **Without memory the voice is byte for byte what it was** (`MemoryContext.NONE`, or `marvin.memory.read=false`):
   the persona prompt, the unbudgeted context block, the tool list, no `memory` key in replies. The parity tests with
   the Python host keep holding; `ApiContractIT` ignores the Java-only `memory` report and `memory` stream messages,
   as it already ignored `tools`.
3. **The history budget** (2500 tokens, design 5.3) is kept like the turn limit: the older half of the turns goes at
   once, so the cached prefix changes rarely. The past context blocks stay in the history (the design's rule).
4. **The profile is read from a cache**, never from the database on the voice's path. A profile version written by
   the owner (edit, restore, a forgotten fact's line removed, forget everything) triggers `rewarm` through the admin's
   `changed` event; the nightly rewrite already did.
5. **"Today so far" is today's and yesterday's day summaries**, sentence by sentence (scored by words in common with
   the question, then today before yesterday, then place in the summary). A day is summarised the night after, so on
   most days only yesterday's exists. No calendar yet.
6. **Token calibration** uses the messages after those the previous request shared (Ollama's `prompt_eval_count`
   covers only what it evaluated again), minus 5 template tokens per message and 3 for the reply; only when at least
   one message was shared and the new text is 200 characters or more; a ratio outside 1.5 to 8 characters per token is
   ignored. Per conversation language, starting at 3.5 with a 10 % margin. With a model that reloads, or a cache that
   is not reused, the measurements are ignored rather than learned wrongly.
7. **A 300 ms hard budget** for memory on the voice's path, then the question goes without it (logged, and shown in
   the reply inspector). The search normally runs during recognition and costs nothing.
8. **Forget by voice**: the first call lists matches and a six-character code (never to be said aloud); a call with
   the code in the same turn is refused ("the person has not confirmed yet"); in a later turn it forgets. Codes expire
   after 5 minutes and work once; every pending proposal is listed in the app (`GET /api/memory/forget`), where the
   owner can confirm or cancel it. A tool call without a turn number counts as turn 0, never as the app.
9. **Forgetting everything** is confirmed twice: a code, then the code with the typed phrase "forget everything".
10. **`recall`** searches by meaning (the 20 nearest facts, past and archived included, cosine ≥ 0.35) and by words
    (the three longest words of the query, accents kept, in statements and in what was said), keeps to the period,
    and returns at most 8 facts (status, validity in words, where they came from), 3 summaries, 5 lines said. Current
    facts it returns are marked used.
11. **Sensitive facts**: never retrieved or recalled while the brain sees more than one person (`targets > 1`), nor
    for a cloud model (the audience has the flag; no cloud model exists yet). Day summaries have no sensitive events.
12. **API style**: GET and POST only, fixed POST paths with the id in the JSON body (the access filter checks every
    POST path exactly and handles no other method), times in Unix seconds, `409` for a refused confirmation, `404` for
    an unknown fact.
13. **The nearest-facts query** first finds the k ids, then reads those rows. Reading the full rows in the ordered scan
    made PostgreSQL gather the sources of every candidate: 110 ms with 3000 facts before, 4 ms after.

### Verified

- `cd host-java && ./mvnw verify`: 303 tests, 0 failures, 1 skipped (the embedded database as root). New tests: domain `ContextAssemblerTest` (cut by
  score not position, a long line does not push out short ones, heading cost, report, the "now" section equals the
  old context block, the "now" budget keeps the clock and the simulated line, the system prompt without memory is the
  persona byte for byte, calibration per language and implausible counts ignored, the history budget), `ReadPathTest`
  (the score's terms and weights, sum not product, the floor, periods, validity texts, sentences and words);
  application `MemoryRecallServiceTest` (current relevant facts best first without past, archived or unrelated ones;
  sensitive facts only alone; used marks; missing embedding model; gist; profile cache and warm-up on owner edits;
  recall with validity, sources, days and lines said, and the period; forgetting by voice needs a later turn, the app
  confirms, codes expire and cancel, everything needs the phrase; review), `VoiceMemoryTest` (system prompt with the
  profile byte-identical across two questions and the warm-up, history grows only at the end, memory only in the last
  message, facts marked used; a new profile version changes the system prompt once; without memory the prompt is the
  persona; 30 candidates cut to 300 tokens by score with the inspector's report; the search reused from the
  speculative transcript; the 300 ms budget; the audience with a guest; calibration converging to the model server's
  4.5 characters per token); `MemoryToolsLoopTest` (the real `AnswerLoop` and `OllamaLanguageModel` against the stub:
  remember, recall, forget refused in the same turn and done in the next); `MemoryApiIT` (every route, its errors,
  the access rules, the stream's `memory` messages, forgetting a fact and everything, the profile cache following
  the API); `VoiceEndToEndIT` now also checks memory on the real voice path (memory tools' rules in the system
  prompt, the question embedded, the reply's memory report); `ApiContractIT`, `MemoryEndToEndIT` and the rest green.
- `cd host && python3 -m pytest -q`: 305 passed, 3 skipped (the Python host is unchanged).

### Latency

- **The voice's thread**: heard to the model's request, median of 40 questions (`VoiceMemoryTest`, fake sidecar and
  model, a memory answering at once with 30 facts and 2 summary sentences): **0.439 ms without memory, 0.511 ms with
  memory: +72 µs** (the assembly: scoring the "now" lines, cutting three sections, rendering); the final `verify`
  run measured 0.404 and 0.492 ms (+88 µs).
- **Retrieval**, on PostgreSQL 18 with pgvector (HNSW), 3000 facts, question embedded by the stub over HTTP
  (`MemoryApiIT`, median of 30): **12.2 ms in all: embedding 6.8 ms, search and scoring 4.0 ms** (8.8, 4.8 and 2.7 ms
  in the final `verify` run). It runs while the
  question is still being recognised (on the speculative transcript), so the question usually does not wait for it.
  On the Mac the embedding is bge-m3 on Ollama (expect tens of milliseconds, in parallel with the voice's model if
  Ollama keeps both loaded: `OLLAMA_MAX_LOADED_MODELS` ≥ 2).
- **Prompt tokens**: the system prompt grows by the memory tools' rules (about 100 tokens) and the profile (at most
  500); both are cached and re-warmed only when they change. The volatile part grows by at most 450 tokens (150 +
  300) plus the tool schemas' three entries in the cached part. What that costs is Ollama's `prompt_eval_duration`,
  now logged for each question (`marvin.voice`: "prompt: N tokens evaluated in S s ...") and shown in each reply's
  memory report: measure it on the Mac.

### Known gaps

- **No real model or bge-m3 here**: the relevance floor (0.45), the scoring weights and the calibration were checked
  with the stub's word embeddings and counts. On the Mac: read a few replies' memory reports, check which facts pass
  the floor, adjust `RetrievalScoring.DEFAULT` if needed.
- Only the first request of a question calibrates (a tool round's second request is not used).
- The profile goes into the system prompt as it is; the soul (design 5.1) does not exist yet.
- `recall`'s search in what was said matches words as written (`ILIKE`): a French question without accents does not
  find accented words, and the reverse.
- The per-question embedding and the voice's model share Ollama; if Ollama keeps only one model loaded, bge-m3 can
  evict the voice's model. To check on the Mac (`ollama ps` while talking).
- The app does not show any of this yet (the Memory screen and the reply inspector's memory sections are the next
  stage's).
- Carried over: the golden recordings are ignored by `.gitignore` (run `marvin-contracts/tools/recordings.py` after a
  fresh clone); retention does not reduce the presence context's samples.

### Hints for the next stages

- **The app**: the reply entry's `memory` object is the inspector's data (sections → items with `kept`, `score`,
  `similarity`, `relevance`, `recency`, `importance`; `timings`; `usage`); the Memory screen's routes are in
  docs/memory.md; listen to `memory` messages on `/api/stream` and reload what is shown; forget and forget everything
  are two-step (code, then confirm); pending voice proposals come from `GET /api/memory/forget`.
- **On the Mac**: run `MemoryEvaluationTest` with the real models; look at the `prompt:` log lines for the first
  question after a pass and after a profile edit (the warm-up should keep `prompt_eval_count` small).

## Stage: app shell

The new app, part 1: the approved visual direction (docs/design.md 10.4; the designer's prototype, Day "Herbier" /
Night "Halo") built for real on the Java host's static app, wired to the real API. The Python host keeps its own,
older app unchanged (it stays a tool); the two apps now differ on purpose (docs/ui.md).

### What exists

- **Files** (`marvin-adapter-web/src/main/resources/app/`, plain ES modules, no build step, no CDN, no web fonts):
  `index.html` (the shell and every screen's markup), `style.css` (the Day block, the Night block, then rules shared
  by both), `theme.js` (a classic script in the head: the appearance before the first paint), `app.js` (shell,
  navigation `#view/sub`, the one event stream, badges), `core.js` (helpers, a small event bus between modules),
  `face.js`, `daycard.js`, `home.js`, `talk.js`, `convo.js`, `inspector.js`, `memory.js`, `memdata.js`,
  `activity.js`, `marvin.js`. `AppController.STATIC_TYPES` serves exactly these.
- **Appearance**: Day, Night, Auto (`prefers-color-scheme`, live), kept in `localStorage` (`marvin.appearance`);
  identical markup and geometry (e2e measures the Home boxes, top bar and rail in both: equal). Skip link, visible
  focus, 44 px targets (a few compact text buttons enlarge their hit area with a pseudo-element), reduced motion.
- **Shell**: five destinations (Home, Talk, Memory, Activity, Marvin), a bottom bar on a phone and a rail on a
  computer, a count on Home when a decision waits, a dot on Talk while Marvin listens or speaks; the top bar with the
  wordmark and eyes, the connection, the Simulated badge, the appearance switch. The old `#robot`, `#history`,
  `#settings` addresses still land in the right place.
- **Eyes**: `face.js` draws face.py's geometry (the robot's nine expressions from `FaceParams`, the voice's seven from
  the old mini face, lids, blinks, the spring) in the theme's colours, lids cut out so the orb shows through. One
  shared state (the robot's `expression` from `/api/state`, the voice's status) drives every face: Home's orb,
  Talk's mini face, the wordmark (still). Marvin > Robot also shows the robot's screen as the host draws it
  (`/face.png`), polled only while that page is open.
- **Home**: presence (status sentence, Talk, Mute), your decision (pending forget proposals with Keep / Forget,
  otherwise an honest "Nothing needs you"; never hideable, always second), your day (stats and the real timeline),
  a small thing remembered (latest fact with its source line, the next two, the fact's dialog with quoted sources),
  breaks and breathing (breathing sampled from the live state every 5 s for ten minutes; "Simulated reading",
  "no longer live" after 10 s without state), recent moments. Customize: show/hide and up/down buttons (keyboard
  and touch), persisted per browser (`marvin.home`), reset (dialog, Marvin > Preferences, Marvin overview).
- **Talk**: the old live conversation ported as is (bubbles forming from partials, shimmer while understood, dots
  while thinking, words appearing as said, listening strip with the time left, earcons, the "now" line, ignored
  utterances folded), voice on/off, Talk now, Mute, Stop, typed questions; `who` lines above bubbles as in the
  design; memory chips under answers that used `remember` / `recall` / `forget`; the reply inspector in a dialog
  with the memory report (profile version, sections with budget and tokens, every candidate with its score, kept or
  left out, timings, Ollama's counts, the problem when memory was late); "Here, now" beside it (presence, the facts
  the last answer used, its timing and model).
- **Also ported, so nothing is lost** (to be refined by the next stage): Memory (facts with filters, search, the
  fact dialog, profile, export link), Activity (background tasks: honest empty state; memory at work with
  Consolidate now; today's moments; History with days, week and conversation search; the log), Marvin (overview
  with Soul and Connections as "coming later"; Robot with devices, lidar, radar, vitals, recoloured per theme;
  Voice settings; Preferences; System = `/api/health`).
- **Host change**: `ForgetConfirmations.proposeFact` / `proposeEverything` now notify listeners (a `memory` /
  `forget` message), so a proposal made on one device shows on every open app (`MemoryRecallServiceTest`). Home
  also reloads decisions when the first one expires (expiry sends nothing).
- **Tests and tools**: `AppFilesTest` rewritten (every file served and every served file exists; the page and module
  imports load only served files and every script is used; no network URL, no inline script, style or handler;
  SPDX headers; Day and Night define the same variables). `ApiContractIT` checks the app's files (`app_index`,
  `app_script`, `app_style`, `app_manifest`, the public stylesheet) by status and headers only. `e2e/e2e.py`
  rewritten for the new app (34 checks, and the screenshots); `e2e/stub_ollama.py` answers "remember that ..." with
  a `remember` tool call.

### Decisions and deviations

1. **The prototype's page titles are kept as product copy** ("A little room to breathe.", "Things in motion."),
   dates are real; Talk's title follows the voice ("I'm listening.", "Let me think.", "Resting."). Everything else
   shown is real data; the prototype's sample content (drafts, recipients, Soul proposal, task budgets) has no
   counterpart and is replaced by honest empty states.
2. **Your decision = pending forget proposals** until tasks and approvals exist: the only real thing that waits for
   the owner's yes today.
3. **The inspector is a dialog** (design), not the old inline expansion; "Why this answer" under each answer.
4. **Robot, History, Settings and the log were ported now**, restyled, into Marvin and Activity sub-pages, rather
   than keeping the old app reachable: the new app replaces the old one at `/` from this stage.
5. **ES modules** (not one file): the CSP (`script-src 'self'`) allows them, and each screen stays readable.
6. **The brand eyes are drawn by the same renderer** (still), not CSS pills.
7. **Manifest colours** follow Day (`#f2f0e7`/`#faf9f3`); the page's `theme-color` has one per scheme.

### Verified

- `./mvnw verify`: 307 tests, 0 failures, 1 skipped (the embedded-database test, as root); `ApiContractIT` 7/7 with the Java app exempt from
  byte counts; `AppFilesTest` 4/4; `MemoryRecallServiceTest` 13/13; ArchUnit green. `cd host && python3 -m pytest -q`:
  305 passed, 3 skipped (the Python host and its app unchanged).
- `python3 host-java/e2e/e2e.py app` against `./marvin demo` (stub Ollama, the voice sidecar with `--fake`):
  34/34, no console error: robot page live, History search and previous day, log, voice on, typed question, weather
  tool with the think leak removed, remember tool with its chip and the fact in memory, inspector with timings and
  memory, Talk now, mute/unmute, stop, voice settings applied, Auto following the system live, same geometry in both
  appearances, appearance and layout kept after a reload, layout reset, a decision appearing on Home (made through
  the API, as another device would) and Keep dropping it.
- At 320 px no screen scrolls sideways.
- Screenshots of every screen in both appearances at 1440x900 and 390x844 (DPR 2) and the prototype comparisons:
  `/mnt/user-data/outputs/memory-shots/` (`compare/` holds prototype | implemented side by side).

### Latency

The app changes nothing on the voice path: no new server work per question (the inspector reads the reply entry
already sent). `renderVoice` and the live handlers are the old ones.

### Known gaps

- Memory's screen is the read side only: edit, pin, archive, review, forget from a fact, forget everything, profile
  versions and restore, episodes, the raw log, memory settings (per-source switches, models) are the next stage's.
- A proposal's expiry sends nothing on the stream (Home re-checks at the expiry time).
- The e2e walk leaves its test facts in the demo's memory ("I water the plants on Sundays").
- The prototype's `gallery.html` views and 320 px captures were not compared one by one; only the five destinations'
  PNGs.

### Hints for the next stage (the app, part 2)

- `memory.js` is where the Memory screen grows; `memdata.js` has the fact dialog (add the actions there: edit, pin,
  archive, forget with its code). The Home decision already confirms/cancels proposals (`home.js loadDecisions`).
- Listen with `on("memory", ...)` from `core.js`; `emit("badge", {view, n, label})` sets a destination's count.
- New files must be added to `AppController.STATIC_TYPES` (AppFilesTest fails otherwise); new colours to both theme
  blocks (it fails otherwise too).

## Stage: app screens

The new app, part 2: Memory, Activity and Marvin built for real on the designer's direction (Day "Herbier", Night
"Halo"), on the real API, in both appearances and both sizes. No change on the host's Java side except the three new
files in `AppController.STATIC_TYPES`: every route the screens need already existed (read path stage).

### What exists

- **Memory** (`memory.js`, `memdata.js`, `memtools.js`, `worker.js`):
  - Filters **All memories**, **Pinned**, **Suggested**, **Past** with their counts, a search (Escape clears it),
    pages of 50 (**Show more**). Rows: status (Yours, Reviewed, Suggested, Pinned, No longer true, Replaced,
    Archived), sensitivity, source line, **Source & edit**, **Pin**/**Unpin**, **Keep** for a suggestion, **Bring
    back** for an archived fact; **Keep all shown** on Suggested; **Add** (the owner's memory, stored at once).
  - "Source & edit": the sources quoted and dated, the owner's own words first, two shown and the rest folded,
    each with **Open that conversation** (History, scrolled to the line and flashed); the correction with its
    sensitivity (a new version, `POST facts/edit`); Pin, Keep as reviewed, Archive/Bring back; use count, learned
    by, confidence, importance; the other versions. **Forget this fact** opens a second screen that says what goes
    and that the conversation stays in History; only its **Forget fact** proposes and confirms (code, then
    confirm, both in one gesture since the owner has just made the second choice).
  - Profile card; **Read profile & versions**: the lines (the owner's marked), every version with its diff and
    **Use this version again** (`profile/restore`), **Edit** (lines added or changed become `kept_lines`, lines the
    owner kept stay kept, removed lines go).
  - "Days, in Marvin's words": day, week, month episodes, the stale ones marked, today's line ("written tonight,
    after 03:00").
  - Memory at work (shared with Activity, one request for both): state, pending, last passes, next night, models,
    what the last pass did in words, **Consolidate now** and **Run the nightly pass** (Memory only), the
    embedding problem with its fix. The same problem shows as a notice at the top of Memory with **Check again**.
  - Memory, on your terms: the per-source switches (instant, `POST /api/memory/settings`), **Settings** (worker
    on/off, idle minutes, night hour, retention), **The raw log** (search, older pages), **Export** (JSON or
    Markdown), **Forget everything** (what goes and what stays, **Continue** asks the host for a code, then the
    phrase must be typed before the button enables; **Keep my memory** cancels the proposal on the host).
- **Activity**: Today = Tasks & approvals (an honest empty state listing what will appear: steps, budget, approval
  on the exact version; and that today only a forget request waits, on Home), Memory at work, today's moments (12,
  then **Show all**). History = the day card plus that day "in Marvin's words" (its episode, "written tonight" for
  today, or "not written"), the last seven days plus the weeks in Marvin's words, conversations and search.
- **Marvin**: Overview (unchanged, System now a link); Robot with a notice for offline (last heard, views frozen),
  host not answering, simulated sensors; **Voice & models** = the voice form, Ollama (reachable or its fix, its
  models and their roles: voice, memory, night, search), memory's models (day, night, embedding, with the
  embedding's state); **System** (`system.js`) = services (database, robot link, memory with the embedding fix,
  voice sidecar with **Restart the voice**, Ollama), this computer (version, mode, data folder), the log with its
  filters and a text filter, live, in a scrolling box; Preferences + "Language and the outside world" (language,
  tools, internet, home place: a partial `POST /api/voice/settings`).
- **States**: a lost stream shows a banner on every screen after 4 s ("What you see may be out of date") and hides
  it on reconnection; lists show a pulsing placeholder while loading; every panel that cannot be read says why
  (with **Try again** on the fact list and the worker); empty states per filter and per episode level.

### Decisions and deviations

1. **The log moved to Marvin > System** (the task list for this stage puts it there): diagnostics live with the
   services, Activity keeps what happened. `#activity/log` redirects there (`history.replaceState`).
2. **Past = the past and the archived, together** (two requests, merged by date). Replaced versions are not in it:
   `JdbcFactStore` keeps them out of `past` on purpose, and each fact shows them in its own history. Its hint and
   empty text say so.
3. **Forgetting a fact from the app is one gesture after the second choice**: the dialog's "Forget fact" asks the
   host for the code and confirms it at once. The code protects the voice path (a model must not forget on its
   own); in the app the owner's explicit second click is the confirmation.
4. **Voice sidecar restart = off, then on** from the app (`/api/voice/off`, `/api/voice/on`). No new endpoint: the
   host already restarts it this way, and the other services (PostgreSQL in Docker, Ollama) are not the host's to
   restart; their fix is shown instead.
5. **Language, tools, internet and home place are the voice's settings**: Preferences edits them with a partial
   update, and says that saving restarts the voice (VoiceService restarts on any change; not changed here, since
   the sidecar's configuration is read at start).
6. **At 320 px the Simulated badge is hidden** in the top bar (it overlapped the appearance switch); the robot page
   and Home's breathing still say "Simulated".
7. The fact dialog's title is the design's "Remembered, with a source." (the statement is in the editor); past
   facts show "No longer current." and their statement read-only.

### Verified

- `./mvnw verify`: 307 tests, 0 failures, 1 skipped (the embedded-database test, as root); ArchUnit, `AppFilesTest`
  (the three new files served and used, no inline code, Day and Night the same variables) and `ApiContractIT`
  green. One run failed `MemoryEndToEndIT.theWritePath` (5 events expected, 4 seen right after `flush`); it passed
  alone and in the next full run: a timing flake in that test, not in this stage's code (known gap below).
- `cd host && python3 -m pytest -q`: 305 passed, 3 skipped.
- `python3 host-java/e2e/e2e.py app` against `./marvin demo --voice` (stub Ollama, voice `--fake`): 55/55, no
  console error. New: System (services, the log and its filter, the old `#activity/log` address), Memory (add,
  source quoted, correct, the earlier version in the fact's history, pin, pinned filter, search, forget with its
  second choice and "Keep it", the profile edited with the owner's line and an older version restored, export,
  raw log, settings, a source off and on, "forget everything" gated by the typed phrase then cancelled on the host),
  the lost-connection banner (stream aborted), every screen at 320 px without sideways scrolling. The walk forgets
  what it taught Marvin at the end.
- Screenshots: `/mnt/user-data/outputs/memory-shots/` (the e2e gallery, `app-*`), `app-screens/` (Memory, Activity,
  History, Marvin's five pages at 1440x900, 390x844 and 320x700, Day and Night) with `app-screens/dialogs/`
  (add, Source & edit, forget, profile and versions, export, raw log, settings, forget everything's two steps,
  Past and Suggested, both appearances), `compare/` (prototype | built for Memory, Activity, Marvin, both
  appearances, both sizes).

### Latency

Nothing on the voice path changed: no host code, no new work per question. The worker panel reads
`/api/memory/worker` at most once a second when the stream says it changed.

### Known gaps

- `MemoryEndToEndIT.theWritePath` can fail on a slow run (the event count read right after `flush`); worth a wait
  in the test.
- A forget proposal's expiry still sends nothing on the stream (Home re-checks at the expiry time; the "forget
  everything" dialog says when it expires and reports an expired code).
- "Past" pages both lists by 50 each; with more than 50 of either, **Show more** fetches the next 50 of both.
- Saving the language, tools or home place restarts the voice (a few seconds); a restart only when the sidecar's own
  settings change would need VoiceService to tell them apart.
- Importance is shown but not editable in the app (the API accepts it).
- The prototype's `gallery.html` views were not compared one by one.

### Hints for the next stages

- Tasks and approvals: Activity's `task-empty` panel and Home's decision are where they go; the decision must bind
  to the exact version the owner read (handoff.md).
- Soul and connectors: Marvin's overview has their "coming later" panels; a connector's memory permission belongs
  beside Memory's per-source switches (`[data-source]`).

## Stage: review fixes

The review of memory v1 and the new app (2 blockers, 12 majors, 13 minors) fixed, with the tests that reproduce each
finding. Details for a reader of the repo: [docs/memory.md](../docs/memory.md).

### Fixed

**Blockers (privacy).**

1. **Forgetting a range of the log left its day summary in every prompt.** `EpisodeStore.markStale` now blanks the
   summary (and its embedding) at once; `gist`, `recall`, the Memory screen and Activity never read a stale summary;
   the next night rewrites it, or deletes it (`EpisodeStore.delete`) when nothing is left of its day, and its week and
   month are blanked with it. Roll-ups never use blank or stale parts.
2. **Forgetting a fact left it in summaries, recall, restored profiles and the export.** `forgetFact` now withholds
   the fact's source events (`event_log.withheld`, migration `memory/V3__withheld.sql`: kept so that other facts keep
   their links, but out of `between`, `recent`, `byIds`, `page` and `unconsolidated`: never summarised, recalled,
   listed or exported again), empties an owner event that held the statement, blanks the days, weeks and months of
   those events, removes the profile lines that state it from the active version (a new version) and from every older
   one (`ProfileRemovals.redactHistory`), and keeps the statement in `memory.state` (`profile_removals`) until the next
   profile rewrite, which is told to remove every line stating it even reworded (`ProfileRequest.remove`, a prompt
   section); the lines that rewrite takes out for it leave the older versions too. The idle pass runs that rewrite when
   a removal is pending, so it happens within `idle_minutes`, not only at night. `restoreProfile` filters the restored
   text by the pending removals. The voice drops its conversation history (finding 8). Test:
   `MemoryPrivacyAndRobustnessTest.aForgottenFactLeaves...` (prompt, summaries, recall, profile history, restore,
   export) and `forgettingAWholeDay...`.

**Majors.**

3. **Sensitive conversation lines reached guests.** When a batch yields a sensitive fact, its events are relabelled
   `sensitive` (`EventLog.relabel`, only ever raised) in the same guarded write, and a day already summarised with them
   is blanked. With someone else in the room, `gist` and `recall` leave out every day that had sensitive lines. Tests
   with `Audience(othersPresent=true)`.
4. **A missing embedding model failed the whole nightly pass.** Each step of a pass now runs on its own
   (`MemoryWorker.step`): a failing step is recorded (error and fix) and the others go on; the report says `partial`.
   Extraction checks the embedding model first and does not start without it (no model call wasted, the events wait).
5. **One unreadable summary failed every night.** `summarize` and `rewriteProfile` failures are caught per period:
   the period is recorded in `memory.state` (`nightly.failed`) and tried again one, two, then three nights later (then
   given up, logged); the profile keeps its previous version. `num_predict` raised (summary: 3 × words + 200, profile:
   3 × tokens + 200), the likely cause being truncated JSON.
6. **Day episodes stalled after a gap of more than 56 days.** The days to do come from the log
   (`EventLog.days`: distinct local days with events), and a cursor (`nightly.days_through`) moves over days with
   nothing to summarise. Tests with a four-month gap and with 60 days of sensitive-only events.
7. and 13. **The worker could undo the owner.** `MemoryGuard`: every owner change (remember, suggest, edit, pin,
   archive, review, forget, profile edit and restore) runs under it and moves its generation on; the worker writes
   (the batch's plans, episodes, profile versions) only under it and only when the generation is the one it read
   before its model calls; otherwise nothing is written and the batch is redone from fresh facts (at most three times
   a pass). `FactStore.apply` takes all of a batch's plans in one transaction, ends a fact only `WHERE expired_at IS
   NULL` (ends first, successor pointers after the insert), and refuses a new fact whose sources were all forgotten:
   `FactStore.Conflict`, nothing written. Two concurrent owner edits of one fact: the second gets "this fact was
   changed meanwhile". Forgetting everything is a reset: the running pass stops at once (`Run.cancelled`). Tests
   with a model that forgets or edits the fact from inside `reconcile`, and `MemoryStoresIT.aPlanOnAFactChanged...`.
8. **The prompt history kept retrieved and forgotten facts.** `VoiceControl.clearHistory` (one cache miss, then the
   history grows again): called when a guest comes in (`othersPresent` false → true, checked per question) and after
   anything is forgotten (`MemoryAdminService.addForgetListener`); a turn that began before the clear does not add its
   messages (the forget tool's own list of matches included). `VoiceMemoryTest`.
9. **The embedded database's exact scan would drop memory out of the prompt at a few thousand facts.** Without
   pgvector the filter selects ids only and the vectors come from an in-process cache (read in the background at
   start, updated on apply, `setEmbedding`, delete, orphans, everything). Measured in `MemoryStoresIT`, 3000 facts ×
   1024 on PostgreSQL 18 without pgvector: **first call after a restart 1.4 s** (the cache not yet filled; the
   background read normally does it before the first question), **then a median of 27.5 ms** (was about 280 ms on the
   server alone plus the parsing).
14. **Warm-ups raced the live conversation.** `VoiceService.rewarm` only sets a pending flag; the warm-up runs when the
   voice is idle (no turn, not listening, thinking or speaking), checked on every status and at the end of each turn.
   A question abandons a warm-up in flight, and an abandoned warm-up does not overwrite `lastPrompt`. The worker does
   not ask for a warm-up after a pass that yielded. Tests: `aWarmUpAskedForDuringAQuestionWaitsUntilTheVoiceIsIdle`,
   `aPassThatYieldsToTheVoiceDoesNotWarmItUp`.
15. **Yielding did not free the GPU before the first token.** Found while testing the fix: Spring AI's reactive client
   (WebFlux on the JDK connector) does **not** close the connection when its stream is cancelled, so Ollama went on
   generating even mid-stream. Memory's calls now use the JDK `HttpClient` directly (HTTP/1.1, same request body), and a
   virtual-thread watcher polls `cancelled` every 100 ms and closes the response, which closes the connection, also
   before the first chunk; the 5-minute timeout is enforced by the same watcher. Test with the stub silent for 2 s:
   `Cancelled` in under 300 ms and the stub sees the connection closed (`StubOllama.abandoned`).
16. **The event log's flush reported done too early** (the `MemoryEndToEndIT` flake). `LogWriter` counts outstanding
   work (from the offer to the end of the write) instead of the queue's contents, and `close` joins the writer rather
   than interrupting it mid-write. `HistoryWriter` (presence) had the same pattern and got the same fix. `LogWriterTest`.

**Minors.**

10. Editing a fact's wording or making it sensitive, and archiving it, withdraw its line from the profile (and pass it
    to the next rewrite as a removal); making it sensitive also relabels its sources.
11. A reconciliation `UPDATE`'s merged wording goes through `FactCandidate.check` (secret → the candidate's own
    statement, health and money → sensitive, bounded length).
12. With a guest in the room, `remember` stores a suggestion (`ManageFacts.suggest`: extracted, unreviewed, confidence
    0.6, under Suggested), and a forget proposal gives no code: it waits in the app, and a voice confirmation is
    refused. `MemoryForConversationTest`.
17. One shared budget for the memory sections, `marvin.memory.volatile-budget` (250 tokens), filled by score across
    "today so far" and the facts (`ContextAssembler.cutTogether`); the question's trace gets `marvin.memory.tokens`.
    `docs/test-plan.md` 9.4 and 9.5 are the Mac checks (20 questions with and without memory; a question during a
    pass). The latency numbers of the read path stage were the Java side only: **the design's criterion (first word
    within +100 ms of phase 2) is unverified until 9.4 is run on the Mac.**
18. Traces: the question span carries `marvin.memory.embed_s`, `search_s`, `waited_s`, `timed_out`, `tokens`; each
    worker pass is a `marvin.memory.pass` span (outcome and counts) with a `marvin.memory.step` span per step; the
    report's counts include `prompt_tokens` and `model_ms` of extraction and reconciliation.
19. The one-time backfill became an idempotent catch-up (`RecordMemory.catchUp`): a high-water mark per source
    (`feed:<name>`), at every start and every ten minutes. Forgetting from the log records the time range or the
    event's reference (`forgotten_feed`, no content), and a catch-up skips them. A database backfilled by the earlier
    version starts its mark at the source's end (reading the past again could bring back what was forgotten since).
20. ArchUnit: `bootBridgesUseApplicationPortsOnly` (outside `@Configuration` classes, the boot module reaches the
    application layer through ports only; `HistoryLifecycle` joins the known lifecycle glue), and the rule-bites test
    has a memory fixture (the conversation calling a memory service is caught).
21. Phone and 320 px: the connection's words are visually hidden but kept for screen readers (the status region still
    announces), the dot stays at 320 px, and lost/offline is a hollow ring, not only another colour.
22. The live label says "Live · connected to Marvin's host" when the app is not opened on the host itself.
23. The browser's `theme-color` follows the chosen appearance (theme.js updates both tags and drops their `media`).
24. The presence sentence ends each part with a full stop.
25. The presence live region is written only when its text changes.
26. The memory chip has a 44 px hit area.
27. Activity lists the pending forget requests with a link to decide on Home, and Activity's nav count follows them
    (Home publishes its list as a `decisions` app event).

Also: a stale summary shows "Being written again" in Memory and Activity instead of an empty or old text.

### Not done, and why

- **The voice's own model calls (`OllamaLanguageModel`) have the same cancellation problem as finding 15**: a
  cancelled answer (barge-in, a dropped turn) does not close the connection, so Ollama may go on generating it. Out of
  this stage's scope (the voice path, not memory), but it matters for latency: the next stage should move the voice's
  streaming to the JDK client the same way and check it with `StubOllama.abandoned`.
- Worker spans are per pass and per step, not per model call (the counts are summed in the report instead).
- Withholding is per batch: forgetting one fact withholds every line of the conversation it was learned from (a
  fact's sources are its whole batch), so other facts from that conversation lose those quotes. Narrower provenance
  (per line) would need the extraction to say which lines a fact comes from.
- A forgotten statement stays in `memory.state` until the next profile rewrite (the next pass after `idle_minutes`):
  the only way to catch the reworded lines. Older profile versions are cleaned by exact line; a reworded line in an
  old version that the rewrite never saw is not caught.
- Paraphrases in the active profile are removed at the next rewrite, not at the very moment of forgetting (the word
  match catches the lines that repeat the statement).
- With a guest, a day that had any sensitive line is left out of "today so far" entirely (conservative); separate
  guest-safe summaries were not built.

### Verified

- `cd host-java && ./mvnw verify`: 336 tests, 0 failures, 1 skipped (the embedded-database test, as root). New:
  `MemoryPrivacyAndRobustnessTest` (14), `LogWriterTest` (2), `MemoryForConversationTest` (2), `VoiceMemoryTest` +3,
  `ContextAssemblerTest` +1, `OllamaMemoryModelTest` +1, `MemoryLogServiceTest` +1, `MemoryStoresIT` +2,
  `ArchitectureTest` +1 rule, `ArchitectureRulesBiteTest` +1. `VoiceMemoryTest`'s speculative-search test had a race
  (it asked before the partial's search was recorded) and now waits for it.
- `cd host && python3 -m pytest -q`: 304 passed, 3 skipped, 1 failed once (`test_parent.py::test_the_child_exits_when_its_host_is_killed`,
  a timing test of the unchanged Python host); it passed on the rerun (3/3).
- `python3 host-java/e2e/e2e.py review` against `./marvin demo --voice` with the stub Ollama: 55/55, no console error.
  Screenshots: `/mnt/user-data/outputs/memory-shots/review-fixes/` and `review-fixes/minors/` (the phone and 320 px top
  bar, Activity with a waiting request and its count, Night on a light device with the matching `theme-color`, a phone
  over the LAN address reading "Live · connected to Marvin's host").

### Latency

- The voice path's Java work is unchanged in kind (one more small cut across two sections). The memory sections are now
  bounded to 250 estimated tokens together (they could reach 450): at most that much more prompt evaluation per
  question, to be measured on the Mac (test plan 9.4).
- A question during a memory pass no longer waits for the pass's prompt evaluation (the connection is closed within
  about 100 ms), nor for a warm-up (deferred until idle).

### Hints for the next stages

- Run test plan 9.4 and 9.5 on the Mac and write the numbers here; tune `marvin.memory.volatile-budget` from them.
- Move `OllamaLanguageModel`'s streaming to the JDK client (see "Not done").
- Per-line provenance in extraction would make withholding precise.
