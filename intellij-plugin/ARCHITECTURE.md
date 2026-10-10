# IntelliJ plugin architecture

This module is an isolated IntelliJ Platform plugin. It is an external consumer of
Skill Bill's versioned IDE status contract (`skill-bill work status --format json`)
and, from the details popup only, of two mutating CLI verbs: `skill-bill goal stop`
and `skill-bill goal pause`. It never imports `runtime-kotlin` persistence, workflow
engines, filesystem manifest parsers, JDBC, or SQLite, and it reads no Skill Bill
database.

## Adapted starter principles

Borrowed from the KMP Compose starter's *useful* boundaries — not its stack:

| Starter idea | IntelliJ adaptation |
| --- | --- |
| Inward dependency direction | `presentation` → `application`/`domain` ← `infrastructure`; only `composition` wires concrete adapters; `ui` consumes presentation + composition |
| Feature-owned presentation | Project-scoped `SkillBillStatusViewModel` owns `StateFlow<SkillBillStatusUiState>` |
| Thin UI entry points | Status-bar widget renders/emits intents |
| Explicit lifetimes | IntelliJ `Project` + `Disposable` replace custom App/User/Screen scopes |
| Repository-backed source of truth | `StatusRepository` port; CLI adapter is the sole live transport |
| Persistence behind a port | `PreferenceCachePort` for settings + optional last-known display cache |

Not copied: KMP, Compose Multiplatform, Room/SQLDelight, navigation graphs, or
code-generated DI.

## Package ownership

```
dev.skillbill.intellij
├── domain/           Status outcomes, value types, clock, display-cache model
├── application/      StatusRepository, PreferenceCachePort, StatusRefreshCoordinator
├── infrastructure/   CLI process adapter, IntelliJ PersistentStateComponent prefs
├── presentation/     ViewModel, UI state, StatusUiMapper, StatusBarPresentation
├── ui/               StatusBarWidgetFactory + SkillBillStatusBarWidget + settings Configurable (IntelliJ APIs)
└── composition/      SkillBillStatusCompositionRoot, SkillBillProjectStatusService
```

Architecture tests under `src/test/.../architecture` reject presentation→infrastructure
shortcuts and forbidden runtime/JDBC/SQLite imports as pure JVM source scans.
`presentation` must not import `StatusBarWidget` APIs; those live under `ui/`.

## Source of truth

1. **Authoritative live status** — Skill Bill CLI IDE status contract only.
2. **Preferences** — CLI executable override and refresh interval (seconds-scale;
   default 15s). Never workflow state.
3. **Last-known display cache** — optional, observation-time-marked. May produce
   only a **stale** UI state, never an authoritative active state.
4. **Elapsed time** — derived from authoritative `started_at` / `subtask_started_at`
   via an injected clock. Absent timestamps stay absent (shown as unavailable);
   never synthesized from `updated_at`. Clock-skew negatives clamp to zero.
   Only **active** work ticks against wall-clock now: a settled state (stale,
   blocked, failed) measures to its last authoritative `updated_at` and stays
   frozen there, so an abandoned run cannot grow a duration that reads as ongoing
   execution. Labels follow suit — "elapsed" while live, "ran" once settled. States
   that describe no run at all (idle, unavailable, incompatible) keep the neutral
   "elapsed"; "ran" there would assert an execution that never happened.

### Freshness versus lifecycle

For goals, "done" requires the runtime's final completion record. Completed
subtasks alone do not establish completion. PR publication and CI monitoring
remain part of the goal run, and blocked finalization stays blocked even when
every subtask is complete.

Freshness is a **modifier**, not a state. It replaces the lifecycle only when the
lifecycle claims to be live (`active` / `paused`), which is the genuine
went-silent case. Against a settled lifecycle (`blocked` / `failed` / `terminal` /
`idle`) the lifecycle is the more specific truth and wins; staleness is carried
alongside it and surfaces as the stale marker plus a tooltip note. A stale
`terminal` row therefore reads idle, never as work in progress.

## Status-bar expected states

Documented presentations (no screenshot asset required for this release):

| UI state | Status-bar text (concise) | Animation | Notes |
| --- | --- | --- | --- |
| Active | `Skill Bill · <step> · <goal> · <subtask> [· c/t]` | Yes | Progress `c/t` only when bounds are valid |
| Idle | `Skill Bill · idle` | No | No matching work / idle or terminal contract; a terminal row may additionally carry the stale marker |
| Stale | `Skill Bill · stale · …` | No | Cached/observation-marked; only reachable from an `active`/`paused` lifecycle |
| Blocked | `Skill Bill · blocked` | No | Distinct from failed; may additionally carry the stale marker; retained 24h |
| Failed | `Skill Bill · failed` | No | Distinct from blocked; may additionally carry the stale marker; retained 6h |
| Unavailable | `Skill Bill · unavailable` | No | Missing CLI, timeout, malformed, etc. |
| Incompatible | `Skill Bill · incompatible` | No | Contract-version mismatch |

Long or unsafe labels are normalized/truncated on the bar; full safe context stays
in the tooltip and accessibility description. Click → coalesced refresh + details
popup.

### Goal controls

The popup carries a **Stop goal** and a **Pause after current subtask** control, shown
only for an active `feature-goal` snapshot carrying an issue key. Eligibility and label
text are decided once in `GoalControlsPresentation` and consumed — never re-derived — by
the popup.

- `Stop goal` → `skill-bill goal stop <issue-key> --repo-root <canonical>`
- `Pause after current subtask` → `skill-bill goal pause <issue-key> --repo-root <canonical>`

Each mutating repository owns a `ProcessRunner` instance distinct from the status-poll
runner: `runCoalesced` coalesces per instance, so a shared runner would let a mutation
join an in-flight poll and return that poll's exit code. Calls dispatch off the EDT,
failures collapse to bounded summaries that never carry stdout, stderr, exception text,
or paths, and the plugin terminates no process itself — termination belongs to the
runtime verb. Pause disablement is snapshot-derived (`pause_requested`), so a request
made from the CLI or observed after an IDE restart still disables the control.

Resume is deliberately absent: it selects an agent, profile, and launch environment the
IDE does not own.

### Troubleshooting unavailable / incompatible

- **Unavailable / missing executable**: ensure `skill-bill` is on `PATH` or in
  `${SKILL_BILL_BIN_DIR:-$HOME/.local/bin}`, or set an absolute path in
  **Settings | Tools | Skill Bill**. Search-path lookup merges the platform's
  shell-aware environment with the IDE process environment: a desktop-launched IDE
  inherits the session `PATH`, which on macOS omits the user-local bin directory
  and everything else a shell profile adds.
- **Unavailable / misconfigured / process failure / timeout**: verify the CLI runs
  `skill-bill work status --format json` for the open project root outside the IDE.
- **Incompatible**: upgrade Skill Bill CLI or the plugin so IDE status
  `contract_version` matches (`0.1` for this release).

## Persistence policy

- Use IntelliJ `PersistentStateComponent` (or equivalent lightweight settings).
- Persist only: CLI path override, refresh interval, bounded display-cache fields
  plus `observedAt`.
- Never persist tokens, prompts, phase artifacts, raw stderr, absolute sensitive
  paths, or unbounded process output.
- Never write Skill Bill runtime databases.

## Composition and lifetimes

`SkillBillProjectStatusService` is the project-scoped composition root entry.
It constructs adapters, coordinator, and ViewModel with explicit constructors.
Polling starts only while a consumer (status-bar widget) is active, coalesces
overlapping polls, and cancels the loop plus child processes when the project
is disposed. There is no application-global mutable status cache.

## Compatibility

- Products: IntelliJ IDEA Community and Ultimate only.
- Range: IDEA **2025.2** (build `252`) and newer; no `until-build` pin.
- JVM toolchain: **JDK 21** for compile and `runIde`.
- Plugin id: `dev.skillbill.status` (Marketplace forbids the template word `intellij` in plugin IDs).
- Status-bar extension / factory / widget id: `SkillBillStatusBarWidget`.

## Deferred

Marketplace publishing, plugin signing, Remote Development / Split Mode support,
the full Skill Bill tool window, and every workflow mutation beyond the two goal
controls above — goal launching, resume, retry, and abandon — remain out of scope
for this status-bar release. `install.sh`, `uninstall.sh`, and `skill-bill install
apply` are never invoked from this module.
