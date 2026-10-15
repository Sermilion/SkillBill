# SKILL-423 data-layer validation

The desktop reads the runtime store through read-only Room and shared runtime
projection. Controls use the existing runtime graph. DataStore owns app
preferences. SKILL-424 retains the final module, DI, ViewModel, Navigation 3 and
native host work described in its separate spec. The per-obligation verdicts
for the SKILL-423 data gaps that SKILL-424 took over are in
[skill-423-handoff.md](skill-423-handoff.md); this record keeps the measured
results.

## Checks

Validation on Linux used the repository's JDK 21 toolchain on 2026-10-12.

- `cd runtime-kotlin && ./gradlew spotlessApply check` passed. This
  includes runtime unit tests, repository architecture tests, Detekt and Spotless.
- `cd desktop-tray && ./gradlew clean check createDistributable --no-build-cache
  --no-configuration-cache` builds and tests the desktop and its composite
  runtime dependencies, then creates the application image. It passed all 41
  desktop tests with no skipped tests.
- `python desktop-tray/scripts/packaged-data-smoke.py` exercises Room, bundled
  SQLite, shared status projection, detail and DataStore through the distribution's
  launcher and bundled JDK. It copies the image to a disposable directory, adds
  only the test entry class to that copy, and uses an empty PATH and disposable
  home. Its classpath contains packaged production libraries. The installed
  skill and CLI executable do not participate.
- `git diff --check` checks authored changes for whitespace errors.

macOS and Windows were not exercised locally. Desktop CI now runs checks,
distribution creation and the same packaged-data smoke on all three hosts.
Their results remain pending until CI runs. Linux data packaging passed; this
record does not claim interactive tray or window smoke coverage.

## Behavioral evidence

| Boundary | Regression evidence |
| --- | --- |
| Read-only compatibility | `RoomRuntimeCompatibilityTest` compares schema, migration ledger and durable rows before and after Room reads. The driver refuses writes. |
| Missing, corrupt and incompatible stores | `RoomBoardSourceTest` refuses missing files, older schema versions and foreign Room identities without creation, migration or repair. |
| Busy store | The exclusive-writer regression requires a typed busy result and recovery after rollback and reconnect. |
| Completed-history starvation | A live goal with a durable manifest and process lease, plus a live phase, remain visible behind 5,000 terminal phase records. Each live query projects one candidate. EXPLAIN QUERY PLAN proves an indexed phase search. |
| Bounded reads | Desktop pages return ten items with one lookahead candidate to detect continuation. Shared board projection examines at most forty candidates per page, with at most three coherence attempts. Goal status streams at most 100 subtasks, child outcomes, dependencies or ledger entries per batch. Each growing detail list has its own cursor. Nested subtask previews contain at most ten dependencies and one fallback agent. |
| Ordering and cursor identity | Nanosecond ordering, deterministic paging, end of pagination, changed filters, changed database generations and same-path replacement have repository tests. |
| Retained range | Controller tests load beyond five pages, refresh from the retained offset, and leave other detail lists unchanged. Sidebar and history use retained keyset anchors. |
| External commit observation | An independent JVM writes through the runtime session factory. Subscribed live, history and exact selected detail update. A counted idle interval records no repository reads or emissions. A coherent runtime transaction changes goal/control and phase topics together. Rollback and unrelated table commits stay silent. Registration barriers, coalesced topic revisions, controlled overflow after lost hints, reconnect and writer exit immediately after commit retain durable state. |
| Resource lifetime | Closing the selected store cancels its commit-signal watcher and Room connection. The observer opens no SQLite connection. Reads after source closure fail. |
| Time-derived state | Controlled clocks prove freshness, lease and recent/history expiry without another write. Selected detail observes lease expiry without a board subscriber. Desktop reads pass their observation time into shared detail projection. Expired deadlines do not start an idle database query loop. Display ticks do not query SQLite. |
| Retained-page deadlines | `RoomBoardSource` keeps deadlines by section and page cursor. `DashboardController` reports the retained cursor window after refresh and append, so evicted pages no longer schedule expiry reads. These repairs preserve the separate store, query, and identity deadline work still owned by SKILL-424 AC-34. |
| Completed-only detail selection | `IdeStatusService` passes the completed-only selector into `goalSubtaskPage`, so validation detail pages are filtered by the persistence query before projection. |
| Control admission | Real runtime fixtures reject changed repository, database generation and lease fence. Pause admission succeeds once, rejects repeated stale targets, and does not invent goal completion. Nested runtime writes roll back with their outer admission transaction and recover after failure. |
| Notifications | Controller and transition tests distinguish initial discovery from confirmed completion. History and reconnect do not replay completion; repeated observations deduplicate transitions. Settings authorize delivery again when the native callback sends. |
| Preferences | DataStore tests cover one-time migration, concurrent field updates, restart preservation and corruption without resetting the existing file. |

`SQLiteDatabaseSessionFactory` compares the desktop work revisions inside each
write transaction. After a successful commit, it takes a per-store lock and
atomically replaces a sibling signal file with a generation token and the
latest full revision snapshot when a work topic changed. The lock prevents a
delayed writer from replacing a newer snapshot. The tray watches only that file.
It never watches the database, WAL, or shared memory files. The signal watcher
opens no SQLite connection. `RoomBoardSource` keeps
its existing read-only connection for query snapshot fences. Room queries still
read the committed database state.

The watcher registers before it reads the signal baseline. A commit before that
baseline is covered by the initial repository query. A later commit changes the
published revisions. Overflow rereads the whole snapshot, so coalesced events
still identify every changed topic. Reconnect starts from a new baseline and
reads current database state. The external writer test exits immediately after
publishing its committed change. The controlled overflow test exercises the
watch adapter, not an operating-system queue.

The phase-query plan and projected candidate counts measure different work.
They do not claim a constant number of SQLite virtual-machine steps for arbitrary
search text or every possible goal/control distribution.

## Readian patterns and deviations

The reference is `/home/sermilion/StudioProjects/readian-android`.

- `core/data/.../db/room/RoomInvalidationPagingSource.kt` connects committed
  invalidation to PagingSource lifetime. Here the runtime's revision snapshot
  in a sidecar signal file bridges other processes, and affected section
  PagingSources invalidate. Room's in-process tracker cannot observe these
  external writers.
- `core/data/.../subscriptions/repository/ReadianTopicsRepository.kt` maps stored
  data at the repository boundary. Here shared runtime services project candidate
  identities before `RuntimeWorkMapper` produces desktop models.
- `core/data/.../db/room/ReadianRoomDatabaseProvider.kt` and the JVM platform
  builder separate database construction and closure from presentation. Here
  infrastructure owns Room and the signal watcher, bound to a database
  generation and observation session.
- `core/datastore/.../settings/AppSettingsStore.kt` publishes typed settings and
  uses atomic `updateData` field changes. The desktop keeps those mechanics and
  migrates existing JVM preferences once.

Room has a read-only projection of the migration ledger and raw candidate
queries. Runtime migration 52 installs its Room identity and revision triggers.
Migration 53 adds store identity, exact accepted phase output and standalone
process-owner identity. It stays registered with literal version `53` because
it is a historical ledger entry. `DesktopReadSchema.VERSION` describes the
schema Room accepts; it is not used to renumber an existing migration. The
runtime remains the only schema and write authority. Room does not create
its own database or run destructive migrations. PagingSource units retain a
board page and its snapshot metadata. Current controller consumers request
bounded pages directly and retain five page groups, rather than adopting the
reference app's PagingData presentation stack. SKILL-424 owns that presentation
architecture and generated composition.

The desktop version catalog pins Room runtime/compiler `3.0.3`, bundled SQLite
`2.7.1`, DataStore `1.2.1`, Paging `3.5.2`, KSP `2.3.12`, and Kotlin `2.4.21`.
The packaged app resolves the data and runtime libraries through declared Gradle
dependencies and the composite runtime build; it does not copy jars from an
installed Skill Bill distribution.

CLI `read` keeps its existing fresh-store initialization behavior. Desktop
projection joins `readIfPresent`, which opens an existing compatible store
read-only and never bootstraps a missing file. Missing stores become a typed
repository failure instead of an empty healthy board.

## Deleted data path

Production no longer has `CliBoardSource`, `CliResolver`, `ProcessRunner`,
`WorkPayloadDecoder` or `WorkPayloadKeys`, and their superseded tests are deleted.
The main composition has no work-command or executable discovery consumer.
The retired names remain only in the architecture guard's rejection vocabulary;
`cliOverride` and `pollSeconds` remain only in preference migration cleanup and
its migration regression test.
There is no polling setting, board polling loop, or timed observer reconnect loop. A failed observer emits one typed stale result and stays failed until explicit refresh reconnects. Idle-failure coverage checks that no further queries, watcher registrations, or board emissions occur before that request. The remaining UI delays are
hover behavior, tray availability, elapsed-time display and known data deadlines.
Native host helpers remain in SKILL-424's scope.

## Runtime architecture review

Section 5 of `docs/architecture-guidelines.md` was applied to the runtime diff.

| Rule | Result and owner |
| --- | --- |
| A1 | Clean. Engine control admission depends on ports and shared status; SQLite owns schema, drivers and transactions. Desktop infrastructure consumes the generated runtime graph. |
| A2 | Clean. `DesktopReadSchema` has runtime migration/read validation and desktop Room consumers. `DesktopCommitSignal` is read by `runtime-infra:sqlite` and `desktop-tray`. Work detail pagination models serve engine projection and desktop mapping. Existing ports carry bounded queries implemented in SQLite. |
| A3 | Clean. RuntimeComponent remains the composition root. New WorkControlService constructor dependencies are private. SQLite factory thread state belongs to the factory and clears in finally. |
| A4 | Clean. Bounded workflow, phase and reason lookups extend the owning persistence ports. No vendor object enters their signatures. |
| A5 | Clean. Existing pause and stop operations accept an expected durable parent identity. Duplicate exact-operation wrappers were removed. The retired CLI data path and decoder tests were deleted. |
| A6 | Clean. Settlement JSON paths use SharedPayloadKeys. DesktopReadSchema owns shared revision names and Room identity; DesktopCommitSignal owns the sidecar suffix and revision separator. Enum wire tokens use their owners. |
| A7 | Clean. Schema and storage refusals use typed runtime failures. Control rejection is a result. JDBC result codes classify database failures; no error-message branch or swallowed cancellation was added. Failed sidecar publication records a warning after commit and leaves the committed result intact. |
| A8 | Clean. Runtime ambient effects keep their existing injected owners. Desktop composition supplies environment and home; repository tests supply clocks and disposable paths. |
| A9, A10 | Clean. Control admission and its models form a work/control responsibility. Persistence tests remain with SQLite. The desktop exercises the real shared control boundary. |
| A11, A12 | Clean. Helpers are private or internal. Public models remain in model packages. The new shared boundaries expose typed values rather than raw payload maps. |
| G1-G7 | Clean. No ceiling, baseline or exemption was widened. RuntimeComponent's exact API inventory pins workObservationService and replaces the unused runner export with the admitted execution entry. Its added/removed-property rejection test remains. All 289 runtime-core repository tests pass. |

SQLite and runtime-core decisions record transaction joining and the two
composition access points. The full runtime check also retains existing CLI
status, planning, execution and monitor behavior.

## Repair verification

The shared board selects bounded SQLite candidates before projection and records
the candidates examined. Shared cursors carry store identity and revision.
Desktop reads check coherence across Room selection and runtime projection. The
5,000-record fixture exercises both shared board and desktop live queries.

Standalone executions persist exact accepted output. Legacy restoration reports
missing content, ambiguity and its bounded search limit. Goal subtask, validation,
dependency and execution-history lists have independent cursors. SQLite returns
bounded nested previews instead of decoding every dependency or participant.

Goal status streams manifest pages and reconciles only the selected subtask IDs
against bounded child-outcome pages. Counts and planning summaries use scalar
accumulators. Retry interpretation streams ledger pages and retains current-task
fix counts. Runtime status reads a workflow header, selected artifact families and
bounded phase-ledger pages. Legacy goal-child repository identity returns at most
two distinct identities. The desktop fixture compares full and bounded shared
status for a 1,000-subtask goal, preserves its 2,018-character blocked reason, and
checks a dependency beyond the first page before choosing runnable work.

Owner observations cover standalone process identity, exit before registration
and detail-only owner death without a final write. Detail subscriptions also own
a cancellable lease/freshness deadline. Observation regressions cover registration
races, lost hints with controlled overflow, coalesced topics and reconnect.

The final Linux runtime check, clean desktop check, distribution creation and
packaged-data smoke passed after these repairs. macOS and Windows remain CI-only
evidence. SKILL-424 owns final application guards and native host replacement.
