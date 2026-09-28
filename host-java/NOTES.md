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
