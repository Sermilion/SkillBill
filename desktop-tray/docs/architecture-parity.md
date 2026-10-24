# Desktop architecture parity

This record maps the compiled desktop build to the Readian reference patterns SKILL-424 adopted. It holds three tables: the
module inventory, the migration inventory for the retired single-module tree, and the Readian parity matrix. Evidence cells
name tests and Gradle tasks only; executed results, counts and hosts belong to the validate-phase report.

Task paths are relative to `desktop-tray/`. Every module's `check` is reached from the root `check`, which depends on each
child project's `check` and on the included build's `:convention:check`. Root `test` runs every module's `jvmTest`.

## Reference revision

The reference root is `/Users/braian.gapur/StudioProjects/readian-android`. Its current HEAD is
`69f9161c116dde3eb011a7e41f3a9f2fdf6a6377`. The recorded revision `36d1dd9f902a7ab8b7eb2117e898e911fe51120d` (observed
2026-10-11) does not resolve in the clone, so drift could not be diffed. No new reference patterns were adopted; the rows
below keep the patterns recorded against the earlier revision.

## Module inventory

Every project in `settings.gradle.kts` has sources and a build script. Their `check` and `jvmTest` tasks are
`:<project>:check` and `:<project>:jvmTest`.

| Project | Owns | Main production symbols | Production consumers |
| --- | --- | --- | --- |
| `:core:common` | Scopes, dispatcher provider, host environment, diagnostics port | `AppScope`, `DatabaseSessionScope`, `WindowScope`, `EntryScope`, `DispatcherProvider`, `HostEnvironment`, `Diagnostics` | every other project |
| `:core:domain` | Work models and repository ports, no storage, runtime or UI types | `WorkItem`, `Board`, `Detail`, `AppSettings`, `WorkBoardRepository`, `WorkDetailRepository`, `WorkControlRepository`, `WorkPolicyRepository`, `WorkStoreSession`, `SettingsRepository`, `Outcome` | `:core:data`, `:core:datastore`, `:core:ui`, `:core:testing`, features, `:composeApp` |
| `:core:data` | Read-only Room adapter, runtime projection, commit-signal watcher, database session, control admission | `DatabaseSession`, `WorkStoreSessionManager`, `RoomWorkBoardRepository`, `RoomWorkDetailRepository`, `RuntimeWorkControlRepository`, `RuntimeWorkPolicyRepository`, `RuntimeWorkMapper`, `DesktopCommitSignalObserver`, `DatabaseSessionComponent` | `:composeApp` |
| `:core:datastore` | DataStore preferences and one-time legacy migration | `DataStoreSettingsRepository`, `LegacySettingsMigration`, `StoredSettings` | `:composeApp`, `:core:data` tests |
| `:core:designsystem` | Theme, palette, fonts and design components | `DesignSystemTheme`, `Palette`, `DesignComponents` | `:core:ui`, features, `:composeApp` |
| `:core:ui` | Shared presentation: paged lists, section pager, effects, feedback, entry stores, route and ViewModel factory contracts | `PagedListState`, `TrackedBoardSections`, `SectionPager`, `AppRoute`, `EntryArgs`, `InjectViewModel` | features, `:composeApp` |
| `:core:testing` | Fakes and fixtures | `FakeRepositories`, `WorkFixtures`, `TestClock` | test source sets of `:core:ui`, `:core:data`, features |
| `:feature:work` | Work list and tray popover screens | `WorkViewModel`, `TrayViewModel`, `WorkRoute`, `WorkContent`, `TrayPopoverContent` | `:composeApp` |
| `:feature:detail` | Detail screen | `DetailViewModel`, `DetailRoutes`, `DetailContent`, `DetailPresentation` | `:composeApp` |
| `:feature:history` | History screen | `HistoryViewModel`, `HistoryRoute`, `HistoryContent` | `:composeApp` |
| `:feature:settings` | Settings screen | `SettingsViewModel`, `SettingsRoute`, `SettingsContent` | `:composeApp` |
| `:feature:specs` | Spec-store browser pane; selection is pane-local and never navigates | `SpecsViewModel`, `SpecsRoute`, `SpecsPane`, `SpecStatusDot` | `:composeApp` |
| `:composeApp` | The shell: merged application component, per-window and per-entry graphs, navigation, window lifetimes, host intents, tray, notifications, login start, packaging | `ApplicationComponent`, `WindowComponent`, `EntryComponent`, `EntryViewModels`, `ViewModelFactory`, `AppOwner`, `WindowRegistry`, `WindowOwner`, `WindowNavigator`, `HostIntentRouter`, `SessionTransitionCoordinator`, `StoreSessionCoordinator`, `NativeServicesImpl`, `NativeTrayHost`, `MacStatusItemBridge` | none; it is the application |

Distribution tasks live in `:composeApp` and the `skillbill.desktop.application` convention: `createDistributable`,
`stageAppImage`, `packageAppImage`, `compileAppImageLauncher`, `compileLinuxTrayNative`, `compileMacTrayNative`,
`packagedDataSmoke`. The root build aliases each of them. Their native inputs are under `composeApp/src/jvmMain/native/`.

## Migration inventory

The retired tree was one `desktop-tray/src/{main,test}` module. Nothing remains under root `src`.

### Production files

| Old owner | Final owner |
| --- | --- |
| `application/DashboardController.kt` | Deleted. Replaced by the feature ViewModels, `:core:ui` `TrackedBoardSections` and `PagedListState`, and the `:composeApp` coordinators. |
| `application/Ports.kt` | Repository ports moved to `:core:domain` `Repositories.kt`; `NativeServices` to `:composeApp` `application/Ports.kt`. The combined `BoardSource` is deleted. |
| `composition/Main.kt` | `:composeApp` `composition/Main.kt`, with `AppOwner` and `ApplicationComponentFactory`. |
| `domain/WorkModels.kt`, `domain/DesktopUiScaleConfiguration.kt` | `:core:domain` `model/`. |
| `infrastructure/Preferences.kt` | Deleted. Replaced by `:core:datastore`. |
| `infrastructure/NativeServicesImpl.kt`, `NativeTrayHost.kt`, `TrayIconRenderer.kt`, `TrayPopoverPlacement.kt`, `RuntimeDiagnostics.kt`, `DesktopUiScale.kt`, `macos/MacStatusItemBridge.kt` | `:composeApp` `jvmMain` `infrastructure/`. |
| `infrastructure/data/RoomBoardSource.kt` | Deleted. Replaced by `RoomWorkBoardRepository`, `RoomWorkDetailRepository`, `RuntimeWorkControlRepository` and `RuntimeWorkPolicyRepository` in `:core:data`. |
| `infrastructure/data/BoardPagingSource.kt`, `RepositoryNames.kt`, `RuntimeWorkMapper.kt` | `:core:data` `paging/` and `jvmMain` `runtime/`. |
| `infrastructure/storage/*` | `:core:data` `jvmMain` `signal/` and `store/`. |
| `infrastructure/cli/*` | Deleted by SKILL-423; no desktop path starts the CLI. |
| `presentation/PhasePipelinePresentation.kt`, `TrayHoverPresentation.kt`, `TrayPresentation.kt` | `:feature:detail` `DetailPresentation.kt`; `:feature:work` `TrayHover.kt`, `TrayContract.kt`, `TrayViewModel.kt`. |
| `ui/DesignComponents.kt`, `ui/Theme.kt` | `:core:designsystem` (components, design tokens and `DesignSystemTheme`). The scale-aware `SkillBillTheme` wrapper stays in `:composeApp` `jvmMain` `ui/Theme.kt`. |
| `ui/Pagination.kt`, `ui/ReasonTooltip.kt` | `:core:ui`. |
| `ui/DetailPane.kt`, `ui/RunActionButtons.kt` | `:feature:detail`. |
| `ui/MainWindow.kt`, `ui/TrayUi.kt` | Split into `:feature:work`, `:feature:history` content and the `:composeApp` navigation host and sidebar. |
| `ui/TrayPopover.kt`, `ui/WindowChrome.kt`, `ui/DesktopUiScale.kt` | `:composeApp` `jvmMain` `ui/`. |
| `src/main/native/*`, `resources/icons` | `:composeApp` `src/jvmMain/native/` and `resources/`; fonts to `:core:designsystem` compose resources. |

### Tests

| Old test | Final owner |
| --- | --- |
| `DashboardControllerTest` | Deleted with its subject. Behavior is covered by `WorkViewModelTest`, `TrayViewModelTest`, `DetailViewModelTest` and `HistoryViewModelTest`. |
| `ProjectionTest` | The `project` function is deleted; the runtime's `IdeStatusPolicy.arrange` owns ordering and counts. The frozen-clock case is ported to `WorkFormatTest`. |
| `TransitionTrackerTest` | `:composeApp` `TransitionTrackerTest`. |
| `DesktopArchitectureTest` | `:composeApp` `architecture/`, with `ModuleGraphRulesTest`, `SourceRulesTest`, `NavigationTypingRulesTest` and `DeletionRulesTest`. |
| `DesktopUiScaleTest`, `TrayPopoverPlacementTest` | `:composeApp` `infrastructure/`. |
| `RoomBoardSourceTest` | Split into `ReadOnlyStoreTest`, `BoundedBoardPagesTest`, `ObservationLifecycleTest`, `WorkStackTest` and `ControlOutcomeTest` in `:core:data`. |
| `RoomRuntimeCompatibilityTest`, `PackagedDataSmoke` | Same names in `:core:data` `jvmTest`. |
| `RoomBoardObservationFailureTest`, `DesktopCommitSignalObserverTest` | Deleted as timing-based tests. |
| `RepositoryNamesTest` | `:core:data` `jvmTest` `runtime/RepositoryNamesTest.kt`. |
| `DataStoreSettingsTest` | `:core:datastore` `DataStoreSettingsRepositoryTest`. |
| `TrayHoverPresentationTest` | `:feature:work` `TrayHoverTest`. |
| `MainWindowTest` | Title and duration cases to `:feature:detail` `DetailPresentationTest`; the history scroll case to `:feature:history` `HistoryContentTest`. |
| `ReasonTooltipTest` | Deleted as a timing-based test. |
| `CliMutationTest`, `CliResolverTest`, `ProcessRunnerTest`, `WorkPayloadDecoderTest` | Deleted with the CLI adapters by SKILL-423. |

### Deviations

- 2026-10-15, operator-approved: the `assertNoReachableWorkDataProcessAdapter` reachability rule in `DesktopArchitectureTest`
  was removed, together with the process-only assertions of `walkGovernedEdges` (the forbidden process adapter names, the
  `FunctionBody.launchesCommand` check, the `WorkRunLauncher` start/stop cut-off and the approved display-query helper) and
  the companion roots that only it used. Desktop work reads and controls may reach runtime services that run `git` or other
  processes through the in-process runtime graph. The aliased-import and extension-call fixtures were reworked from
  rejecting a process call into asserting that the walk reaches the aliased or extension target, and an alias to a missing
  declaration must still fail as unresolved. This is a recorded relaxation, not a baseline or an exemption: no allow-list
  entry, skipped root or suppression was added, `approvedProcessFiles` and the run-launch ownership set are unchanged, and
  `DeletionRulesTest` still confines OS process use in the app to the four approved native files, so no other app code can
  run an installed CLI or parse its output.

## Readian parity matrix

| Reference pattern | Skill Bill path or symbol | Real consumer | Lifecycle owner | Evidence | Deviation |
| --- | --- | --- | --- | --- | --- |
| `settings.gradle.kts` composite build, typed accessors, populated modules | `settings.gradle.kts`, `build-logic` | every `build.gradle.kts` | Gradle | `ModuleGraphRulesTest`, `:composeApp:jvmTest` | Independent included build; the runtime is a second composite build. |
| `build-logic/settings.gradle.kts` imports the catalog | `build-logic/settings.gradle.kts`, `Catalog.kt` | convention plugins | Gradle | `DesktopApplicationConventionTest`, `:convention:check` | None. |
| `KmpLibraryConventionPlugin` | `KmpLibraryConventionPlugin` | core libraries | Gradle | `ConventionTargetInventoryTest` | JVM-only target; no Android plugin. |
| `KmpComposeConventionPlugin` | `KmpComposeConventionPlugin` | `:core:designsystem`, `:core:ui` | Gradle | `DesktopApplicationConventionTest` | None. |
| `AndroidKotlinInjectConventionPlugin` | `KotlinInjectConventionPlugin` | `:core:data`, `:core:datastore`, `:composeApp` | Gradle | `KotlinInjectGenerationTest` | Common and JVM generation only. |
| `core/domain/build.gradle.kts` | `:core:domain` | `:core:data`, features | Gradle | `ModuleGraphRulesTest` | No Paging in domain; paging lives in `:core:data`. |
| `feature/detail/build.gradle.kts` | `feature/*/build.gradle.kts` | `:composeApp` | Gradle | `ModuleGraphRulesTest`, `DesktopArchitectureTest` | Features never import data, datastore or another feature. Guard deviation, 2026-10-15, operator-approved: `DesktopArchitectureTest` no longer rejects work-data reachability of runtime services that run `git` or other processes (see Migration inventory, Deviations); the app still never runs an installed CLI. Not a baseline or exemption. |
| `ApplicationComponent` with `@MergeComponent` | `ApplicationComponent` | `AppOwner`, `Main.kt` | `AppScope` | `ApplicationComponentSmokeTest` | Exposes only what the shell reads. |
| `common/di/Scopes.kt` | `core/common/.../di/Scopes.kt` | generated graphs | `AppScope`, `DatabaseSessionScope`, `WindowScope`, `EntryScope` | `ApplicationComponentSmokeTest`, `TwoWindowLifetimeTest` | `DatabaseSessionScope` replaces `UserScope`. |
| `AppFeatureScreenComponent` | `WindowComponent`, `EntryComponent`, `EntryComponents` | `WindowOwner` | window, entry | `ApplicationComponentSmokeTest` | Presentation factories live in `:core:ui`, not domain. |
| `InjectViewModel` | `core/ui/.../navigation/InjectViewModel.kt` | feature routes | entry | `SkillBillNavHostTest` | Typed assisted arguments, not a string map. |
| `ViewModelEntry` | `EntryViewModels`, `EntryArgs`; `ViewModelFactory` for the app-lifetime tray ViewModel | `EntryContent` | entry (tray: `AppScope`) | `NavigationTypingRulesTest` | No `Any` argument bag; the `codegen:viewmodel-inject-processor` module is omitted, since typed constructors replace the string-keyed factory map. |
| `PostDetailContract` | `DetailContract`, `WorkContract`, `HistoryContract`, `SettingsContract`, `TrayContract` | feature ViewModels | entry | `DetailViewModelTest`, `WorkViewModelTest`, `HistoryViewModelTest`, `SettingsViewModelTest`, `TrayViewModelTest` | One typed contract per screen. |
| `PostDetailViewModel` | `DetailViewModel` and siblings | feature content | entry | the ViewModel tests above | Typed Flow combination; no unchecked casts. |
| `ReadianNavHost` | `NavigationHost.kt` (`NavDisplay`, saveable-state and ViewModelStore decorators) | `Main.kt` | window | `SkillBillNavHostTest` | A navigator per separate desktop window. |
| `RouteBackStack` | `RouteBackStack`, `RouteSnapshot`, `WindowNavigator` | `WindowOwner` | window | `RouteRestorationTest`, `WindowNavigatorTest` | Restoration is versioned and validated against the database generation. |
| `DetailEntries` | `EntryContent` in `NavigationHost.kt`, `HostIntentRouter` | tray and notifications | window | `HostIntentRouterTest`, `SkillBillNavHostTest` | Cross-feature routing stays in `:composeApp`. |
| `ReadianTopicsRepository` | `RoomWorkBoardRepository`, `RoomWorkDetailRepository`, `BoardPagingSource` | feature ViewModels | `DatabaseSessionScope` | `BoundedBoardPagesTest`, `WorkStackTest` | Local runtime-backed queries, ten-row pages, no remote mediator. |
| `TopicRoomDao` | `WorkRoomDatabase`, `WorkCandidateQueries` | the repositories | `DatabaseSessionScope` | `RoomRuntimeCompatibilityTest`, `ReadOnlyStoreTest` | The runtime owns the schema and candidate semantics. |
| `ReadianRoomDatabaseProvider` | `DatabaseSession`, `WorkStoreSessionManager` | `StoreSessionCoordinator` | `DatabaseSessionScope` | `ObservationLifecycleTest`, `DatabaseGenerationTransitionTest` | One typed session owner; no destructive deletion. |
| `AppSettingsStore` | `DataStoreSettingsRepository`, `LegacySettingsMigration` | `SettingsViewModel`, coordinators | `AppScope` | `DataStoreSettingsRepositoryTest` | Explicit readiness, corruption and migration outcomes. |
| `JvmComponentFactory` | `ApplicationComponentFactory`, `AppOwner.quit` | `Main.kt` | `AppScope` | `ApplicationComponentSmokeTest` | Host-owned startup and shutdown; no global singleton. |

## Process and native helpers

Guard-approved process use is limited to `NativeServicesImpl`, `NativeTrayHost`, `TrayPopoverPlacement` and
`MacStatusItemBridge` (display queries, the macOS bridge and login-start helpers), plus the Linux `AppRun` launcher, which
starts the packaged app. Harness launch goes through the runtime's admitted `WorkControlRepository.launch` boundary, not a
desktop process. `DeletionRulesTest` rejects any other process launcher, any class implementing two or more of the four work
ports, the retired preference and polling names, and repeating timers in data and feature code.

Desktop work reads and controls (status projection, goal outcome reconciliation, resume, pause, stop and run launches) may
reach runtime services that run `git` or other processes through the in-process runtime graph, per the 2026-10-15 operator
decision. The packaged app itself never runs an installed `skill-bill` CLI, never discovers `PATH` or a login shell, and
never parses command output.

## Runtime touched-file notes

Architecture-guidelines section 5 notes for the `runtime-kotlin` files modified in this branch beyond SKILL-423
(`docs/data-layer-validation.md` holds the SKILL-423 notes). Files: `runtime-domain` `IdeStatusPolicy.kt`; `runtime-ports`
`idestatus/` and `work/` models, `WorkListRepository.kt`, the new `WorkCandidateRepository.kt` and the `UnitOfWorkDefaults`
test fixture; `runtime-engine` `work/` (`IdeStatusService.kt`, `IdeStatusCandidatePaging.kt` and `IdeStatusDetailPages.kt`
moved into `work/pagination/`, `WorkDetailPagePosition.kt`, `WorkExecutionHistory.kt`, `WorkControlModels.kt`) and
`FeatureTaskRuntimeStatusService.kt`; `runtime-infra/sqlite` `worklist/` stores and `StandalonePhaseStatusStore.kt`. The
runtime `check` run in the validate phase is the executed evidence; nothing here claims a result.

| Notes | Finding |
| --- | --- |
| A1, A2 | Dependency direction is unchanged. `WorkCandidateRepository` is a port in `runtime-ports`, `WorkListRepository` extends it, and SQLite implements it; the engine and domain import no storage types. |
| A3, A4 | `RuntimeComponent` is still the composition root and gains no accessor. Paging helpers moved into the engine `work/pagination` package and pagination arguments group into `StandaloneEligiblePage`. |
| A5, A6 | The existing ports and wire vocabulary carry the same values; `StandalonePhaseStatusRepository` takes one page value in place of three parameters. No raw payload map was added. |
| A7, A8 | Error and clock handling are unchanged. The status read keeps its retry and published `incompatible_record` problem; the `fencedStatus` extraction keeps both behaviors. |
| A9, A10 | Goal subtask and dependency stores each own one table read; `WorkGoalSubtaskSelection` replaces boolean and id-list parameters. Tests stay with the code they cover. |
| A11, A12 | New helpers are internal or private. Public models stay in model packages. |
| G1-G7 | No baseline, exemption or suppression grew, and no guard ceiling was raised. |

## Hosts

Delivery proof has two layers: the `check` task and `:composeApp:packagedDataSmoke`,
which runs the distribution's own launcher and bundled JDK against a disposable store and home with an empty `PATH`. It is
outside `check` because it needs a built distribution.

- CI runs `check`, `createDistributable` and `packagedDataSmoke` on `ubuntu-latest`, `macos-latest` and `windows-latest`.
  The local validate-phase host is macOS (darwin). Executed results and the host each smoke ran on are recorded in the
  validate-phase report; this document claims no run.
- Not exercised by any automated task on any host: native tray icon clicks, tray anchoring and hover, OS notification
  display and click-through, login-start registration, KDE and GNOME tray behavior, and installer packages
  (`packageDmg`, `packageMsi`, `packageDeb`, `packageRpm`, `packageAppImage`). Verify those by hand per host.
- The in-process smoke does not pause, stop or launch work. The data layer's control tests own those outcomes.
