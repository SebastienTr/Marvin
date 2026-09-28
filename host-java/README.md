<!-- SPDX-License-Identifier: CC-BY-4.0 -->
# Marvin's host in Java

The next host core for Marvin: Java 25, Spring Boot 4.1, hexagonal, one deployable made of
bounded contexts that could later become services. The design is in
[docs/design.md](../docs/design.md); the engineering log (decisions, deviations, known gaps, per
migration phase) is in [NOTES.md](NOTES.md).

Status: **phase 0**, the foundation. The Python host (`host/`, `marvin-host run`) is still the
one that talks to the robot; this one starts, migrates its database and answers a health check.

## Run it

From the repository root, one command does everything (JDK, build, database, host):

```bash
./marvin doctor     # what this machine has and how to fix what is missing
./marvin up         # PostgreSQL (Docker) + the host; the app on http://localhost:8765/
./marvin demo       # the same with a simulated robot and a simulated past week
./marvin status     # what runs, and the host's health
./marvin logs       # follow the log (-n 100: the last lines)
./marvin down       # stop everything (the data is kept)
```

- **JDK**: a JDK 25 from `MARVIN_JAVA_HOME`, `JAVA_HOME`, `/usr/libexec/java_home` (macOS) or the
  `PATH`; if there is none, `./marvin` downloads Eclipse Temurin 25 into
  `~/.local/share/marvin/jdk` (Adoptium API). Nothing is installed system-wide.
- **PostgreSQL 18 with pgvector**: in Docker (`pgvector/pgvector:pg18`, container
  `marvin-postgres`, volume `marvin-pgdata`, `127.0.0.1:5433`) when Docker runs; otherwise the host
  starts its own PostgreSQL (data in `~/.local/share/marvin/pg`, no pgvector yet). `MARVIN_DB=docker`
  or `MARVIN_DB=embedded` forces one.
- **Python**: the voice sidecar (phase 2) uses `host/.venv`; `./marvin up --voice` creates it with
  the `voice` extra when it is missing.
- **Build**: `./marvin` builds the jar when it is missing or older than the sources.

Or by hand, with a PostgreSQL at `MARVIN_DB_URL` (default `jdbc:postgresql://127.0.0.1:5433/marvin`,
user and password `marvin`):

```bash
cd host-java
./mvnw -q -DskipTests package
java -jar marvin-app/target/marvin-host.jar          # MARVIN_MODE=demo for the demo
curl http://localhost:8765/api/health
```

| Variable | Default | |
|---|---|---|
| `MARVIN_PORT`, `MARVIN_BIND` | `8765`, `0.0.0.0` | The app's port and address |
| `MARVIN_MODE` | `live` | `live` or `demo` |
| `MARVIN_DATA_DIR` | `~/.local/share/marvin` | Data (embedded database, run files, logs) |
| `MARVIN_DB_MODE` | `external` | `external` (at `MARVIN_DB_URL`) or `embedded` |
| `MARVIN_DB_URL`, `MARVIN_DB_USER`, `MARVIN_DB_PASSWORD` | Docker's | The external database |

## Build and test

```bash
cd host-java
./mvnw verify          # unit tests, ArchUnit, Testcontainers integration tests (Docker needed)
./mvnw -q test         # unit tests and architecture rules only
```

`*Test` classes run in `test` (surefire), `*IT` classes in `integration-test` (failsafe). The
integration tests use `pgvector/pgvector:pg18` through Testcontainers and are skipped without
Docker; the embedded-database test is skipped when run as root (PostgreSQL refuses root).

## Modules

| Module | Packages | Holds | May depend on |
|---|---|---|---|
| `marvin-domain` | `marvin.host.domain.<context>` | Model and domain services, plain Java | nothing (Maven enforcer) |
| `marvin-application` | `marvin.host.application.<context>`, `.port.in`, `.port.out` | Use cases and ports, plain Java | domain (Maven enforcer) |
| `marvin-adapter-web` | `marvin.host.adapter.web` | The app, its REST API and SSE (Spring MVC) | application |
| `marvin-adapter-robot` | `marvin.host.adapter.robot` | UDP protocol v1, `.mvrec`, datagram tap | application |
| `marvin-adapter-persistence` | `marvin.host.adapter.persistence` | PostgreSQL, one schema and one Flyway history per context | application |
| `marvin-adapter-llm` | `marvin.host.adapter.llm` | Spring AI, Ollama | application |
| `marvin-adapter-sidecar` | `marvin.host.adapter.sidecar` | gRPC to the Python sidecars, supervisor | application, contracts |
| `marvin-app` | `marvin.host.app` | Spring Boot main, wiring; `ArchitectureTest` | everything |
| `marvin-contracts` | `marvin.host.contracts.voice.v1` (generated) | Golden files from the Python host, `voice.proto` ([README](marvin-contracts/README.md)) | protobuf, gRPC |

Bounded contexts today: `robot`, `presence`, `conversation`, `settings`, `system`. A context
reaches another only through its application ports (`application.<context>.port.in|out`) and its
domain events (`domain.<context>.event`); `domain.shared` is the shared kernel. Each context owns a
PostgreSQL schema of the same name.

## Architecture rules

`marvin-app/src/test/java/marvin/host/app/ArchitectureTest.java`, on every build:

- onion architecture: domain, application, adapters; no adapter depends on another;
- the domain uses no framework and no I/O (`org.springframework`, `jakarta`, `javax`, `java.sql`,
  `java.net`, `java.io` except `Serializable`, gRPC, protobuf, Jackson, SLF4J);
- the application layer uses no framework either (Spring wiring lives in `marvin-app`);
- Spring AI only in the LLM and MCP adapters;
- no system clock in the domain and application (`System.currentTimeMillis`, `nanoTime`,
  `Instant.now`, ...): time comes from a `Clock` port or from the robot's frames;
- no cycles between the domain's or the application's contexts; ports are interfaces;
- bounded contexts talk through ports and events only (`ArchitectureRulesBiteTest` checks that
  this rule catches a violation).
