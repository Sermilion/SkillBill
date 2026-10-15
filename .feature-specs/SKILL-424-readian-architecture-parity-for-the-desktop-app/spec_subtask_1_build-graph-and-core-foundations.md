# SKILL-424 Subtask 1 - Build graph and core foundations

Parent spec: [.feature-specs/SKILL-424-readian-architecture-parity-for-the-desktop-app/spec.md](./spec.md)
Issue key: SKILL-424

## Scope

Turn `desktop-tray` into a JVM-only Kotlin Multiplatform multi-module build. Add the included `build-logic:convention` build and one desktop version catalog. Register `composeApp` as the desktop application host and move the current root application into it, so the root build no longer compiles application classes. Extract the modules this subtask can populate with real code: `core:common` (dispatchers, clock, scope annotations, coroutine qualifiers), `core:domain` (pure work/settings models, query and control results, repository interfaces), `core:designsystem` (theme, typography, colors, generic controls, resources), and `core:testing` (deterministic clocks, dispatcher fixtures, test data). Add architecture guards for the module graph and source sets.

Parent sections: Required final project structure; Module responsibilities and dependencies; Build conventions; Architecture enforcement and evidence.

Modules owned by later subtasks (`core:data`, `core:datastore`, `core:ui`, `feature:*`) are registered only when they hold real code. Do not add empty placeholder projects.

## Acceptance Criteria

1. `desktop-tray/settings.gradle.kts` includes `build-logic` through `pluginManagement`, and the convention build imports `desktop-tray/gradle/libs.versions.toml`. Project-owned convention plugins with one Skill Bill prefix cover KMP library, Compose KMP, Kotlin-Inject/Anvil, tests/quality, and desktop application packaging. Convention tests exercise them (parent AC-02).
2. Every registered module compiles one `jvm` target only. A clean configuration and build succeed without Android SDK variables or Apple toolchains, and no Android/iOS plugin, target, or KSP configuration is registered. Gradle target-inventory evidence proves it (parent AC-03).
3. `composeApp` owns the desktop executable, native tray sources/resources, and packaging tasks, with verified native loading, package identifiers, icons, and distributions preserved. The root build holds no production classes. Root task aliases keep documented developer commands working (parent AC-01, AC-24 packaging part).
4. `core:common`, `core:domain`, `core:designsystem`, and `core:testing` are populated from the old tree in `commonMain`/`commonTest`, with real production consumers. `core:domain` has no Compose, lifecycle, Room, DataStore, navigation, host, or runtime dependency (parent AC-05).
5. Architecture guards read the actual Gradle module graph and common/JVM source sets. They check acyclic inward edges, domain purity, common-source portability (no `java.time`, JDBC, filesystem, AWT, or runtime imports in common code), and that production code cannot depend on `core:testing`. They fail on empty scan sets, and synthetic rejection fixtures go through the real scanner, including aliased imports and qualified names (parent AC-04, AC-06 portability part, AC-26).
6. Existing desktop behavior and tests keep passing from their new locations, and `./gradlew check` at the desktop root runs every child module's checks.

## Execution rule

This subtask has no manifest dependencies. It runs on the current tree. It creates any missing module, convention, or contract its criteria need, keeps a change that is already present, and applies its rule to whatever anchors exist, whether moved, renamed, or deleted. When it moves or deletes a symbol, it updates every caller wherever that caller lives. It leaves `desktop-tray` configuring, compiling, and passing `./gradlew check`; it never lands a half-migrated tree that only a later subtask can repair.

The parent spec is authoritative for module ownership, allowed dependencies, lifetimes, reference patterns, and non-goals. Read its sections named under Scope before changing code. The Readian reference root is `/Users/braian.gapur/StudioProjects/readian-android`; record its current HEAD and any drift from the recorded `36d1dd9f902a7ab8b7eb2117e898e911fe51120d` before adopting a pattern.

## Non-goals

- Feature ViewModels, Navigation 3, data repositories, DataStore, or SKILL-423 handoff obligations.
- Any parent non-goal.

## Validation Strategy

Run `./gradlew clean check --no-build-cache` in `desktop-tray`, the convention-build tests, and the guard rejection fixtures. Record the resolved module/target graph. Confirm root `check` executes child tests.

## Next Path

Later subtasks add data, handoff, feature, navigation, and delivery work on the resulting tree.

## Implementation Details

All facts below come from the preplan digest. Items marked **Assumption** were not in the digest. Implement confirms each one against the tree before relying on it and records the result in its summary.

### Settled decisions

1. **Portable time.** Common code uses the stdlib `kotlin.time.Instant`, `kotlin.time.Clock` and `kotlin.time.Duration`. Do not add `kotlinx-datetime` and do not write a homemade epoch type. JVM code converts at its boundary with `toKotlinInstant()`/`toJavaInstant()` and `toKotlinDuration()`/`toJavaDuration()`. Production code that calls `Instant.now()` or `Clock.systemUTC()` takes an injected `kotlin.time.Clock` instead; `Main.kt` passes `Clock.System`. **Assumption:** these types are stable in Kotlin 2.4.21. If the compiler still demands `@ExperimentalTime`, the KMP convention adds `optIn("kotlin.time.ExperimentalTime")` to every source set. Common code never falls back to `java.time`.
2. **KSP scope.** Annotation processing runs only through `kspJvm`, plus `kspJvmTest` where a test source set needs generation. Do not use the bare `ksp(...)` configuration or `kspCommonMainMetadata`. With one target, this is the only processing pass, so no declaration is processed twice.
3. **No `codegen:viewmodel-inject-processor`.** Nothing in this subtask needs it.
4. **Kotlin-Inject/Anvil coordinates.**
   - Kotlin-Inject: `me.tatarka.inject:kotlin-inject-runtime-kmp` and `me.tatarka.inject:kotlin-inject-compiler-ksp`.
   - Anvil: `software.amazon.lastmile.kotlin.inject.anvil:runtime`, plus `:runtime-optional` only if its annotations are used, and `:compiler`.
   - Implement pins the newest versions whose published compatibility covers KSP 2.3.12 and Kotlin 2.4.21.
   - If no compatible pair exists, the subtask blocks and names the pair. Do not drop or stub the convention to get past it.
5. **Scope annotations and coroutine qualifiers are deferred.** Their first consumers are the generated components in subtask 2, so they are not added here. `core:common` holds only symbols that have a consumer today. This narrows the Scope paragraph's list under AC 4's "real production consumers" rule. Record the deviation in the implementation summary.
6. **Interim app placement.** Whatever does not move into a core module moves unchanged into `composeApp/src/jvmMain/kotlin`, keeping its current `dev.skillbill.tray.*` package:
   - `DashboardController`, `RoomBoardSource`, `Preferences`, the storage and data infrastructure, `presentation/*`, the `ui/*` files not moved to the design system, and the host adapters.

   This is the working app moved to its new home, not a placeholder. Subtasks 2 to 5 split it later.
7. **Convention plugin IDs.** All use one `skillbill.` prefix:

   | Plugin ID | What it does |
   | --- | --- |
   | `skillbill.kmp.library` | Applies `skillbill.quality`. |
   | `skillbill.kmp.compose` | Applies `skillbill.kmp.library`. |
   | `skillbill.kotlin.inject` | Kotlin-Inject/Anvil runtimes and compilers. |
   | `skillbill.quality` | Test wiring and the architecture manifest. |
   | `skillbill.desktop.application` | Applies `skillbill.kmp.compose`, and owns Compose Desktop packaging and the native tray tasks. |

   - No Room convention in this subtask. `composeApp` applies `com.google.devtools.ksp` by ID and adds `kspJvm(libs.room.compiler)` directly. Subtask 2 owns Room and its convention.
8. **Module graph evidence comes from Gradle, not from build-file text.**
   - The `skillbill.quality` convention gives every project a `writeArchitectureManifest` task. The task writes a JSON manifest containing:
     - the project path;
     - the applied plugin IDs;
     - the Kotlin target names;
     - the names of every `ksp*` configuration;
     - the source directories of each Kotlin source set;
     - the declared project dependencies for each configuration;
     - the resolved components of `jvmCompileClasspath` and `jvmRuntimeClasspath`, and of their test equivalents.
   - Use configuration-cache-safe providers: `incoming.resolutionResult.rootComponent` and lazily computed `MapProperty` values.
   - Each project exposes the manifest through a consumable `architectureManifestElements` configuration.
   - The guard host resolves the manifests of every `rootProject.subprojects` entry through a resolvable configuration. Its test task receives the manifest file list through a `CommandLineArgumentProvider` system property.
9. **Packages.** Core modules use `dev.skillbill.tray.core.<module>`:
   - `core.common`
   - `core.domain.model` and `core.domain.repository`
   - `core.designsystem`
   - `core.testing`

   Every import of a moved symbol is updated wherever it lives.
10. **Guard host.** The rewritten guard lives in `composeApp/src/jvmTest/kotlin/dev/skillbill/tray/architecture/`. It replaces the root `DesktopArchitectureTest`, and its PSI dependency comes from the catalog.

### Ordered tasks

**T1. Catalog (AC 1, AC 2).** Edit `desktop-tray/gradle/libs.versions.toml`.

- **Plugin artifacts as libraries**, for the build-logic classpath:
  - `kotlin-gradle-plugin`
  - Compose Multiplatform gradle plugin
  - Compose compiler gradle plugin
  - KSP gradle plugin
  - `kotlin-serialization` gradle plugin
- **New libraries:**
  - Kotlin-Inject runtime and compiler, Anvil runtime and compiler (see decision 4).
  - JetBrains multiplatform lifecycle-viewmodel/compose, Navigation 3 UI, lifecycle-viewmodel-navigation3, and saved state. Pick versions that match Compose 1.12.1.
  - `kotlin-compiler-embeddable`, with `version.ref = "kotlin"`. This replaces the hardcoded `2.4.21` string.
  - `kotlin-test`, `kotlinx-coroutines-test`, and Gradle TestKit support where it is needed.
  - Explicit Compose Multiplatform coordinates for runtime, foundation, ui, material3 and components-resources, plus the ui-test artifact the existing UI tests use.
- Keep every existing pin. Do not add entries that this subtask's builds and tests never resolve. The lifecycle, Navigation 3 and saved-state entries are resolved by the convention test T10b.

**T2. `build-logic` included build (AC 1, AC 2, AC 5).**

- **`desktop-tray/build-logic/settings.gradle.kts`:**
  - `rootProject.name = "build-logic"`.
  - Repositories: `gradlePluginPortal()`, `google()`, `mavenCentral()`.
  - `versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }`.
  - `include(":convention")`.
  - No reference to `runtime-kotlin`, to Readian, or to any absolute path.
- **`build-logic/convention/build.gradle.kts`:**
  - Applies `kotlin-dsl`.
  - Declares the T1 plugin artifacts as `implementation`, so the TestKit `withPluginClasspath()` can see them.
  - Adds `gradleTestKit()`, JUnit Jupiter and `kotlin-test` for its tests.
  - Registers the five plugins under `gradlePlugin { plugins { … } }`.
  - Its test task passes the absolute path of the desktop catalog as a system property.
- **`src/main/kotlin` content:**
  - **`skillbill.kmp.library`:**
    - Applies `org.jetbrains.kotlin.multiplatform` and registers exactly one `jvm()` target.
    - Sets the JVM toolchain to the spec the root build uses today. That is language 21, with the vendor the current root build pins (Adoptium per the 2026-10 tray packaging fix). Copy it, do not reinterpret it.
    - Adds `kotlin("test")` to `commonTest` and applies `skillbill.quality`.
    - Never calls `androidTarget`, `ios*`, `js` or `wasm`.
  - **`skillbill.kmp.compose`:**
    - Adds `org.jetbrains.compose`, `org.jetbrains.kotlin.plugin.compose`, the Compose runtime in `commonMain`, and the ui-test artifact in `jvmTest`.
    - Sets the Compose resources `packageOfResClass` per module from the project path.
    - Adds no `dependsOn` by task-name substring. Generated resources reach compilation through the plugin's own source-set wiring.
  - **`skillbill.kotlin.inject`:**
    - Applies KSP.
    - Adds the Kotlin-Inject and Anvil runtimes to `commonMain`.
    - Adds both compilers to `kspJvm` only.
  - **`skillbill.quality`:**
    - `tasks.withType<Test>().configureEach { useJUnitPlatform() }`.
    - The architecture manifest task and the `architectureManifestElements` configuration from decision 8.
  - **`skillbill.desktop.application`:** moves from today's root `build.gradle.kts` without changing behavior:
    - The `desktopJavaHome` resolution, exposed as an extension property so the native tasks can reuse it.
    - The exclusion of `dev.nucleusframework:nucleus.decorated-window-tao`.
    - Compose Desktop `nativeDistributions`:
      - formats Dmg, Msi, Deb and Rpm;
      - `packageName "Skill Bill"` and macOS `bundleID dev.skillbill.tray`;
      - the version from `RELEASE_VERSION`, with the macOS rule against a leading `0.`;
      - JDK modules `java.sql`, `java.net.http`, `java.management` and `jdk.unsupported`;
      - `javaHome`.
    - The native tasks:
      - `compileAppImageLauncher`.
      - `compileLinuxTrayNative`, writing to `layout.buildDirectory/generated/linuxTray/resources`.
      - `compileMacTrayNative`, building `libskillbilltray.dylib` staged under `macos/`.
    - Native sources are read from the applying project's `src/jvmMain/native/{linux,macos}`.
    - Native outputs reach the jar through `kotlin.sourceSets.jvmMain.resources.srcDir(<task output provider>)`, not through `processResources.dependsOn`.
    - `stageAppImage` is a Sync task fed by `createDistributable` output under the project build directory. Its icon is `design/App/docs/assets/skill-bill-app-icon.svg`, resolved from the repository root through `rootProject.layout.projectDirectory`, not relative to the module.
    - `packageAppImage` keeps the pinned `appimagetool` 1.9.1 at the root `desktop-tray/build/tools/...` location that `APPIMAGE.md` already documents (`rootProject.layout.buildDirectory`). Configuration must never download or execute it.
    - The OS `onlyIf` guards stay as they are today. The macOS `clang`/AppKit compile is host packaging, not an Apple mobile toolchain.

**T3. Root settings and build (AC 1 to AC 3, AC 6).**

- **`desktop-tray/settings.gradle.kts`:**
  - `pluginManagement { includeBuild("build-logic"); repositories { gradlePluginPortal(); google(); mavenCentral() } }`.
  - Keep the foojay 1.0.0 plugin, `FAIL_ON_PROJECT_REPOS`, the repositories, and `includeBuild("../runtime-kotlin")` with all five `dev.skillbill:*` substitutions.
  - Add `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`.
  - `include(":composeApp", ":core:common", ":core:domain", ":core:designsystem", ":core:testing")`.
  - Register no `core:data`, `core:datastore`, `core:ui` or `feature:*` here.
- **`desktop-tray/build.gradle.kts`:**
  - Drop the application plugins, the dependencies, the compose block and the native tasks.
  - Apply only `base`.
  - Plugins already on the build-logic classpath are applied by ID with no version (`id("com.google.devtools.ksp")`, `id("org.jetbrains.kotlin.plugin.serialization")`). This avoids the "already on the classpath with unknown version" failure.
  - `check` depends on:
    - every subproject's `check` by path;
    - `gradle.includedBuild("build-logic").task(":convention:check")`.

    It never depends on `runtime-kotlin` checks.
  - **Root task aliases.** Implement collects every `./gradlew <task>` invocation in:
    - `ARCHITECTURE.md`, `APPIMAGE.md` and `docs/data-layer-validation.md`;
    - `scripts/packaged-data-smoke.py`;
    - `.github/workflows/desktop-tray-ci.yml` and `desktop-tray-snapshot-cleanup.yml`.

    For each task name that now lives under `:composeApp`, it registers a root alias that depends on `:composeApp:<task>`. Expected names include `run`, `createDistributable`, `packageDistributionForCurrentOS`, `packageDmg`/`Msi`/`Deb`/`Rpm`, `stageAppImage`, `packageAppImage`, and a `test` alias over every child `jvmTest`.
  - **Callers that pass `--tests` or other Test-type options cannot go through an alias.** Edit those callers to `:composeApp:jvmTest` in this subtask, in CI and scripts, because of the execution rule. The broader prose rewrite of those documents stays with subtask 6.

**T4. Move the app into `composeApp` (AC 3, AC 6).**

- **Sources and resources:**
  - `git mv` `src/main/kotlin` to `composeApp/src/jvmMain/kotlin`.
  - `src/main/resources/icons` to `composeApp/src/jvmMain/resources/icons`.
  - `src/main/native` to `composeApp/src/jvmMain/native`.
  - `src/test/kotlin` to `composeApp/src/jvmTest/kotlin`.
  - The fonts go to T7, not here.
- **`composeApp/build.gradle.kts`:**
  - Applies `skillbill.desktop.application`, KSP by ID, and the serialization plugin by ID.
  - Sets `mainClass = "dev.skillbill.tray.composition.MainKt"`, unchanged.
  - `jvmMain` dependencies are today's production list:
    - `compose.desktop.currentOs`, material3, icons-extended;
    - serialization-json, coroutines core and swing;
    - compose-native-tray, nucleus notification/application/autolaunch;
    - room-runtime, sqlite-api, sqlite-jdbc, datastore-core, paging-common;
    - `dev.skillbill:runtime-core` and `runtime-contracts`;
    - the core projects through typed accessors.
  - `kspJvm(room-compiler)`.
  - `jvmTest` dependencies:
    - `runtime-infra-host`, `runtime-infra-contracts`, `testFixtures(runtime-infra-sqlite)`;
    - `kotlin-compiler-embeddable` from the catalog;
    - kotlin-test and junit-jupiter;
    - `projects.core.testing`.
  - The `jvmTest` task:
    - keeps `maxHeapSize = "2g"`;
    - declares the guard's runtime inputs (`RuntimeModuleCatalog.kt` and the runtime `src/main/kotlin` roots) relative to `rootDir.parentFile/runtime-kotlin`, not to the module directory;
    - passes `desktopRoot` and `runtimeRoot` as system properties.
  - Keep today's gating of `PackagedDataSmoke`.
- **Cleanup:** delete the now-empty root `src/`.

**T5. `core:common` (AC 4).**

- `core/common/build.gradle.kts` applies `skillbill.kmp.library`.
- `commonMain` holds `dev.skillbill.tray.core.common.DispatcherProvider`, an immutable holder exposing only the dispatcher roles current production code uses (for example `io` and `default`).
- It never references `Dispatchers.IO`. The JVM construction site in `Main.kt` passes it.
- Replace the direct `Dispatchers.*` uses in the composeApp classes that launch background work (such as `DashboardController`, `RoomBoardSource` and `DesktopCommitSignalObserver`) with a constructor-injected `DispatcherProvider`. No default values.
- **Assumption:** at least one such direct use exists. If none does, `core:common` instead holds the injected clock seam shared by domain and the app, and the deviation is recorded. It must not be registered empty.

**T6. `core:domain` (AC 4, AC 5).**

- **Build:** `core/domain/build.gradle.kts` applies `skillbill.kmp.library`, plus serialization only if the moved models are `@Serializable`. `commonMain` depends on:
  - `api(kotlinx-coroutines-core)`, because the repository interfaces expose `Flow`;
  - `implementation(projects.core.common)` only if something uses it.
- **Move `domain/WorkModels.kt` and `domain/DesktopUiScaleConfiguration.kt` to `core.domain.model`.**
- **Move from `application/Ports.kt` to `core.domain.repository`** (or to `model` for value types):
  - `Outcome`, `Problem`, `ProblemCode`;
  - `WorkBoardRepository`, `WorkDetailRepository`, `WorkControlRepository`, `WorkStoreSession`, `SettingsStore`;
  - the value types the digest lists: `AppSettings`, `Retention`, `HistoryFilter`, `WorkSection`, `DetailList`, `Board`, `Detail`, `WorkItem`, `WorkIdentity`, and `BoardRetention`.
- **Rule:** any interface whose signature exposes a JVM, runtime or Paging-JVM type stays in composeApp's `application/Ports.kt`, and the decision is recorded. Example: `WorkStoreSession.selectDatabase(path)` if `path` is a `java.nio.file.Path`.
- **What stays in composeApp:** `NativeServices`, `DashboardState`, `DashboardModel`, `ListLoadState` and `DisplayDurations` are presentation or host types and stay in composeApp.
- **Time types:** replace `java.time.Instant`/`Duration` in the moved types with the `kotlin.time` types. Update every caller, adding conversion at the JVM edges:
  - `RuntimeWorkMapper`, `RoomBoardSource`, `WorkCandidateQueries`;
  - `DashboardController` (`project(board, now)`, `displayedDuration`, `TransitionTracker`);
  - `presentation/*`, `ui/*`;
  - every test.
- **Preserve precision:** keep the precision the old `java.time` values carried, including any millisecond truncation already in place, so existing assertions stay unchanged.
- **Forbidden in domain:** Compose, lifecycle, Room, DataStore, navigation, host and runtime imports and dependencies.

**T7. `core:designsystem` (AC 4).**

- **Build:** `core/designsystem/build.gradle.kts` applies `skillbill.kmp.compose`. It declares `api` dependencies on the Compose runtime, foundation, ui and material3, because public composables expose `Modifier`, `Color` and `TextStyle`. Add `implementation(components-resources)`.
- **Move to `commonMain` under `core.designsystem`:** `ui/Theme.kt` and the generic, domain-free parts of `ui/DesignComponents.kt`. Anything that imports work models, app types or JVM-only Compose APIs stays in composeApp's `ui/`.
- **Fonts:**
  - Move `Inter.ttf`, `JetBrainsMono.ttf` and `SpaceGrotesk.ttf` to `core/designsystem/src/commonMain/composeResources/font/`.
  - Move their OFL licence files to `composeResources/files/licenses/`, so they still ship in the distribution jar.
  - Rewrite font loading to `Font(Res.font.…)` behind `@Composable` font-family accessors.
- **Callers:** composeApp's remaining UI depends on `projects.core.designsystem` and replaces the classpath font loading.
- **Assumption:** Theme currently loads fonts through JVM classpath `Font(resource = …)`. If it loads them in some other way, implement adapts while keeping the same three families.

**T8. `core:testing` (AC 4, AC 5).**

- **Build:** `core/testing/build.gradle.kts` applies `skillbill.kmp.library`. `commonMain` declares `api(projects.core.domain)`, `api(projects.core.common)` and `api(kotlinx-coroutines-test)`.
- **What to extract:** only fixtures that the moved tests already duplicate or that must change for `kotlin.time`:
  - a deterministic `kotlin.time.Clock` (fixed or advanceable);
  - a test `DispatcherProvider` over a `TestDispatcher`;
  - domain test-data builders for `WorkItem`, `Board` and `WorkIdentity` that `DashboardControllerTest`, `RoomBoardSourceTest`, `ProjectionTest` or `TransitionTrackerTest` build by hand today.
- **Consumers:** composeApp's `jvmTest` consumes these. No production configuration depends on `core:testing`.

**T9. Architecture guards (AC 2, AC 4, AC 5).** These live in `composeApp/src/jvmTest/kotlin/dev/skillbill/tray/architecture/`.

- **Scanner.** One PSI scanner reuses the existing `KotlinCoreEnvironment` setup. It reports:
  - each import's `importedFqName`, so aliases are checked by their real target and star imports by their package;
  - every dot-qualified type reference and expression whose text starts with a known package root.
- **Manifest graph rules:**
  - Every included project has a manifest. The set of manifests must be non-empty, and each manifest must list at least one non-empty production Kotlin source directory.
  - The Kotlin targets of every module are `{jvm}`. The `metadata` target that KMP always creates is allowed.
  - No `com.android.*` or `org.jetbrains.kotlin.android` plugin is applied.
  - No `ksp*` configuration names Android or iOS.
  - The declared project edges match the parent spec's allowed-dependency table, encoded once for every module named there. Unknown modules fail.
  - The declared graph has no cycles.
  - Feature modules do not depend on one another.
  - No production configuration declares `:core:testing`, and no production `jvmRuntimeClasspath` resolves it. The resolved check catches leaks through `api`.
  - `core:domain`'s resolved `jvmRuntimeClasspath` contains no artifact under:
    - `org.jetbrains.compose` or `androidx.compose`;
    - lifecycle, Room, DataStore or navigation groups;
    - `dev.skillbill` runtime;
    - the tray or nucleus groups.
  - Every core module except `core:testing` has at least one production consumer. `core:testing` has at least one test consumer.
  - The root `desktop-tray/src` does not exist.
- **Source rules over the source directories in the manifests:**
  - `commonMain` and `commonTest` must not reference `java.`, `javax.`, `jdk.`, `sun.`, `com.sun.`, JDBC/sqlite-jdbc packages, or runtime `skillbill.` packages. This covers `java.time`, JDBC, filesystem, AWT and runtime code.
  - `core:domain` must not reference Compose, lifecycle, Room, DataStore, navigation or host packages.
  - Production sets outside `core:testing` must not reference `dev.skillbill.tray.core.testing`.
  - Every scan root must be non-empty.
- **Rewrite the old `DesktopArchitectureTest` with no rule weakened.**
  - Port these:
    - `forbiddenStorageImports`, `forbiddenApplicationImports`, `forbiddenPresentationTokens`, `forbiddenCoreTokens`;
    - `forbiddenWorkDataProcessAdapters`, `approvedNativeHostProcessFiles`;
    - the fixed five-file `WorkRunLauncher` consumer set;
    - the process-adapter call graph from the `/composition/`, `/application/` and `/infrastructure/data/` roots;
    - the pinned `IdeStatusService.board/projectBoardSelection/detail` and `WorkControlService.request` edges;
    - the scan of `RuntimeModuleCatalog.kt` and the runtime roots.
  - Scan roots now come from the manifests and the `desktopRoot`/`runtimeRoot` properties instead of hardcoded `src/main/kotlin/dev/skillbill/tray` strings.
  - Path filters that named `/domain/` or `application/Ports` follow the moved files into `core/domain`.
  - Keep every non-empty-scan assertion.

**T10. Convention tests (AC 1, AC 2).** These live in `build-logic/convention/src/test/kotlin/`. They use TestKit with `withPluginClasspath()`.

- **Fixture setup:** the fixture settings import the real desktop catalog through the system property from T2, apply the foojay resolver, and use the same repositories as the real build.
- **Tests:** see Test obligations a, b and c. Each fixture build runs once per test.

**T11. Caller sweep (AC 3, AC 6).**

- Search the whole repository for:
  - `desktop-tray/src/main` and `desktop-tray/src/test` paths;
  - old task paths;
  - the hardcoded `kotlin-compiler-embeddable:2.4.21`;
  - imports of moved symbols.
- Fix each caller:
  - CI path filters and task invocations;
  - `scripts/packaged-data-smoke.py`;
  - test inputs.
- Do not rewrite architecture prose here. Subtask 6 owns documentation and the parity and migration inventories.

### Test obligations

Each new test is tied to a realistic bug. Moved tests stay as they are; only their imports and time types change. Trivial glue such as `DispatcherProvider`, the root aliases and the time conversions gets no dedicated test. The existing controller, projection and Room tests already cover the time migration.

- **(a) `ConventionTargetInventoryTest`** (AC 1, AC 2).
  - The fixture has `:lib`, which applies `skillbill.kmp.compose` and `skillbill.kotlin.inject`, and `:app`, which applies `skillbill.desktop.application`.
  - It runs `writeArchitectureManifest` for both and asserts:
    - the targets are `{jvm}` (plus metadata);
    - no Android plugin is applied;
    - no Android or iOS `ksp*` configuration exists.
  - Bug caught: a convention that silently adds a mobile target, or a KSP setup that processes the same code twice.
- **(b) `KotlinInjectGenerationTest`** (AC 1, AC 2).
  - The fixture compiles `:lib` from a clean state. It has a `@Component` and an `@Inject` class, and `commonMain` dependencies on the catalog's lifecycle-viewmodel, Navigation 3 and saved-state entries.
  - It asserts that the compile succeeds and that the generated component exists exactly once under `build/generated/ksp/jvm`.
  - Bug caught: an incompatible Kotlin-Inject/Anvil/KSP pin, a mis-registered processor, double generation, or a catalog pin with no JVM variant.
- **(c) `DesktopApplicationConventionTest`** (AC 3).
  - It configures `:app` without an `appimagetool` present and runs `jvmProcessResources --dry-run`.
  - It asserts that configuration succeeds and that the native task for the host OS is in the graph: `compileMacTrayNative` on macOS, `compileLinuxTrayNative` on Linux, none on Windows.
  - Bug caught: the native library is missing from the packaged jar after the move, or configuration needs the tool.
- **(d) `ModuleGraphRulesTest`** (AC 2, AC 5). One table-driven test runs the real manifest rules over synthetic manifests, with one case per rule:
  - an outward edge;
  - a cycle;
  - a feature depending on a feature;
  - a production configuration on `:core:testing`;
  - `:core:testing` leaked through `api` into a resolved runtime classpath;
  - a Compose artifact on domain's classpath;
  - a non-JVM target;
  - an Android plugin;
  - an unknown module;
  - an empty manifest set;
  - an empty module source set;
  - a module with no consumer.

  A conforming graph must be accepted. Bug caught: a rule that never fires, or one that rejects everything.
- **(e) `SourceRulesTest`** (AC 5). Synthetic sources go through the real scanner:
  - `import java.time.Instant as Moment` in common code;
  - a qualified type `java.sql.Connection` with no import;
  - a qualified expression `java.io.File("x")`;
  - the star import `import java.awt.*`;
  - a `skillbill.engine` import in common code;
  - `androidx.compose.runtime.State` in domain;
  - a production import of `dev.skillbill.tray.core.testing`;
  - an empty scan root.

  A clean common file must be accepted. Bug caught: aliases or qualified names getting past an import-only check.
- **(f) `DesktopArchitectureTest`, rewritten** (AC 5, AC 6). It runs every rule over the real manifests and sources and asserts non-empty inputs. It also keeps the existing runtime and process assertions.

### Constraints

- Use `implementation` unless a public signature exposes the type. The `api` entries named above are the only ones, each with its exposed type: Flow, Compose UI types, and test fixtures.
- Do not add `codegen` modules, empty modules, placeholder scope annotations, a Room convention, or `core:data`, `core:datastore`, `core:ui` or `feature:*` registrations.
- Do not commit generated output.
- No dependency on Readian or on absolute paths in any build file.
- Do not change runtime-kotlin code or guards.
- Do not grow any baseline, exemption or suppression.
- Preserve the native loading paths, package identifiers, icons, formats and the `mainClass`.
- **Readian reference.** Implement reads `/Users/braian.gapur/StudioProjects/readian-android` if it is accessible. It records the current HEAD and any drift from `36d1dd9f…` in its summary. If the tree is inaccessible, it records that and adapts the patterns from the parent spec's table.
- **Phase boundaries.** This phase and implement do not run validation. The validate phase runs `./gradlew clean check --no-build-cache` with `ANDROID_HOME` and `ANDROID_SDK_ROOT` unset. It also records the module and target graph from the manifests and confirms that root `check` ran tests in the child modules and in `:convention`.

## Spec Path

.feature-specs/SKILL-424-readian-architecture-parity-for-the-desktop-app/spec_subtask_1_build-graph-and-core-foundations.md
