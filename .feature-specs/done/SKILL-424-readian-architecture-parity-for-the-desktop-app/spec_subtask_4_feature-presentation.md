# SKILL-424 Subtask 4 - Feature presentation

Parent spec: [.feature-specs/SKILL-424-readian-architecture-parity-for-the-desktop-app/spec.md](spec.md)
Issue key: SKILL-424

## Scope

Replace global `DashboardController` ownership with feature-owned presentation. Populate `core:ui` (shared state rendering, work UI primitives, portable route marker, entry DI/factory helpers) and `feature:work`, `feature:detail`, `feature:history`, and `feature:settings`. Each feature holds a colocated contract, ViewModel, route/arguments, UI models/mappers, and Compose route/content functions in `commonMain`. Delete `DashboardController` and superseded `BoardSource`/presentation wrappers once their last consumer migrates.

Parent sections: Feature presentation; Module responsibilities and dependencies; Host adapters and UI preservation.

## Acceptance Criteria

1. Work, detail, history, and settings each have a separate typed `UiState`/`Action`/`Effect` contract and ViewModel exposing read-only StateFlow. Mutable state is private, and no global controller or forwarding shell owns their work (parent AC-10).
2. Work keeps independent page/load state for Needs you, Goals running, and Standalone phases. History owns retention and its paging generation. Detail owns selection-specific queries and paged sublists. Settings owns drafts, persistence actions, and typed validation outcomes. No screen reads another screen's ViewModel for application data.
3. Initial loading, loaded empty, retained content refreshing, retained content with read error, append error, and control pending/refused stay distinct. Barrier-driven ViewModel tests catch loading flicker, lost rows, and stale-result overwrite (parent AC-11).
4. Effects follow an explicit per-family bounded delivery policy that does not replay after recomposition or restoration and retains required pending feedback through collector detach and reattach, proven by tests (parent AC-12).
5. ViewModels, contracts, routes, mappers, and screens live in feature `commonMain`. Features depend only on `core:common`, `core:domain`, `core:designsystem`, and `core:ui`, never on data, datastore, runtime, or another feature, and guards with rejection fixtures enforce it (parent AC-04, AC-06).
6. State derives from typed repository streams with typed intermediate combinations, not untyped Flow arrays and indexed casts. Content functions render from immutable state without a real database and keep current visuals, tooltips, accessibility semantics, and keyboard behavior (parent AC-25 screen part).
7. `DashboardController` and the superseded combined `BoardSource` have no production consumers and are deleted (parent AC-23 controller part).

## Execution rule

This subtask has no manifest dependencies. It runs on the current tree. It creates any missing module, convention, or contract its criteria need, keeps a change that is already present, and applies its rule to whatever anchors exist, whether moved, renamed, or deleted. When it moves or deletes a symbol, it updates every caller wherever that caller lives. It leaves `desktop-tray` configuring, compiling, and passing `./gradlew check`; it never lands a half-migrated tree that only a later subtask can repair.

The parent spec is authoritative for module ownership, allowed dependencies, lifetimes, reference patterns, and non-goals. Read its sections named under Scope before changing code. The Readian reference root is `/Users/braian.gapur/StudioProjects/readian-android`; record its current HEAD and any drift from the recorded `36d1dd9f902a7ab8b7eb2117e898e911fe51120d` before adopting a pattern.

## Non-goals

- Navigation 3 host, per-window ownership, or tray routing beyond what is needed to keep the current shell rendering these features.
- A generic `:presentation` module or a renamed controller.
- Any parent non-goal.

## Implementation Details

These tasks come from the preplan digest. That digest described a single-project `../../../desktop-tray` tree in which all production code sits under `src/main/kotlin/dev/skillbill/tray/`. Each "Assumption" line below marks a fact the digest did not establish. Implement confirms it against the current source before relying on it and records any change in its phase output. Paths are relative to `desktop-tray/`. Implement does not run builds, tests, or `check`. Those belong to the build and validate phases. The tests listed here are the ones to author.

### Task 1: Reconcile the tree before changing it (serves all ACs)

- Decide what subtasks 1 and 2 have already delivered. Check for:
  - `build-logic`;
  - the `core:*`, `feature:*`, and `composeApp` projects in `settings.gradle.kts`;
  - lifecycle/ViewModel, kotlinx-coroutines-test, and Compose UI-test entries in `gradle/libs.versions.toml`;
  - a portable time type in `core:common`;
  - the location of `Ports.kt` and the `WorkModels.kt` types;
  - whether `RoomBoardSource` is still one class implementing four ports;
  - generated Kotlin-Inject/Anvil components.
- Keep every piece that already exists. Later tasks use the "already present" branch for those pieces and the "missing" branch for the rest.
- Read `application/DashboardController.kt` (833 lines) and `DashboardControllerTest` (773 lines) in full. Map each controller responsibility to one owner:
  - work ViewModel;
  - history ViewModel;
  - detail ViewModel;
  - settings ViewModel;
  - tray preview ViewModel;
  - app-level notification coordinator;
  - app-level store-session coordinator;
  - shell window state.

  Keep this table and report it in the phase output. Subtask 6 builds the migration inventory from it.
- Try to read the Readian reference at `/Users/braian.gapur/StudioProjects/readian-android`. Record its HEAD and any drift from `36d1dd9f…`. Preplan could not reach this path. If it is still unreachable, record that and adapt the patterns from the parent spec's reference table: `PostDetailContract`, `PostDetailViewModel`, `InjectViewModel`, and `ViewModelEntry`.

### Task 2: Create only the missing modules (AC-5)

Skip this task for any module that exists. Otherwise:

- Register `core:common`, `core:domain`, `core:designsystem`, `core:ui`, `core:testing`, `feature:work`, `feature:detail`, `feature:history`, and `feature:settings`.
- Add one desktop KMP+Compose convention in `build-logic/convention`. It registers exactly one `jvm` target and no Android or iOS target, and features apply it.
- Feature `build.gradle.kts` dependencies:
  - `commonMainImplementation` may name only `core:common`, `core:domain`, `core:designsystem`, and `core:ui`.
  - `core:testing` is allowed only on test configurations.
- Add catalog entries for anything missing:
  - JetBrains multiplatform `lifecycle-viewmodel` and `lifecycle-viewmodel-compose`;
  - `kotlinx-coroutines-test`;
  - the Compose desktop UI-test artifact.

  Assumption: implement picks versions that resolve with Compose 1.12.1 and Kotlin 2.4.21.
- If `composeApp` does not exist, the root project stays the executable. It adds `implementation(project(":feature:…"))` and `core:*` dependencies, and subtask 5 moves the host. This is a compiling, working intermediate state, not a half-migration.

### Task 3: Portable domain contracts and time (AC-5, AC-6)

**Repository interfaces.** If the `Ports.kt` interfaces have not moved, move them and their types into `core:domain` `commonMain`:

- `WorkBoardRepository`, `WorkDetailRepository`, `WorkControlRepository`, `WorkStoreSession`, `SettingsStore`;
- `Outcome`, `Problem`, `ProblemCode`;
- `Board`, `Detail`, `DetailList`, `WorkItem`, `WorkIdentity`, `WorkSection`, `Retention`, `HistoryFilter`, `AppSettings`, `BoardRetention`.

Update every caller. `NativeServices` is a host port with no feature consumer, so it goes to the app or host owner (`composeApp`, or `composition/` if Task 2 kept the root).

**Delete the global types.** Delete `DashboardState`, `DashboardModel`, and `ListLoadState` from `Ports.kt`. Task 4 replaces them with per-screen state.

**Time.**
- Replace `java.time.Instant` and `Duration` in the moved code and in `project(board, now)` / `displayedDuration` with the type subtask 1 introduced.
- If subtask 1 introduced none, add an epoch-millis value class plus a `Clock` interface to `core:common`. This resolves preplan open decision 1 without a new dependency.
- Add a `DispatcherProvider` (or reuse the existing one) for injected dispatchers.
- The JVM `Clock` implementation lives in the host or composition source set.

### Task 4: `core:ui` shared presentation primitives (AC-3, AC-4, AC-6)

All of these live in `commonMain`.

**Shared list state.** Add a `PagedListState<T>` sealed type:
- `InitialLoading`
- `InitialError(problem)`
- `LoadedEmpty(refreshing)`
- `Content(items, refreshing: Boolean, readError: Problem?, append: AppendState)`

`AppendState` is `Idle | Loading | Failed(problem) | Exhausted`. `ControlState` is `Idle | Pending(kind) | Refused(problem)`. A pure reducer helper does the transitions: a refresh on `Content` sets `refreshing = true` and never returns to `InitialLoading`. A read failure on `Content` keeps its items and sets `readError`.

**Effect delivery policy.** Implement the policy once, as one documented type per effect family:

| Family | Examples | Mechanism | Lifetime rule |
|---|---|---|---|
| Navigation and host intents | open detail, copy text | `NavigationEffects<E>` over `Channel(capacity = 1, onBufferOverflow = DROP_OLDEST)`, exposed as `receiveAsFlow()` | One consumer. The latest intent survives detach. A consumed intent never replays after recomposition or restoration. |
| Transient feedback | "copied" | `Channel(capacity = 4, DROP_OLDEST)` | Losing it on overflow is acceptable. |
| Required feedback | control outcome or refusal, settings save failure | Not a channel. A bounded `pendingFeedback: List<Feedback>` held in `UiState`, max 8, newest kept, each item with a monotonic id | Removed only by an `Acknowledge(id)` action sent after display. |

The required-feedback design means a collector that was cancelled mid-handling cannot lose the item, and rendering keyed by id does not re-show it. Document in KDoc that delivery is not exactly-once.

**Moved UI primitives.** Move into `core:ui`: `ui/Pagination`, `ui/ReasonTooltip`, `ui/RunActionButtons`, `presentation/PhasePipelinePresentation`, and the work-row projection (`BoardProjection`, `project`, `displayedDuration`, now Clock-based). Work and history both render these rows.

**Design system.** If `core:designsystem` is missing, move `ui/Theme`, `ui/DesignComponents`, and `ui/DesktopUiScale` into it. Move the font files with their OFL licence files into its compose resources.
- Assumption: fonts load through JVM classpath APIs today. Common code needs Compose `Res.font`, and the resource-generation task needs an explicit dependency, not a name-substring `dependsOn`.
- `ui/WindowChrome` stays with the window host.

**Entry helper.** Add `rememberEntryViewModelStoreOwner(key)`. It creates one `ViewModelStore` per key and calls `clear()` when the key changes or the owner is disposed. This is the temporary per-selection owner for detail. Subtask 5 replaces it with the Navigation 3 ViewModelStore decorator, and it must not become an app singleton.

### Task 5: `feature:work` (AC-1, AC-2, AC-3, AC-6)

**Package and contract.** Package `dev.skillbill.tray.feature.work`, with `WorkContract.kt`:

- `WorkUiState(needsYou, goalsRunning, standalone: PagedListState<WorkRowUi>, filter, selected: WorkIdentity?, control: ControlState, pendingFeedback)`
- `sealed WorkAction`: `Refresh`, `LoadMore(section)`, `RetryAppend(section)`, `SetFilter`, `Select(identity)`, `Acknowledge(id)`
- `sealed WorkEffect`: `OpenDetail(identity)`

Assumption: list rows show no run-control buttons. If they do, add `WorkAction.Control` with the same typed control family and private `ControlState` as detail.

**`WorkViewModel`.**
- It extends `androidx.lifecycle.ViewModel` and takes `WorkBoardRepository`, `Clock`, and `DispatcherProvider` through its constructor. Use `@Inject` when Kotlin-Inject is present. No constructor defaults.
- One private `MutableStateFlow` per section. Each owns its own `observe(section, …)` job, cursor, append job, and generation counter. Results carrying an older generation are dropped.
- Public `state: StateFlow<WorkUiState>` comes from the typed 5-arity `combine(needsYou, goalsRunning, standalone, filter, selection)`. No vararg/array `combine` and no indexed casts.
- `refreshEvents` is filtered to this VM's three sections.
- `retainPages` is called per section. Assumption: `BoardRetention` can express one section. If it is global, implement makes it per-section so work and history cannot clobber each other.

**Screens.**
- `WorkContent(state, onAction)` holds the sidebar and list content moved out of `ui/MainWindow`. It is stateless, keeps the current visuals, tooltips, semantics, and keyboard handling, and does not touch a DB.
- `WorkRoute(viewModel, onOpenDetail)` collects state and effects.

**Tray preview.**
- Add `trayPreview/` with `TrayPreviewContract` and `TrayPreviewViewModel`, which reads bounded first pages from `WorkBoardRepository`.
- `TrayPreviewContent` comes from `ui/TrayPopover` and `ui/TrayUi`.
- Move the `TrayPresentation` and `TrayHoverPresentation` mappers here.
- The state exposes the hover text and the bounded goal/phase entries that the macOS status-item menu (integer codes 0–7 and 1000–1007) and the Windows/Linux popover render. The host reads only this ViewModel's state. It never reads `WorkViewModel`.

### Task 6: `feature:history` (AC-1, AC-2, AC-3)

- `HistoryUiState(retention, filter: HistoryFilter, list: PagedListState<WorkRowUi>, generation: Long, pendingFeedback)`.
- Actions: `SetRetention`, `SetFilter`, `LoadMore`, `RetryAppend`, `Refresh`, `Select`, `Acknowledge`. Effect: `OpenDetail(identity)`.
- `HistoryViewModel` owns retention and filter. Each change bumps `generation`, cancels the in-flight observation and append, and resets the list to `InitialLoading` only on that generation change. Late results from an older generation are discarded.
- It reacts only to history-section `refreshEvents`.
- `HistoryContent` and `HistoryRoute` hold the history list moved from `ui/MainWindow`.

### Task 7: `feature:detail` (AC-1, AC-2, AC-3, AC-4)

**State, actions, effects.**
- `DetailUiState(identity, content: DetailContent, control: ControlState, pendingFeedback)`.
- `DetailContent` is `Loading | Unavailable(problem) | Loaded(detail: DetailUi, readError: Problem?, refreshing, sublists: DetailSublists)`. `DetailSublists` is a typed holder with one `PagedListState` and cursor per `DetailList` value.
- Actions: `Refresh`, `LoadMore(list: DetailList)`, `RetryAppend(list)`, `Control(kind: ControlKind)`, `CopyReason`, `Acknowledge(id)`. `ControlKind` covers pause, stop, cancel, and launch/run again with an agent id, replacing `perform(action: String, …)`.
- Effects: `CopyToClipboard(fullReason)` (transient family). Navigation back is a route callback.

**`DetailViewModel`.**
- It takes the identity as an assisted or typed factory argument (`DetailViewModel.Factory` interface, no `Map<String, Any>`), plus `WorkDetailRepository`, `WorkControlRepository`, `Clock`, and dispatchers.
- It observes `observeDetail(identity)`. `LoadMore(list)` uses only that list's cursor through `detail(identity, cursor, list, startOffset)`.
- A control action sets `Pending(kind)` and calls the repository command. Refusal or failure becomes `Refused(problem)` plus a required-feedback item.
- It never writes a terminal status into `detail`. Only the observed stream changes lifecycle facts.
- Copy reason uses the full durable reason text, not the bounded label.

**Screens.** `DetailContent` and `DetailRoute` come from `ui/DetailPane`. The shell creates the ViewModel inside `rememberEntryViewModelStoreOwner(identity)`, so a new selection gets a new instance and the old one is cleared.

### Task 8: `feature:settings` (AC-1, AC-2, AC-3)

**State.**
- `SettingsUiState(readiness: Loading | Ready | Corrupt(problem), saved: AppSettings?, draft: SettingsDraft, validation: SettingsValidation, save: Idle | Saving | Failed(problem), pendingFeedback)`.
- Actions are edit actions for each persisted field, plus `Save`, `Discard`, and `Acknowledge`.

**`SettingsViewModel`.**
- It uses only the domain `SettingsStore`, through `settings` and `update(transform)`.
- It does not show defaults as ready before the first stored value arrives.
- `Save` runs only when `draft` validates.
- Validation is typed and syntactic, because filesystem discovery is forbidden in features. A blank or whitespace database path gives `PathBlank`. Assumption: no other field needs validation.

**Host-side effects of settings.** Applying start-at-login and switching the database are not settings-feature host calls. The app coordinators (Task 9) observe the persisted settings.

**Screens.** `SettingsContent` and `SettingsRoute` hold the settings-window content.

### Task 9: App-level coordinators and shell rewiring (AC-1, AC-2, AC-7)

**Coordinator location.** Put these in `composeApp` `commonMain`, or in `composition/` if Task 2 kept the root.

**`TransitionNotificationCoordinator`.**
- Move `TransitionTracker` and `enabledTransitions` out of `DashboardController.kt` into this app/session-owned class.
- It subscribes to the live and recent sections itself and establishes the silent baseline per observation session once all of them have arrived.
- It resets on store switch, keeps the store-epoch fencing, and calls `NativeServices.notify`/`notifyAuthorized`.
- It is independent of which screen is showing.

**`StoreSessionCoordinator`.**
- It observes settings readiness and the database path, calls `WorkStoreSession.selectDatabase`, and applies `NativeServices.setStartAtLogin`.
- Assumption: `DashboardController` does both of these today. Implement confirms this in Task 1.

**Shell rewiring.**
- Rewire `Main.kt` / `MainWindow` so the main window composes `WorkRoute`, `HistoryRoute`, and `DetailRoute`.
- The current tab (`chooseTab`) and the selected identity become per-window shell state. Subtask 5 replaces both with Navigation 3.
- The tray popover and macOS status item use `TrayPreviewViewModel`. The settings window uses `SettingsRoute`.
- Construct the ViewModels with the generated factories if subtask 2 has landed. Otherwise use explicit constructor calls at the single composition root. Never construct them inside a recomposing scope without a ViewModelStore owner.
- `trayAvailable`/`trayUnavailable` stay host state.

### Task 10: Delete the controller and the combined source (AC-7)

**`DashboardController`.** Delete it, with the `DashboardModel`/`DashboardState` leftovers and the old `ui/MainWindow`, `ui/DetailPane`, `ui/TrayPopover`, `ui/TrayUi`, and `presentation/*` files whose content moved.

**`RoomBoardSource`.**
- If it still implements `WorkBoardRepository`, `WorkDetailRepository`, `WorkControlRepository`, and `WorkStoreSession` in one class, split it into one class per port over one shared session owner. The owner holds the mutex, the selected-database generation, and `closeAndJoin`.
- Keep the observation, sidecar watcher, silent baseline, and fencing behaviour unchanged.
- Place the classes in `core:data` if it exists. Otherwise put them in `infrastructure/data/` and let subtask 2 relocate them.
- Delete the combined class.

**Callers.** Update `RoomBoardSourceTest`, `RoomBoardObservationFailureTest`, and `PackagedDataSmoke`.

### Task 11: Tests to add or relocate (AC-3, AC-4, AC-6)

Put these in feature `commonTest` (run through the JVM target), with fakes in `core:testing`. The fake `WorkBoardRepository`, `WorkDetailRepository`, `WorkControlRepository`, and `SettingsStore` use `CompletableDeferred` barriers per call. Use `StandardTestDispatcher` with `Dispatchers.setMain`.

Each test names the bug it catches:

1. **Work, refresh retains content.** Content is loaded, a refresh is held at a barrier, and the state stays `Content(refreshing = true)` with the same rows. Catches loading flicker.
2. **Work, append failure keeps rows.** Page 1 is loaded and the append fails, so the rows remain and `append = Failed`. A retry appends without duplicates. Catches lost rows.
3. **Work, section independence.** A Needs-you read error leaves Goals running and Standalone unchanged. Catches a shared load flag.
4. **History, stale generation.** The retention changes while the old query's result is held. Releasing the old result after the new one does not overwrite it. Catches stale-result overwrite.
5. **Detail, control refused.** `Control(Pause)` shows `Pending`, then the refusal shows `Refused`. The observed detail status is unchanged. Catches a fabricated transition.
6. **Detail, sublist cursor isolation.** `LoadMore(listA)` passes listA's cursor and leaves listB untouched. Catches wrong-list append.
7. **Detail, required feedback across detach.** A refusal happens with no collector. A later collector sees the feedback in state, `Acknowledge` removes it, and re-collecting shows nothing. Catches lost or replayed required feedback.
8. **`core:ui` navigation effects.** A consumed `OpenDetail` is not re-delivered to a new collector, and overflow keeps only the latest. Catches repeated navigation after recomposition.
9. **Settings, readiness.** Before the first stored value arrives, the state is `Loading` and `Save` is a no-op. A corrupt outcome becomes `Corrupt`. Catches connecting the default store before readiness.
10. **Settings, validation.** A blank path gives `PathBlank` and the store value is unchanged. Catches persisting invalid drafts.
11. **Coordinator, silent baseline.** Port the existing `TransitionTrackerTest`, plus any `DashboardControllerTest` baseline cases, to the coordinator. Initial data and a store switch produce no notification. Catches historical completion notifications.
12. **Content.** Move `MainWindowTest` and `ReasonTooltipTest` to the owning module's `jvmTest`. Add one Compose UI test that renders `WorkContent` from an immutable `Content(readError = …)` state, with no DB, and asserts the rows, the error banner, and the accessible names. Catches content wired to live data or lost semantics.

**Other existing tests.** Move `ProjectionTest` and `TrayHoverPresentationTest` with their code. Delete `DashboardControllerTest` only after each case is ported or listed as covered elsewhere in the Task 1 table. Do not drop regression cases tied to past bugs. Write no tests for annotations, directory names, or trivial mappers.

### Task 12: Architecture guards (AC-5, AC-6, AC-7)

Extend the module-aware `DesktopArchitectureTest` (subtask 1's rewrite if present, otherwise the current one). Every rule asserts a nonempty scan set and has a rejection fixture run through the real PSI scanner.

1. **Feature imports.** Feature and `core:ui` production sources must not import:
   - the data, datastore, or infrastructure/composition packages;
   - runtime `skillbill.*`;
   - `androidx.room` or `androidx.datastore`;
   - `java.*`/`javax.*`, including `java.time` and AWT;
   - another feature's package.

   Fixtures cover a direct import, an aliased import, and a fully qualified reference.
2. **Feature project dependencies.** Each feature's resolved project dependencies must be a subset of {`core:common`, `core:domain`, `core:designsystem`, `core:ui`}, and `core:testing` may appear only in test configurations. Check this from Gradle configuration data, either in a convention verification task wired into `check` or in subtask 1's existing dependency guard. Add a rejection test in `build-logic` convention tests.
3. **Untyped combine.** Feature production sources must not call a `combine` overload taking an array/iterable lambda parameter, and must not use an array-index expression followed by an `as` cast. Add a rejection fixture.
4. **Deleted symbols.** No production declaration or reference to `DashboardController`, `DashboardModel`, `DashboardState`, or `RoomBoardSource`, with a rejection fixture.

No baseline or exemption may grow.

### Constraints

- Features never depend on one another. Cross-feature selection flows through typed route callbacks in the shell.
- No generic `:presentation` module, renamed controller, or forwarding shell.
- No service locator, string-keyed argument map, or unchecked cast.
- Navigation 3, per-window stacks, and typed host intents stay with subtask 5.
- Docs, CI, and the final inventories stay with subtask 6.
- Preserve the current visuals, tooltips, keyboard shortcuts, accessibility semantics, and tray behaviour.
- Never run install, uninstall, or install-sync commands.

## Validation Strategy

Run the desktop `./gradlew clean check --no-build-cache`, common tests through the JVM target, and ViewModel/effect tests with test dispatchers and barriers.

## Next Path

The navigation subtask hosts these features' routes and entries.

## Spec Path

.feature-specs/SKILL-424-readian-architecture-parity-for-the-desktop-app/spec_subtask_4_feature-presentation.md
