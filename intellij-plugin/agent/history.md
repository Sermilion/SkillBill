## [2026-10-10] SKILL-416 subtask 2 — Plugin accept live over Done
Areas: intellij-plugin/application, intellij-plugin/src/test/kotlin/dev/skillbill/intellij/application
- StatusRefreshCoordinator.acceptsNewerStatus accepts incoming Active, Paused, or Blocked over a displayed Done for the same repositoryIdentity, branchCorrelation, and statusStoreId when executionId differs, even if incoming runSequence is lower or null.
- Live-over-Done is a coordinator-local isLiveOverDone predicate, not domain isLiveOutcome(). Failed and Stale stay on sequence rules. Cached Blocked is not the freeze-worthy terminal.
- Pattern: keep the same-execution terminal-regression reject; among two live snapshots, lower runSequence still loses. isStoreReplacement, correlation change, and uncorroborated-idle stay. reusable
- Coordinators do not rank workflows. Runtime selection remains the source of current work.
- Limitation: plugin-only still shows whatever work status returns. Runtime live-cohort selection is subtask 1.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-14] SKILL-238 subtask 3 — IDE extension YAGNI parity
Areas: intellij-plugin/{application,composition,domain,infrastructure/cli,ui}, intellij-plugin/{ARCHITECTURE.md,README.md}
- `CliGoalPauseRepository` / `CliGoalStopRepository` collapse into one `CliGoalMutationRepository` parameterized by a `GoalMutation` enum holding the verb and the five failure-summary strings; the `GoalPauseRepository` / `GoalStopRepository` ports and their outcome types become one `GoalMutationRepository` fun interface with `GoalMutationOutcome`.
- Pattern for near-duplicate CLI mutators: put the differing wording in enum constant fields, not in a second class, and keep the summary strings verbatim so UI text assertions stay untouched. reusable
- SKILL-168 runner isolation survives the collapse — the composition root still builds one repository per dedicated `ProcessRunner` (status, pause, stop) and cancels all three on dispose. A single shared class makes a copy-paste verb regression newly possible, so `GoalMutationWiringTest` asserts the *composed* pause instance emits `goal pause` and the composed stop instance emits `goal stop`. Pair any parameterization collapse with a wiring test at the composition seam. reusable
- `SkillBillStatusBarWidget.activateControl` selects the repository by control kind and makes one `requestMutation` call instead of two structurally identical `when` branches.
- Deleted as caller-less: `StatusClock.from(Clock)` and `PreferenceCachePort`'s `defaultRefreshIntervalSeconds()`. `StatusClock.system()` / `fixed()` remain the injectable ViewModel test seam; callers read `DEFAULT_REFRESH_INTERVAL_SECONDS` directly.
- Foreshadowing "future tool window" prose (extension-point section, table clause, README clause, service doc aside) is gone; the three factual deferred entries in ARCHITECTURE.md and README.md stay. Do not re-add speculative-consumer docs — record deferral as fact, never as design.
- Limitation: `intellij-plugin` is a standalone Gradle build, so its build proof needs `cd intellij-plugin && ./gradlew build` — the repo-root Kotlin pack gate does not reach it. Older entries below still name the deleted `GoalPauseRepository` / `GoalStopRepository`; they record what was true then.
Feature flag: N/A
Acceptance criteria: 5/5 implemented (AC-005 build proof executes in the runtime build/validate phases)

## [2026-09-01] SKILL-228 subtask 2 — Blocked operator reason in status details
Areas: intellij-plugin/{domain,infrastructure/cli,presentation,ui}
- Blocked lifecycle outcomes carry `pauseReason` from the IdeStatus wire through mapper → domain → presentation → status details popup so operators see the validate/build needs-user-action text without opening the agent transcript.
- Mapping stays additive on the existing blocked outcome shape; repair-loop active goals without an operator pause remain unchanged.
- Pattern: surface producer `pause_reason` / summary on blocked details only — do not invent pause copy in the plugin. reusable
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-28] SKILL-215 subtask 1 — Subtask elapsed from execution total
Areas: intellij-plugin/{domain,infrastructure/cli,presentation,prefs}
- Feature-goal snapshots now carry optional `current_subtask.active_duration_ms` / `active_duration_as_of` through mapper, outcomes, UI state, and last-known cache.
- `StatusUiMapper.subtaskElapsed` uses that total (plus live tail while `asOf` is present) instead of `elapsed(subtaskStartedAt, now)` when the field is present. Older CLIs that omit it keep the wall-clock fallback.
- Whenever both clocks exist, subtask elapsed is capped at goal elapsed so a pause gap cannot invert them. `withElapsed` still ticks only `Active` (including `pause_requested`); paused, blocked, failed, and terminal stay frozen.
- Pattern: reuse `activeElapsed` for any sibling execution total; cap the child clock at the parent when both are present. reusable
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-12] SKILL-184 current-phase execution status wording (subtask 2)

Areas: intellij-plugin/{domain,infrastructure/cli,presentation,ui}
- The IDE status wire now carries an optional `current_phase_execution` block (`phase_id`, `kind`, `count`, optional `total`), parsed into `CurrentPhaseExecution` and threaded domain → presentation → popup on the five outcomes that carry a current step.
- `SkillBillStatusBarPresentation.selectDisplaySlot` introduces a single mutually exclusive display slot: planning wins while relevant, execution fills the same slot afterwards, and bar/tooltip/accessibility/popup all read from that one selection so they cannot disagree. Reuse this slot pattern for any future competing status line rather than adding a parallel row. reusable
- Wording is producer-faithful per kind (`loop`/`pass`/`gate`/`attempt`, `n/total` only for `bounded_edge`); the plugin never invents a loop total nor re-labels an attempt as a semantic loop.
- New `getAsStrictInt` helper (number primitive, no fractional part, `intValueExact`) replaces permissive `getAsInt` for both the new counts and the existing `planning` counts — a numeric string or `4.5` is now a type error. Prefer it for new integer wire fields. reusable
- Malformed-block degradation follows the established `parsePlanning` rule: unknown kind, count < 1, over-length phase id, explicit-null total, or a total on a non-`bounded_edge` kind degrades the whole block to null while the outcome still maps.
- Planning progress now renders planned/total directly and bypasses the in-flight `renderedPosition` offset; the bar's drop-by-rank budget treats the merged slot the way it previously treated the planning segment.
- Limitation: one slot only — no simultaneous planning + execution row, no per-phase history of counts.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-08-11] SKILL-183 current-phase model in status popup (subtask 2)

Areas: intellij-plugin/{domain,infrastructure/cli,presentation,ui}
- The IDE status wire now carries an optional `current_model` object (`model` + optional `effort`), parsed into a `CurrentPhaseModel` and threaded domain → presentation → popup as one `Model:` row that is wholly absent when null. Producer side is the runtime (subtask 1).
- `IdeStatusJsonMapper.parseCurrentModel` follows the file's established optional-context degradation rule from `parsePlanning`: non-object, missing/blank/mistyped field degrades to null and the surrounding outcome still maps — never `Unavailable`/`Incompatible`. Copy this rule for any future optional wire block. reusable
- New private helper `getAsStringPrimitive` is strict: a JSON number or boolean is a type error, not coerced text — unlike the older lenient `getAsString`. Prefer it for new string wire fields. reusable
- Model carried only on outcomes that already carry a current step (`Active`, `Paused`, `Stale`, `Blocked`, `Failed`); `Idle`/`Done` have no current phase and must not carry it. Added with null defaults so no existing construction site or fixture broke.
- Sanitization is trim + bounded `take` (120 model / 40 effort) only; a model id can never carry a path, so `AbsolutePathGuard` was deliberately not widened.
- Limitation: status-bar text, icon, and tooltip are intentionally byte-identical — the model is popup-only. No per-phase model table, no new caching or refresh behaviour.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-08] SKILL-168 plugin goal Stop/Pause controls (subtask 5)

Areas: intellij-plugin/{application,composition,domain,infrastructure/cli,presentation,ui}, intellij-plugin/src/main/resources/META-INF
- The plugin now mutates workflow state: `GoalStopRepository` / pause repository issue `goal stop` and `goal pause` for the snapshot's issue key and canonical project root, dispatched off the EDT. Any doc or manifest text claiming "read-only, no workflow mutation" is now false and was removed from README, ARCHITECTURE, plugin.xml and `build.gradle.kts` `pluginConfiguration.description` — that Gradle field silently overrides plugin.xml, so scrub both. reusable
- Control eligibility is a pure function in `presentation/GoalControlsPresentation.kt`: visible only for `lifecycle_state: active` + `workflow_family: feature-goal` + non-null issue key. Keep eligibility in presentation, never in the widget. reusable
- Each mutating repository gets its own `ProcessRunner` instance from the composition root — three distinct runners total (status poll, stop, pause) — so a mutation can never return a poll's result. `ProcessRunnerIsolationTest` locks this in behaviourally, not just by identity. reusable
- Failure surfacing is a bounded, human-readable summary only; raw stdout/stderr and filesystem paths never reach the UI, and a failed mutation leaves polling running with the next snapshot authoritative.
- Pause disabled-state is driven by the snapshot's durable pause-requested signal (subtask 4 wire fields), so a CLI-originated pause and an IDE restart both render disabled — no local UI memory.
- `StatusDetailsPopupContent` isolates popup construction so it is testable without showing a Swing popup, following the widget's existing unit-test-mode handling; theme-derived colours only, no hardcoded palette.
- Limitations: Stop/Pause only — no resume, start, retry, abandon, replan, launching, tool window, or settings UI. Dependency surface unchanged (`com.intellij.modules.platform` only), no DB reads, no process termination by the plugin.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-08-07] SKILL-168 plugin transient-idle smoothing (subtask 1)
Areas: intellij-plugin/{application,domain,infrastructure/cli}
- A `no_matching_work` problem code now marks its derived `Idle` via the existing `StatusDiagnostic.reasonCode` channel rather than a new outcome variant or boolean — the presentation `when` stays exhaustive with zero new branches. Reuse this marker pattern for any "origin of an outcome" distinction. reusable
- Sequential display policy lives in `StatusRefreshCoordinator.refreshOnce`, the single place owning emit + cache write + sequential state. The mapper stays a pure per-response translation and presentation stays a pure function of one outcome; do not add memory to either. reusable
- Corroboration policy: an unconfirmed idle following a live outcome re-emits the previous in-memory outcome and returns early; `UNCORROBORATED_IDLE_TOLERANCE = 1` in `domain/Constants.kt` settles genuine idleness on the second consecutive sample. Any other outcome resets the counter.
- New domain predicates `isUncorroboratedIdle()` / `isLiveOutcome()` on `SkillBillStatusOutcome`; `isLiveOutcome` enumerates Active/Paused/Blocked/Failed/Stale exhaustively, so a new outcome variant forces an explicit liveness decision. reusable
- A hold re-emits the **in-memory** prior outcome only — never a `LastKnownDisplayCache` read, and never a cache write. This preserves the standing contract that a cached display may surface only as `Stale`, and keeps `observedAt` from advancing on a sample that observed nothing.
- Limitation: plugin-side smoothing only; the bad sample still originates in the runtime (subtask 2). Tolerance is a constant, not user-configurable, and `UnavailableReason.NO_MATCHING_WORK` stays retained-for-compatibility and untouched.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-07] SKILL-167 plugin release workflow and docs (subtask 3)
Areas: .github/workflows/plugin-release.yml, intellij-plugin/README.md, RELEASING.md
- Plugin ships on its own `plugin-v*.*.*` tag stream via a dedicated `plugin-release.yml`; `release.yml` and the other runtime workflows stay byte-identical and gain no plugin references. A runtime tag never builds the plugin and vice versa. reusable
- Version flows only through `-Pversion=<derived-from-tag>`; `gradle.properties` keeps `0.1.0-SNAPSHOT` and no file is rewritten during release. Tag remainder must be plain semver or the job fails closed. reusable
- Release job is hosted-only (`ubuntu-latest`, `intellij-plugin/gradlew`, Temurin 21) and runs `check verifyPlugin buildPlugin` with `failureLevel` unchanged — self-hosted runner is off-limits for plugin work. reusable
- Asset staging is fail-closed and verified: exactly one `skill-bill-intellij-plugin-<version>.zip` plus a `sha256sum -c`-verifiable `.sha256` sidecar in `release.yml`'s format; the asset check precedes release creation, so a bad build never publishes a partial release. reusable
- Docs pattern for future streams: RELEASING.md documents tag format + never-cross-streams; plugin README points at the released zip first and build-from-source only as fallback.
- Limitation: no JetBrains Marketplace publishing, no signing, no plugin-side changelog automation.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-07] SKILL-165 planning progress rendering (subtask 2)
Areas: intellij-plugin/{domain,infrastructure/cli,presentation}
- `IdeStatusJsonMapper` parses the optional planning block from `work status --format json`; absent or malformed planning degrades to no planning state rather than failing the whole status parse. reusable
- Planning surfaces as an extra bar segment plus two tooltip lines (planning phase + `Progress: n/m`); when planning is absent the rendered output stays byte-identical to the pre-change baseline. reusable
- Paused/pre-planning goals anchor elapsed at observation time in `StatusUiMapper` (paused states do not tick with the local UI clock); stale observations keep the STALE_NOTE alongside the planning segment. reusable
- Planning state round-trips through `LastKnownDisplayCache`, so a stale cached render keeps planning context instead of dropping to a bare status.
- Limitation: planning rendering is read-only display; no new CLI transport, no planning-driven refresh cadence, non-goal status families render unchanged.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-07] SKILL-148 status-bar widget (subtask 3)
Areas: intellij-plugin/{ui,presentation}, plugin.xml, docs
- Registered `SkillBillStatusBarWidget` StatusBarWidgetFactory (id matches factory/widget) for normal project windows with resolvable project context. reusable
- Thin widget consumes project-scoped ViewModel StateFlow on EDT, owns local UI elapsed ticker (no CLI poll per tick), dispose cancels subscription/ticker/consumer without leaking project refs. reusable
- Pure StatusBarPresentation maps every UI state with dual elapsed clocks, progress-bound validation, truncation, tooltip/a11y, active-only animation, and read-only click refresh+details popup. reusable
- Presentation + platform fixture tests cover states, clocks, disposal, and independent consumers; full dual-Project HeavyPlatform harness not required when two ViewModel pairs prove isolation. reusable
- Docs cover expected states, CLI path setup, refresh/elapsed behavior, unavailable/incompatible troubleshooting, and deferred tool window.
Feature flag: N/A
Acceptance criteria: 12/12 implemented

## [2026-08-07] SKILL-148 IntelliJ plugin architecture foundation (subtask 2)
Areas: intellij-plugin/{domain,application,infrastructure,presentation,composition}, docs, CONTRIBUTING
- Isolated IntelliJ Platform Gradle 2.x plugin (IDEA 2025.2–2026.1, JDK 21) with stable plugin id, no language-plugin deps, and owned check/buildPlugin/runIde/verifyPlugin tasks. reusable
- Inward dependency packages: domain outcomes/clock/display-cache, application ports + StatusRefreshCoordinator, CLI/prefs adapters, immutable StateFlow ViewModel, project-scoped composition root. Architecture tests reject presentation→infra shortcuts and runtime/JDBC/SQLite imports. reusable
- CLI adapter is the sole live transport for `skill-bill work status --format json`: absolute project root, off-EDT, timeout/output bounds, coalesced polls, contract-version gate, typed domain failures — never raw stderr/tokens/sensitive paths. reusable
- Preferences persist only CLI path + refresh interval; last-known display cache is observation-time-marked and can yield only stale UI, never authoritative active. Polling starts on consumer activate and cancels with child processes on project dispose.
- Pattern for future IDE surfaces: widget/tool window collects the same ViewModel StateFlow and emits refresh/lifecycle intents — no second status transport. reusable
- Limitation (by design): status-bar widget, tool window, Compose UI, and publishing are out of scope for this subtask.
Feature flag: N/A
Acceptance criteria: 11/11 implemented
