# SKILL-424 Subtask 5 - Navigation and host lifetimes

Parent spec: [.feature-specs/SKILL-424-readian-architecture-parity-for-the-desktop-app/spec.md](spec.md)
Issue key: SKILL-424

## Scope

Wire the common Navigation 3 app shell in `composeApp`, with entry injection, restoration, per-window ownership, typed tray/notification routing, database-generation transitions, and teardown. Feature modules own their route declarations; `composeApp` owns the host, root sections, cross-feature entry adapters, and callbacks.

Parent sections: Navigation 3 and desktop windows; Dependency injection and resource ownership; Host adapters and UI preservation.

## Acceptance Criteria

1. The common shell uses `NavDisplay` with a typed serializable back stack, explicit saved-state serialization, and saveable-state-holder and ViewModelStore entry decorators. Central app entry adapters register work, work detail, history, history detail, and settings. Routing is not driven by a global selection field (parent AC-13).
2. Entry DI with typed assisted arguments creates the correct ViewModel once per live entry and retains it across recomposition. Missing or wrong arguments produce an attributable outcome without string-keyed `Any` maps or unchecked casts (parent AC-09, AC-07 entry part).
3. App, database-session, window, and entry scopes have explicit owners and destruction conditions. A two-window test counts instances and jobs and shows shared repositories/preferences with isolated stacks, selection, and entry ViewModels (parent AC-08, AC-15).
4. Back and root-section switching restore valid filter, selection, and scroll anchor while keeping the sidebar and master/detail layout (parent AC-14).
5. Tray and notification intents dispatch onto the UI dispatcher, then focus or create the intended window and navigate with typed identity, with defined behavior for no window, hidden window, and multiple windows. Repeat activation reuses the entry instead of growing duplicates (parent AC-16).
6. Window hiding, disposal, and app exit follow distinct ownership rules. Disposal cancels entry collectors without closing a database another window uses, and exit closes all windows, entries, and shared resources (parent AC-15).
7. Database switch and same-path replacement invalidate old routes, results, cursors, selection, and notification baseline before new-session rendering, and no old entry renders with a new session component, proven by barrier-driven tests (parent AC-17).
8. Missing work and invalid or obsolete restored routes yield explicit bounded outcomes and a safe root with recovery navigation (parent AC-18).

## Execution rule

This subtask has no manifest dependencies. It runs on the current tree. It creates any missing module, convention, or contract its criteria need, keeps a change that is already present, and applies its rule to whatever anchors exist, whether moved, renamed, or deleted. When it moves or deletes a symbol, it updates every caller wherever that caller lives. It leaves `desktop-tray` configuring, compiling, and passing `./gradlew check`; it never lands a half-migrated tree that only a later subtask can repair.

The parent spec is authoritative for module ownership, allowed dependencies, lifetimes, reference patterns, and non-goals. Read its sections named under Scope before changing code. The Readian reference root is `/Users/braian.gapur/StudioProjects/readian-android`; record its current HEAD and any drift from the recorded `36d1dd9f902a7ab8b7eb2117e898e911fe51120d` before adopting a pattern.

## Non-goals

- Forcing a mobile full-screen layout or redesigning the tray popover.
- Navigation restoration state in workflow tables.
- Any parent non-goal.

## Validation Strategy

Run the desktop `./gradlew clean check --no-build-cache` with Navigation 3 host/entry tests, two-window tests, and generation-transition tests using deterministic dispatchers and barriers.

## Next Path

The delivery subtask removes remaining legacy paths and records evidence.

## Spec Path

.feature-specs/SKILL-424-readian-architecture-parity-for-the-desktop-app/spec_subtask_5_navigation-and-host-lifetimes.md

## Implementation Details

The tasks below use the preplan digest as their only repository knowledge. Paths are relative to `../../../desktop-tray`. The package prefix is `dev.skillbill.tray`. Lines marked **Assumption** record a fact the digest did not establish. Implement confirms each one against the tree before acting on it and records any difference in its summary.

### Task 1 - Census the tree and record reference drift (serves all ACs)

1. Record which of these anchors exist on the current tree:
   - `composeApp` and `core:{common,domain,data,datastore,ui}`
   - `feature:{work,detail,history,settings}` and their ViewModels and contracts
   - the scope annotations in `core:common`
   - the generated app and database-session components
   - catalog entries for lifecycle/ViewModel, Navigation 3, saved state, kotlin-inject and anvil
   - the location of `TransitionTracker`
   - the database-session owner and whether it exposes a generation
   - whether `DashboardController` and `DashboardState.selected` still exist
2. Subtasks 1 through 4 are ordered before this one, so the plan expects them to be present. **Assumption:** they have landed. If an anchor is missing, apply the Execution rule:
   - Create only the smallest missing piece the ACs below need, in the owner the parent spec assigns.
   - A missing per-screen ViewModel is created in its feature module as a real typed contract. It must not forward to `DashboardController`.
   - Missing catalog entries are added and resolved for common and JVM. If they cannot be resolved, report a blocking prerequisite. Do not defer.
3. Record the Readian HEAD and any drift from `36d1dd9f…` for these reference files before adapting them:
   - `ReadianNavHost.kt`, `RouteBackStack.kt`, `entries/DetailEntries.kt`
   - `InjectViewModel.kt`, `ViewModelEntry.kt`
   - `Scopes.kt`, `JvmComponentFactory.kt`

   The preplan could not read the Readian root. If this session can't read it either, say so and adapt the patterns from the parent spec's reference table only.
4. Read `Main.kt` in full before changing it. This applies wherever `Main.kt` now lives, including `composeApp/src/jvmMain` if it has already moved. The integer status-item codes, the window flags and the Linux tray probe are mapped from the real source, not from the digest summary.

### Task 2 - Routes and a typed back stack (AC-001, AC-008)

**Shared navigation primitives** go in `core:ui` `commonMain` under the `navigation` package:
- A route marker `AppRoute` (a Navigation 3 `NavKey`).
- A portable `DatabaseGenerationRef` value (a `Long` generation plus a store-identity token).
- **Assumption:** `DatabaseGeneration` belongs in `core:domain` if the session port already uses one there. If so, the route reference wraps it.

**Feature routes** are declared in their own modules as `@Serializable` data classes or objects:
- `feature:work` declares `WorkRoute(section, filter)`.
- `feature:detail` declares `WorkDetailRoute(identity: WorkIdentityKey, origin, generation)` and `HistoryDetailRoute(identity, generation)`.
- `feature:history` declares `HistoryRoute(filter)`.
- `feature:settings` declares `SettingsRoute`.

Route rules:
- Routes carry only durable identity and small arguments. They never carry Room entities, DAOs, components, `Path`, closures or decoded payloads.
- `WorkIdentityKey` is a serializable mirror of the domain `WorkIdentity`, built through one mapper in the owning feature.

**Back stack** goes in `composeApp/src/commonMain/kotlin/dev/skillbill/tray/app/navigation/RouteBackStack.kt`:
- It keeps one serializable stack per root section (`RootSection.Work`, `History`, `Settings`) plus the active section.
- It is persisted through a `SavedStateConfiguration` whose `SerializersModule` registers every route subclass polymorphically and explicitly. There is no reflection and no class-name lookup.
- The saved form is a versioned envelope, `RouteStackSnapshot(formatVersion, generation, sections, active)`.

**Restore validation** produces a typed `RouteRestoreOutcome`:
- `Restored`, or `ResetToRoot(reason)`.
- The reasons are `UnknownRoute`, `MalformedPayload`, `FormatVersionMismatch` and `ObsoleteGeneration`.
- Each outcome carries a bounded `RestoreDiagnostic` (capped count and truncated text).
- The safe root is `WorkRoute(NeedsYou)` of the current generation.
- Restore state is session-local only. Nothing is written to workflow tables.

### Task 3 - NavDisplay host with desktop list-detail layout (AC-001, AC-004)

**Host** goes in `composeApp/src/commonMain/.../app/navigation/SkillBillNavHost.kt`:
- It calls `NavDisplay(backStack, onBack, entryDecorators = listOf(saveable-state-holder decorator, ViewModelStore decorator), sceneStrategy = desktop list-detail strategy, entryProvider = appEntryProvider(...))`.
- The existing sidebar stays outside `NavDisplay`. Sidebar clicks switch `RootSection`.
- A composeApp-owned `ListDetailSceneStrategy` renders a list route and its detail route side by side, which keeps today's master/detail panes. **Assumption:** the pinned JetBrains Navigation 3 version exposes `SceneStrategy`. If it does not, implement an equivalent two-pane scene in composeApp and do not fall back to full-screen.

**Entry adapters** go in `composeApp/src/commonMain/.../app/navigation/entries/{WorkEntries,DetailEntries,HistoryEntries,SettingsEntries}.kt`:
- They register work, work detail, history, history detail and settings.
- They wire typed callbacks, such as `onOpenDetail(identity)` calling `navigator.openDetail(...)` and `onBackToList`.
- Feature modules never import one another.

**Restoration policy (AC-004):**
- List routes keep their filter in the route.
- Selection and scroll anchor live in per-entry saveable state, which the saveable-state decorator owns.
- Back pops the detail and restores the list entry's filter, selection and first-visible anchor. The anchor is the identity, with a fallback to the nearest still-present index.
- Switching sections keeps every section's stack intact.

**Global selection:** delete routing through any global selection field (`DashboardState.selected`, `selectedWork`, or the window flags in `Main.kt`) in the same change. Update every caller.

### Task 4 - Entry DI with typed assisted arguments (AC-002)

**Entry injection helper** goes in `core:ui` `commonMain` under `navigation/InjectViewModel.kt`:
- It follows Readian's component-holder pattern. An `EntryComponentHolder` ViewModel, stored in the entry's ViewModelStore, retains the generated entry component.
- ViewModels are created through `viewModel(key = route key) { factory(args) }` with a typed factory.
- There are no `CreationExtras` string keys, no `Map<String, Any>`, and no unchecked casts.

**Assisted factories:**
- Each feature ViewModel takes kotlin-inject `@Assisted` typed arguments, for example `WorkDetailArgs(identity, generation)`.
- The generated `(WorkDetailArgs) -> WorkDetailViewModel` factory is exposed by the entry component, which is contributed to `@EntryScope`.

**Argument validation:**
- `WorkDetailArgs.from(route)` and its siblings return a typed `EntryArgsOutcome`: either `Valid(args)` or `Rejected(routeType, reason)`.
- The adapter renders `Rejected` as a bounded error pane with recovery navigation and never calls the factory.

**Architecture guard:** add one rule to `DesktopArchitectureTest`, or to the module guard if one exists:
- The scope is app-authored navigation and DI sources in `composeApp` and `core:ui`.
- The rule rejects `Map<String, Any`, `Map<String, Any?>`, typealiases to either, and `@Suppress("UNCHECKED_CAST")`.
- It needs a nonempty scan set and a synthetic rejection fixture run through the real PSI scanner, including an aliased-import case.

### Task 5 - Scopes, owners and window lifetimes (AC-003, AC-006)

**Scope annotations** in `core:common` `commonMain` under `di/Scopes.kt`: `@AppScope`, `@DatabaseSessionScope`, `@WindowScope` and `@EntryScope`. Reuse them if they already exist.

**Component graph:**
- `AppComponent` is a `@MergeComponent(AppScope)`. It is built once by the JVM host and owns preferences, the host-intent router, the transition/notification coordinator, the window registry and the session owner.
- The `DatabaseSessionComponent` child is created per generation by the session owner.
- The `WindowComponent` child of the app holds the window owner and navigator.
- The `EntryComponent` is created from the current session component plus the window handle. Windows outlive database switches, so entries bind to both.
- **Assumption:** the kotlin-inject/anvil version supports this child-component shape. If it does not, use a generated factory with explicit typed parameters. Never use a hand-built collaborator tree.

**Owners** in `composeApp/src/commonMain/.../app/lifetime/`:
- `WindowRegistry` (app scope) holds an ordered set of live `WindowOwner`s and the last-focused order.
- Each `WindowOwner` has a `SupervisorJob`, a navigator, and a window `LifecycleOwner`.

**Lifecycle operations:**

| Operation | Behavior |
|---|---|
| `hide()` | Keeps the stack and entries. Moves the window lifecycle to `CREATED`, so lifecycle-aware collectors stop. Matches today's hide-on-close main window. |
| `dispose()` | Clears every entry ViewModelStore, which cancels the `viewModelScope`s, then cancels and joins the window job and unregisters. It never closes the session, because the app owns the session. |
| `AppOwner.shutdown()` | In order: stop intake → dispose and join all windows → session `closeAndJoin` → settings `closeAndJoin` → native tray and status-item teardown → `exitApplication`. |

`AppOwner` goes in `composeApp/src/jvmMain/.../app/AppOwner.kt`. It follows Readian's `JvmComponentFactory`, so the host owns startup and shutdown and there is no global singleton.

**Production windows:**
- The existing main window and settings window become `WindowOwner`s, each with its own `NavDisplay`. The settings window's root is `SettingsRoute`.
- A "New window" action (Cmd/Ctrl+N in the main window) opens another main window through the registry.
- **Assumption:** Cmd/Ctrl+N does not conflict with an existing shortcut. If it does, pick a free accelerator and record the choice.
- The tray popover stays a bounded preview and is not a navigation window.

### Task 6 - Typed host intents and the JVM adapter move (AC-005)

**Intent types** in `composeApp/src/commonMain/.../app/host/HostIntent.kt` (sealed): `OpenSection(section)`, `OpenDetail(identity, generation)`, `ShowMainWindow`, `OpenSettings`, `NewWindow`, `Quit`.

**`HostIntentRouter`** (app scope):
- It accepts intents from any thread and switches to the injected `@UiDispatcher` (from `core:common`, with `Dispatchers.Main`/Swing provided in jvmMain) before it touches any window or navigator.
- Target window policy:
  1. The focused visible window.
  2. Otherwise, the most recently focused window. If that window is hidden, show it first.
  3. Otherwise, create a new window.
- It activates (shows and focuses) the window before navigating.

**Navigator deduplication:**
- `openDetail` is a no-op when the top detail already has the same identity and generation.
- If that identity is deeper in the stack, pop back to it.
- Repeat activation therefore never grows the stack.

**JVM adapters:**
- Replace the macOS integer action codes (0–7 goals, 1000–1007 phases, 2000/2002/2003/2004) with a `MacStatusItemAction` → `HostIntent` mapping in jvmMain. Unknown codes produce a bounded diagnostic and no navigation.
- Windows/Linux `Tray(...)` callbacks and notification clicks emit `HostIntent`s too.

**Moves into `composeApp`:** move these if they are not already there, and update every caller:
- **Into `src/jvmMain/kotlin/`:** `Main.kt` (and `mainClass` → `dev.skillbill.tray.composition.MainKt` or its new FQN), `NativeServicesImpl`, `NativeTrayHost`, `MacStatusItemBridge`, `TrayPopoverPlacement`, `TrayIconRenderer` and the JVM `DesktopUiScale`.
- **Into `src/jvmMain/native/{linux,macos}`:** the native sources.
- **Into `src/jvmMain/resources`:** `icons/skill-bill.svg`. Fonts stay with `core:designsystem` if subtask 1 already moved them.
- **Into composeApp or the desktop-application convention:** the tasks `compileAppImageLauncher`, `compileLinuxTrayNative`, `compileMacTrayNative`, `stageAppImage` and `packageAppImage`. Wire them through real task inputs and outputs, keep root aliases, and keep the bundle ID, package name, formats and pinned Adoptium `javaHome`.

Behavior to preserve:
- the 4-second Linux tray probe fallback
- KDE `kscreen-doctor` placement (the one approved process use)
- the main window staying mounted while hidden

The architecture guard's approved native-process file list may only be re-pathed to the same four files. It must not grow.

### Task 7 - Database-generation transitions (AC-007)

**`SessionTransitionCoordinator`** (app scope, `composeApp/.../app/lifetime/`) observes the session owner's generation.

- **Assumption:** the session port exposes `generation: StateFlow<DatabaseGeneration>`, and same-path replacement increments the generation. If it does not, add that to the `core:domain` session port and its `core:data` owner.

It publishes `SessionState` as `Transitioning(next)` or `Ready(generation, component)`. On every switch or same-path replacement, it runs these steps in order:
1. Publish `Transitioning`. Every window renders a neutral transition root and no entries.
2. Reset every window's stacks to an empty root, which clears old entry ViewModelStores and cancels their collectors, cursors and results.
3. Clear per-window selection.
4. Await the old session's `closeAndJoin`.
5. Reset the notification baseline through the app-level transition coordinator (`TransitionTracker.clear()` plus an epoch fence), so the new store produces no historical completion notifications.
6. Once the new component is ready, publish `Ready` and seed each window with `WorkRoute(NeedsYou)` stamped with the new generation.

Generation guards:
- Every entry adapter compares `route.generation` with `Ready.generation` before it resolves a factory. On a mismatch it renders `ObsoleteRoute` and never touches the new component.
- Entry content keys include the generation, so a ViewModelStore is never shared across generations.

### Task 8 - Missing work and recovery (AC-008)

- When work is missing, the detail ViewModel exposes an explicit `Unavailable` state, and the adapter supplies `onBackToList`. If subtask 4 did not add `Unavailable` to the detail contract, add it.
- Restore outcomes from Task 2 and argument rejections from Task 4 render a bounded diagnostic pane with a "Back to work" action, which resets to the safe root.
- Nothing silently reopens a different store.

### Tests to add

Add tests in `composeApp/src/jvmTest` and the `commonTest` source sets. Use deterministic test dispatchers, `CompletableDeferred` barriers, and counting fakes for the repository and session. Never use `relaxed = true` mocks; use `relaxUnitFun = true` if a mock is unavoidable.

| Test | Covers | Realistic bug it catches |
|---|---|---|
| `SkillBillNavHostTest` (Compose UI test) | AC-001, AC-004 | Detail opened via a global selection field; back returns to page one or loses filter/anchor; a section switch drops another section's stack; list-detail collapses to full-screen |
| `EntryViewModelOwnershipTest` | AC-002 | Two detail routes share one ViewModel or get swapped arguments; recomposition recreates the VM; a popped entry is not cleared; a rejected argument still calls the factory |
| `TwoWindowLifetimeTest` | AC-003, AC-006 | A second session owner or preferences instance per window; selection leaking across windows; disposal leaving entry jobs or closing a session that window B still uses; exit leaving active jobs or unclosed resources (asserts instance and job counts) |
| `HostIntentRouterTest` | AC-005 | Navigation mutated off the UI dispatcher; the wrong window focused for the no/hidden/multiple cases; repeat `OpenDetail` growing the stack |
| `DatabaseGenerationTransitionTest` | AC-007 | A new-session root rendered before the old close joins; a late old-generation result rendered; an old-generation route invoking the new component's factory; historical completion notifications after a switch; same-path replacement treated as a no-op |
| `RouteRestorationTest` | AC-008 | A crash or silent reuse on unknown, malformed, version-mismatched or obsolete-generation snapshots; missing work with no path back |
| Architecture guard rejection fixture (Task 4) | AC-002 | A string-keyed `Any` argument bag or unchecked cast reintroduced, including through an alias |

No test is planned for the static `MacStatusItemAction` code table or for the scope annotations. A test for either would only mirror declarations.

### Constraints

- Keep visuals, typography, icons, window sizing, tray hover and popover bounds, tooltips, keyboard shortcuts and accessibility semantics. There is no mobile layout and no popover redesign.
- Common source has no `java.*`, AWT, JDBC or runtime imports. All host code lives in `composeApp/src/jvmMain`.
- No baseline or exemption grows, and existing guards keep nonempty scan sets.
- The desktop build must configure, compile and keep its tests green when this subtask ends. Implement does not run `check`, because validate owns that.
- Run no install, uninstall or install-sync commands.
