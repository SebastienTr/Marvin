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
