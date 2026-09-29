<!-- SPDX-License-Identifier: CC-BY-4.0 -->
# Review guide: the Java host (migration phases 0 to 2)

The change adds `host-java/` (a Spring Boot host at parity with the Python host), the `./marvin`
launcher, the Python voice sidecar (`host/marvin_host/sidecar/voice`) and a few deliberate changes to
the Python host. The Python host still works and its tests still pass. The work is big (about 56,000
added lines, most of them tests, golden files and generated gRPC code), so review it in this order.

## 1. What was asked, and what was decided (30 min)

1. [design.md](design.md): sections 3 (architecture), 4 (the sidecars), 10.4 (ready for
   microservices) and 11 (migration plan, phases 0 to 2). This is the specification.
2. [host-java/NOTES.md](../host-java/NOTES.md): one section per stage. Each has **Decisions and
   deviations** (where and why the code differs from the design), **Known gaps** and **Verified**.
   Read the deviations and the gaps. The "Review fixes" section lists what an earlier review
   found and how each finding was fixed.
3. [host-java/README.md](../host-java/README.md): how to run, the variables, the module table.

## 2. Module map

| Module | Holds | Start with |
|---|---|---|
| `marvin-domain` | Plain Java, no Spring, no I/O: `robot` (protocol v1 messages, device monitor), `presence` (the brain and events, ported from `brain.py`/`events.py`; day statistics), `conversation` (persona, speech text and `</think>` handling, sentence splitter, weather tool, settings), `settings`, `face`, `system` | `presence/Brain.java`, `conversation/SpeechText.java` |
| `marvin-application` | Use cases and ports per context (`port.in`, `port.out`): `VoiceService` (the conversation: turn order, answer loop, tools, memory), `AnswerLoop`, `PresenceHistoryService` + `HistoryWriter` (history written off the robot thread), `RobotAudioService`, health | `conversation/VoiceService.java`, `conversation/AnswerLoop.java` |
| `marvin-adapter-robot` | UDP 47100 (HELLO/HOST_ACK, lidar, LD2450, vitals, face link, audio relay), `.mvrec` record/replay, datagram tap | `UdpRobotLink.java` |
| `marvin-adapter-persistence` | PostgreSQL through JdbcClient, Flyway, one schema per context, the one-time SQLite import, `voice.json`, embedded PostgreSQL | `SqliteImporter.java`, `db/migration/*` |
| `marvin-adapter-web` | The Python host's app, copied byte for byte (`static/`), its REST API and SSE streams, the access key, the face PNG | `AccessFilter.java`, `ApiController.java` |
| `marvin-adapter-llm` | Ollama through Spring AI (streaming, tool calls) | `OllamaLanguageModel.java` |
| `marvin-adapter-sidecar` | gRPC client of the voice sidecar, the process supervisor, the Python runtime | `GrpcVoiceSidecar.java`, `SupervisedProcess.java` |
| `marvin-app` | Spring Boot wiring (`HostWiring`, `VoiceWiring`), lifecycle, tracing, ArchUnit rules | `ArchitectureTest.java` |
| `marvin-contracts` | Golden files made by the **Python** host (`tools/*.py`): protocol vectors, recordings with their expected events, API snapshots, face and conversation vectors; `voice.proto` | `README.md`, `golden/` |
| `host/marvin_host/sidecar/voice` | The voice sidecar (Python): audio, VAD, wake word, speech recognition and synthesis behind gRPC | `engine.py`, `server.py` |
| `marvin` (repo root) | The launcher: JDK, build, PostgreSQL, host, venv, doctor | `cmd_up` |

Boundaries are enforced, not only described: `ArchitectureTest` (layers, contexts only through
ports, adapters independent of each other, domain without Spring or I/O, Boot glue in
`@Configuration` classes only; `ArchitectureRulesBiteTest` shows each rule fails on a fixture),
the Maven enforcer (domain and application depend on nothing else), and `SchemaOwnershipTest` (a
context's storage names only its own schema).

## 3. Where parity is proven

| What must match the Python host | Proof |
|---|---|
| UDP bytes, protocol v1 | `ProtocolVectorsTest` (Python-made vectors for every message), `UdpRobotLinkTest`, `SimulatorLinkIT` (the real Python simulator against the Java socket) |
| The brain's events and states | `GoldenRecordingsTest`: Python recordings replayed through the Java brain, compared with the Python events and states |
| `.mvrec` files | `MvrecCompatibilityTest`, `PythonReadsJavaRecordingTest` (both directions) |
| API JSON, headers, access rules | `ApiContractIT` replays every request recorded from the Python host (`golden/api`) and compares status, headers and shapes; `PyJsonTest` (Python's number and escaping format) |
| The app itself | `AppFilesTest`: `static/` equals `host/marvin_host/ui/static` byte for byte |
| Face | `FaceVectorsTest` (the Python renderer's pixels) |
| Conversation text handling and tools | `ConversationVectorsTest`, `ToolVectorsTest` (Python-made vectors: speech text, `</think>`, splitter, weather) |
| Voice behaviour end to end | `VoiceEndToEndIT` (the real sidecar in its test mode, a stub model, a spoken question through wake word, answer and latency), `GrpcVoiceSidecarIT`, `VoiceServiceTest` (turn order, interruption, errors) |
| History and import | `StoresAndImportIT`, `DayStatsTest`; the final check compared `/api/history`, `/api/day`, `/api/conversation` of both hosts on the same imported database (NOTES, "Final verification") |
| The whole thing in a browser | `host-java/e2e/` (Playwright walk, stub Ollama, second device); screenshots in the final verification |

Run everything: `cd host-java && ./mvnw verify` (Docker needed for the `*IT` tests) and
`cd host && python3 -m pytest -q`.

## 4. Changes to the Python host (deliberate)

- The voice package split: the audio half is the sidecar (`sidecar/voice`); `marvin-host talk` and
  `run --voice` still work in process.
- `ui/server.py`: the Host check accepts only whole names (DNS rebinding fix, the same in both hosts).
- `parent.py`: a child started by the Java host exits when the host dies.
- Contract tools under `host-java/marvin-contracts/tools` import the Python host to make the golden files.

## Memory v1 (design phase 3)

Built in stages, each with a section under "Memory v1" in [NOTES.md](../host-java/NOTES.md). Read
[memory.md](memory.md) first, then the domain (`domain/memory`: `Reconciliation`, `FactCandidate`, `ProfileText`),
the use cases (`application/memory`: `Consolidator`, `MemoryWorker`, `NightlyPass`), `memory/V1__memory.sql` and
`MemoryStoresIT`, and `MemoryEndToEndIT`. The extraction evaluation set is `MemoryEvaluationTest`.

The read path: the conversation's port `MemoryContext` and `MemoryForConversation` (the boot module's bridge to
memory's `RecallMemory` and `ConfirmForgetting`), `ContextAssembler` and `Persona.systemPrompt`, then `VoiceService`
(`prefetch`, `assemble`, `usage`), `MemoryRecallService`, `ForgetConfirmations`, `MemoryTools`, `MemoryController`.
Tests: `VoiceMemoryTest` (the prompt byte-stable between questions, budgets cut by score, the 300 ms budget, the
calibration, the cost on the voice's path), `MemoryToolsLoopTest` (the tools through the real answer loop and Ollama
adapter), `MemoryApiIT` (every route and the retrieval time with 3000 facts).

## 5. Known gaps and risks (details in NOTES.md)

- Only one host can own UDP 47100. History written by the Java host stays in PostgreSQL: going back
  to the Python host shows its own SQLite history only. The import runs once, so days spent on the
  Python host afterwards are not imported.
- Not run on macOS or with real boards before release: [test-plan.md](test-plan.md) covers it.
- `/api/state` and `today` answer 500 after 5 s while PostgreSQL is down (robot and voice keep going).
- No trace context sent to the sidecar yet (phase 3: a `trace_parent` field in the voice contract).
- Four Boot lifecycle classes are known exceptions to the glue rule (`ArchitectureTest.BOOT_GLUE_KNOWN`).
- The embedded PostgreSQL (used without Docker) has no pgvector yet, and its test is skipped as root.
- Cosmetic: an empty day's `present_s`/`seated_s` are `0.0` in Java, `0` in Python (the same number in JSON).
