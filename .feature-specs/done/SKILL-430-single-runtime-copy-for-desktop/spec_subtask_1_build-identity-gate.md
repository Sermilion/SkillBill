# SKILL-430 subtask 1 - Build identity gate

## Parent

`spec.md`

Covers parent acceptance criteria 1, 2, 3, 4 and 7. Parent criterion 12 (the 2026-10-19 regression) is carried as a test obligation here, because its production behavior is criterion 2 below. This subtask ships alone with the desktop still bundling its runtime, as the parent constraint requires.

## Scope

- A shared Gradle source of truth for build identity, used by both `runtime-kotlin` and `desktop-tray`. Decision: a new included plugin build at the repository root (`../../../build-identity`, plugin id `skillbill.build-identity`) that both `runtime-kotlin/settings.gradle.kts` and `desktop-tray/settings.gradle.kts` include through `pluginManagement`. It derives the commit from `git rev-parse HEAD`, the dirty flag from `git status --porcelain` (untracked files count as dirty), the release tag from `RELEASE_VERSION`, and the install run id from `SKILL_BILL_INSTALL_RUN_ID`. It reads git and env only through configuration-cache-safe providers and declares every value as a task input so the build cache cannot reuse a stale identity.
- Record format. Decision: `build-identity.properties` with keys `commit`, `dirty`, `release_tag`, `install_run_id`. The key names are declared once on the Gradle side and once on the desktop reader side (architecture guideline A6).
- Runtime side: `runtime-cli` and `runtime-mcp` ship the record at their distribution root through the same `distributions.main.contents` hook that adds `LICENSE`, so `installDist` and `runtimeZip` both carry it. The installed record lives at `<runtime root>/runtime-cli/build-identity.properties`.
- Desktop side: the record is a packaged app resource (Compose `appResourcesRootDir`) that both the app and `../../../install.sh` can read without unpacking a jar.
- Runtime install root resolution, matching `../../../install.sh`: `SKILL_BILL_RUNTIME_DIR` when set, else `<user home>/.skill-bill/runtime`. `SKILL_BILL_STATE_DIR` does not affect it. The desktop reads env and home through the injected `HostEnvironment` (guideline A8).
- Desktop gate: a commonMain port in `core/data` with a jvmMain reader. `WorkStoreSessionManager.selectDatabase` consults it before `SessionFactory.create`, so no `DatabaseSession`, `RuntimeComponent` or work control exists on refusal. Refresh/retry re-reads the installed record and proceeds to select the database once it matches.
- Dedicated failure state: a new `ProblemCode` (`runtime_build_mismatch`) with a reason (mismatch, missing, unreadable, not installed) and a carrier for the installed identity, the desktop identity and the reinstall command. Domain types stay free of `java.*`. `StoreSessionCoordinator` must surface this state instead of its generic `DATABASE_ACCESS` problem.
- Keep the comparison and record parsing in a class that needs only the JDK and kotlin-stdlib, with no runtime or DI imports, so subtask 2's bootstrap can reuse it before the runtime classpath exists.
- `../../../install.sh` matched pair: one run id per run, exported into both Gradle invocations; the source path builds the runtime and the desktop from the same checkout; the release path fetches both assets for one tag. Each path stages both, compares the two records, and only then activates. Decision (preplan U5): a desktop that cannot be staged fails the whole install before the runtime is activated, since a half-updated pair is the bug this work fixes. `install.sh` usage text may be updated here; subtask 2 owns the final wording.
- `release.yml` already builds both assets in one job with one `RELEASE_VERSION`; once both builds apply the shared plugin the records match by construction. Adding a release-time comparison step is in scope.
- Shared library versions. Decision (preplan U1): raise the runtime catalog to the desktop's stable versions (kotlin 2.4.21 with a matching KSP, kotlinx-serialization 1.9.0, kotlinx-coroutines 1.10.2 where the runtime resolves it). Add a desktop build check that resolves `dev.skillbill:runtime-cli` on its own (adding a composite substitution for `:runtime-cli`), compares each shared `group:module` version with the desktop's `jvmRuntimeClasspath`, and fails on any difference. Wire it into `check` and before `createDistributable`.

## Acceptance Criteria

1. `runtime-cli` and `runtime-mcp` distributions (both `installDist` and `runtimeZip`) contain a `build-identity.properties` record holding the source commit SHA, a dirty flag for source builds with uncommitted changes, and the release tag for release builds, and the packaged desktop carries the same record for its own build as an app resource. Both records come from the one shared `skillbill.build-identity` Gradle plugin, with no hand-maintained identity values in either build.
2. `WorkStoreSessionManager` reads the installed runtime's record (`SKILL_BILL_RUNTIME_DIR`, else `~/.skill-bill/runtime`, then `runtime-cli/build-identity.properties`) and compares it with the desktop's own record before calling `SessionFactory.create`. Equal identities proceed. A missing, unreadable or different record publishes a dedicated `runtime_build_mismatch` problem, not `incompatible_database`, that names both identities and the reinstall command, and on that path no database connection opens and no process starts. Refresh re-reads the installed record.
3. Identity equality treats a dirty record as different from every other record, including another dirty record of the same commit, unless both carry the same non-empty `install_run_id`. Clean records are equal when commit and release tag match.
4. `../../../install.sh` on the source path builds the runtime and the desktop from the same checkout in one run with one shared `SKILL_BILL_INSTALL_RUN_ID`, and on the release path installs the runtime image and desktop build for the same release tag. Both paths compare the two staged records before activating either one, and on a mismatch, or when the desktop cannot be staged, they print both identities and exit non-zero without replacing the active runtime or desktop app.
5. The runtime and desktop version catalogs resolve the same version of every shared third-party library (at least kotlin-stdlib, kotlinx-coroutines, kotlinx-serialization and the SQLite drivers), and a desktop build check wired into `check` and `createDistributable` fails when the desktop's resolved version of a shared library differs from the runtime's own resolution.

## Non-Goals

- Removing the bundled runtime, the bootstrap entry point, `compileOnly` wiring, or CI runtime provisioning. Subtask 2 owns them.
- Surfacing the identity through `RuntimeProvenanceContract` or any runtime CLI command. No criterion needs it (guideline A5).
- Changing `DetachedWorkRunLauncher`, adding migrations, or repairing databases a mismatched pair already migrated.
- Supporting a desktop on a compatible runtime from a different build.

## Dependency Notes

- First subtask; no dependencies. Subtask 2 reuses the JDK-only identity comparison, the record format and the version-alignment check.
- Assumptions for implement to confirm: a KSP release exists for kotlin 2.4.21 (if raising the runtime catalog is not possible, block rather than downgrade the desktop, since Compose 1.12.1 needs 2.4.21); the actual `../../../install.sh` flag names for source and release installs, used in the reinstall command text (preferred: the source flag when either record has no release tag, the release flag with the installed tag otherwise); whether the runtime resolves kotlinx-coroutines at all (the build check decides which libraries are shared); that a root-level included plugin build needs no `RuntimeModuleCatalog` entry because it is not a runtime module (G4).
- Architecture guidelines for `runtime-kotlin` changes: A1 (no identity reading in `runtime-cli`), A5, A6, A8, G6, G7. No guard baseline or allow-list grows.
- The `.feature-specs` directory is untracked, so local builds come out dirty. That is intended; `../../../install.sh` runs still match through the shared run id.

## Test Obligations

- Regression for parent criterion 12, in `core/data` jvmTest: installed record A, desktop record B, a database at an older `user_version` that `upgradeExistingStore` would migrate. Assert the `runtime_build_mismatch` problem is published, the session factory is never called, and `PRAGMA user_version` is unchanged. Then switch the installed record to match and assert refresh selects the database. Catches a gate placed after `factory.create` or a retry that caches the first read.
- Identity equality rule: dirty records of the same commit with different or missing run ids are unequal, the same non-empty run id is equal, clean records compare by commit and tag. Catches a comparator that ignores the dirty flag.
- Installer repoTest: staged runtime and desktop records that differ make `../../../install.sh` exit non-zero and leave the live runtime and desktop directories untouched. Catches activation before comparison.
- No separate test for the Gradle record task or the version check beyond what validate's builds exercise; the check itself is the guard.

## Implementation Details

Tasks run in this order. Each names the criteria it serves, the paths or symbols it touches, its tests, and its constraints. Items marked "Assumption" come from gaps in the preplan digest; implement confirms them in the files and adjusts the step if the code says otherwise.

### Task 1. Shared `skillbill.build-identity` plugin build (criteria 1, 3)

- Add a root included build `../../../build-identity` with `settings.gradle.kts`, `build.gradle.kts` (`kotlin-dsl`, `gradlePlugin { plugins { create("buildIdentity") { id = "skillbill.build-identity" } } }`) and sources under `build-identity/src/main/kotlin/dev/skillbill/buildidentity/`.
- `BuildIdentityKeys` declares the four key literals once for the Gradle side: `commit`, `dirty`, `release_tag`, `install_run_id`.
- `BuildIdentityPlugin` reads values through providers only, following `SkillBillVersionConventionPlugin`: `providers.exec { commandLine("git", "rev-parse", "HEAD"); isIgnoreExitValue = true }`, `providers.exec { commandLine("git", "status", "--porcelain") }` (any output, untracked included, means dirty), `providers.environmentVariable("RELEASE_VERSION")` and `providers.environmentVariable("SKILL_BILL_INSTALL_RUN_ID")`. Run both git commands with the build's root directory as the working directory. Both builds sit in the same repository, so they see the same commit and dirty state.
- When git fails (no repository, git missing), write `commit=unknown` and `dirty=true`. A record that cannot name its source never matches unless an install run id ties it to the same run.
- Write `install_run_id` only when `dirty=true`. For clean records the key is present with an empty value.
- `release_tag` holds `RELEASE_VERSION` verbatim, empty when unset.
- Register `writeBuildIdentity` (a `@CacheableTask` with `@Input` properties for all four values and an `@OutputFile` at `build/build-identity/build-identity.properties`). Every value is a task input, so neither the build cache nor the configuration cache can hand back a stale identity.
- Write the file by hand in fixed key order, one `key=value` per line, with no timestamp comment. Do not use `java.util.Properties.store`, which adds a date line and breaks byte comparison in task 7.
- Expose the output through the task provider so consumers wire it with `from(writeBuildIdentity)` and get the task dependency for free.
- Include the build from both `../../../runtime-kotlin/settings.gradle.kts` and `desktop-tray/settings.gradle.kts` in `pluginManagement { includeBuild("../build-identity") }`, next to each build's own `build-logic`. The name `build-identity` differs from both `build-logic` builds, so no duplicate-name clash.
- Constraint: this is a Gradle plugin build, not a runtime module, so `RuntimeModuleCatalog` gets no entry (G4). Assumption for implement: confirm the catalog only scans `runtime-kotlin` subprojects.
- Tests: none of its own (sub-spec Test Obligations). Validate's builds run the task.

### Task 2. Runtime distributions carry the record (criterion 1)

- Apply `id("skillbill.build-identity")` in `../../../runtime-kotlin/runtime-cli/build.gradle.kts` and `runtime-kotlin/runtime-mcp/build.gradle.kts`.
- In `RuntimeImageConventionPlugin.kt`, inside `pluginManager.withPlugin("skillbill.build-identity")`, extend the same `extensions.configure<DistributionContainer> { named("main") { contents { ... } } }` hook that adds `LICENSE` with `from(tasks.named("writeBuildIdentity"))`. The file lands at the distribution root, so `installDist` gives `build/install/runtime-cli/build-identity.properties`, `runtimeZip` carries it at the image root, and `../../../install.sh` activates it as `<runtime root>/runtime-cli/build-identity.properties`.
- Extend `verifyRuntimeImageLicense` (or add a sibling verify task wired the same way) so it also requires `build-identity.properties` in the `installDist` output and the runtime image. That is the existing guard pattern. It fails the runtime build if a refactor drops the record.
- Constraint A1: `runtime-cli` and `runtime-mcp` only package the file. No Kotlin code in the runtime reads it. `RuntimeProvenanceContract` stays unchanged (non-goal).

### Task 3. Desktop bundle carries the record as an app resource (criterion 1)

- Apply `id("skillbill.build-identity")` in `../../../desktop-tray/composeApp/build.gradle.kts`.
- In `DesktopApplicationConventionPlugin.kt`, under `withPlugin("skillbill.build-identity")`, register `prepareAppResources` (a `Sync` into `build/generated/app-resources/common/` from `writeBuildIdentity`). Set `compose.desktop.application.nativeDistributions.appResourcesRootDir` to `build/generated/app-resources` and make `createDistributable` and `run` depend on it. Packaged paths: `Skill Bill/lib/app/resources/build-identity.properties` on Linux and Windows, `Skill Bill.app/Contents/app/resources/build-identity.properties` on macOS.
- The app reads its own record from the directory named by system property `compose.application.resources.dir`. Assumption for implement: Compose's `run` task also sets this property when `appResourcesRootDir` is configured. If it does not, the dev `run` path must set it through `jvmArgs`, so dev runs do not refuse themselves.
- Constraint R5: the record goes in at packaging time. `../../../install.sh` never writes inside the packaged app.

### Task 4. Identity record and equality, JDK-free (criteria 2, 3)

- Add `core/data/src/commonMain/kotlin/.../runtime/identity/BuildIdentityRecord.kt` (implement picks the exact package next to the existing `session` package). It holds `data class BuildIdentityRecord(commit, dirty, releaseTag, installRunId)`, a `parse(text: String): BuildIdentityRecord?` that splits `key=value` lines by hand (returns null when `commit` or `dirty` is absent or `dirty` is not `true`/`false`), `fun sameBuildAs(other): Boolean`, and a `display()` string such as `release v0.8.0, commit 1a2b3c4` or `commit 1a2b3c4 (dirty, run <id>)`.
- The desktop-side key literals live once here (A6). Pure Kotlin in commonMain, no `java.*` and no `skillbill.*` imports (`commonForbidden`), no DI annotations. Subtask 2's bootstrap loads this class before the runtime classpath exists.
- Equality rule: if either record is dirty, equal only when both are dirty, the commits match and both carry the same non-empty `installRunId`. Otherwise equal when commits and release tags match.
- Test (Test Obligations, identity equality): `core/data` commonTest or jvmTest `BuildIdentityRecordTest`. One case each for: same-commit dirty records with different or missing run ids are unequal, same non-empty run id is equal, clean records compare by commit and tag (a clean record against a dirty one of the same commit is unequal). It catches a comparator that ignores `dirty`.

### Task 5. Dedicated failure state in the domain (criterion 2)

- `core/domain/src/commonMain/.../model/WorkModels.kt`: add `ProblemCode.RUNTIME_BUILD_MISMATCH("runtime_build_mismatch")`, an `enum class RuntimeBuildReason { MISMATCH, MISSING, UNREADABLE, NOT_INSTALLED }`, and `data class RuntimeBuildProblem(reason, installedIdentity: String?, desktopIdentity: String, reinstallCommand: String)`. Extend `Problem` with `val runtimeBuild: RuntimeBuildProblem? = null`. The default keeps every existing constructor call unchanged. Domain stays free of `java.*`.
- Reason mapping: no runtime root or no `runtime-cli` directory gives `NOT_INSTALLED`. A root without the record gives `MISSING`. A record that cannot be read or does not parse gives `UNREADABLE`. A parsed but unequal record gives `MISMATCH`. A desktop that cannot read its own record also refuses, as `UNREADABLE` with the desktop identity shown as unknown.
- Add `class RuntimeBuildRefusedException(val problem: RuntimeBuildProblem) : Exception(...)` in `core/domain` commonMain. This is the signal `StoreSessionCoordinator` matches in task 6. Assumption for implement: `WorkStoreSession.selectDatabase` reports failures by throwing, as the digest's description of `StoreSessionCoordinator` (catch, then `DATABASE_ACCESS`) implies. If the session reports through a returned result or state instead, carry the refusal in that channel.
- Reinstall command: when the installed record carries a release tag, the release install command with that tag. Otherwise the source install command. Assumption for implement: read the real flag names from `../../../install.sh` `usage()` (the digest suggests `./install.sh --from-source` and `install.sh --release <tag>`) and keep the literal in one place in core/data.

### Task 6. Gate before `SessionFactory.create` (criterion 2, parent criterion 12)

- `core/data/src/commonMain/.../session/RuntimeBuildGate.kt`: `fun interface RuntimeBuildGate { fun check(): RuntimeBuildProblem? }`, where null means proceed.
- `core/data/src/jvmMain/.../runtime/RuntimeInstallRoot.kt`: one function `runtimeInstallRoot(variables: Map<String, String>, userHome: String): Path` that returns `SKILL_BILL_RUNTIME_DIR` when set and non-blank, else `<userHome>/.skill-bill/runtime`. `SKILL_BILL_STATE_DIR` is ignored, matching `../../../install.sh`. Subtask 2's bootstrap calls the same function with `System.getenv()` and `user.home`.
- `core/data/src/jvmMain/.../runtime/InstalledRuntimeBuildGate.kt`: `@SingleIn(AppScope) @ContributesBinding(AppScope)`, constructed from the injected `HostEnvironment` and the desktop record path. It reads `<root>/runtime-cli/build-identity.properties` and the desktop's own record fresh on every `check()` with no caching, and compares them through `BuildIdentityRecord`. It reads files only: no process, no JDBC, no `RuntimeComponent`.
- Provide the desktop record path from a jvmMain `@Provides` that reads `System.getProperty("compose.application.resources.dir")` once, so the gate itself takes a plain value (A8). Implement places it next to the existing `RuntimeSessionProviders.kt` or `processHostEnvironment()` and keeps to the module's DI scoping.
- `WorkStoreSessionManager`: add a `gate: RuntimeBuildGate` constructor parameter. In `selectDatabase(path)`, under the existing `Mutex`, call `gate.check()` first. On refusal, close any active session (slot back to `Idle`) and throw `RuntimeBuildRefusedException` before `factory.create(path)`, so no `DatabaseSession`, `RuntimeComponent` or work control is built. Run the same check at the start of the refresh path, so a refresh against a now-mismatched runtime also refuses and closes.
- `composeApp/.../application/StoreSessionCoordinator.kt`: catch `RuntimeBuildRefusedException` ahead of the generic catch and publish `Problem(ProblemCode.RUNTIME_BUILD_MISMATCH, summary, runtimeBuild = e.problem)` instead of `DATABASE_ACCESS`. Remember the last requested path. When the current problem is `RUNTIME_BUILD_MISMATCH`, `TrayAction.Refresh` re-runs `switchDatabase(lastPath)`, which re-reads the installed record and selects the database once it matches. Assumption for implement: confirm how `TrayAction.Refresh` reaches the coordinator today and route it this way only for this problem code.
- UI: the shell already renders `owner.storeSession.problem`. Show the summary, both `display()` identities and the reinstall command when `runtimeBuild` is set. Keep the change inside the existing problem presentation. Strings follow the module's string-resource convention.
- Architecture guards: the reader and root function stay in `core/data` jvmMain (an `adapterModules` member). No `reflectiveLookup` token, and composition does not construct the gate (`composedByGraph`). No guard baseline or allow-list grows (G7).
- Test (Test Obligations, parent criterion 12 regression): `core/data/src/jvmTest/.../session/RuntimeBuildGateRegressionTest.kt`. Use a temp runtime root (passed through `HostEnvironment` variables as `SKILL_BILL_RUNTIME_DIR`) holding record A, a temp desktop record B, and a database at an older `user_version` that `upgradeExistingStore` would migrate (build it with the `runtime-infra-sqlite` test fixtures already on the classpath). Use the real `InstalledRuntimeBuildGate` and a counting `SessionFactory` that delegates to the real one. Assert `selectDatabase` refuses with the `runtime_build_mismatch` problem carrying both identities, the factory count is 0, and `PRAGMA user_version` is unchanged. Then rewrite the installed record to equal B, refresh and assert the database is selected. It catches a gate placed after `factory.create` and a retry that caches the first read.
- Existing tests that build `WorkStoreSessionManager` pass a gate that always proceeds. That is test setup, not new coverage.

### Task 7. `../../../install.sh` matched pair (criterion 4)

- Generate one run id per run near the top of `run_full_install()`: `SKILL_BILL_INSTALL_RUN_ID="${SKILL_BILL_INSTALL_RUN_ID:-$(date +%s)-$$-$RANDOM}"` and export it. Export `RELEASE_VERSION` once too, so `build_kotlin_runtime_distributions` (`./gradlew -q :runtime-cli:installDist :runtime-mcp:installDist`) and `build_source_desktop_app` (`createDistributable`) see the same values. The source path builds both from the same checkout in one run.
- Split `install_runtime_distributions` / `install_packaged_runtime_pair` into a stage step (build or fetch, `unpack_runtime_image`, stage into the `skill-bill-runtime-candidates.XXXXXX` dir as `runtime-cli.tmp` and `runtime-mcp.tmp`, run `--check-packaged-contracts`) and an activate step (`clean_install_state_if_requested`, `run_pre_install_uninstall`, then the existing `rm -rf` and `mv`).
- Split `install_selected_desktop_app` the same way. Stage builds or fetches, verifies sha256, applies the tar whitelist and extracts into `$install_root/.Skill Bill.installing.$$`. Activate does the backup, `mv` into `desktop_app_target_path`, `install_desktop_app_launcher` and the stop/relaunch.
- Add `read_build_identity_field <file> <key>` and `compare_build_identities <runtime_record> <desktop_record>`, applying the task 4 rule in shell. The shell keeps its own copies of the four key literals in one place in `../../../install.sh`, matching task 1. Locate the staged desktop record with the per-OS resource path from task 3 (`lib/app/resources/` or `Contents/app/resources/`).
- New `run_full_install()` order: stage runtime, stage desktop, compare, then activate runtime, `install_runtime_launchers`, `apply_runtime_install`, and activate desktop last. On a mismatch, or when the desktop cannot be staged, print both identities (or the staging error), remove the candidate and staging dirs and exit non-zero before any `rm -rf` or `mv` of a live directory. This replaces today's "Installation incomplete" continue-after-runtime behavior (decision U5).
- Release path: resolve runtime and desktop assets for the same release tag, then stage and compare the same way.
- When the run does not select the desktop, skip the comparison and keep today's runtime-only behavior. The desktop's startup gate covers a later mismatch. Assumption for implement: confirm whether `../../../install.sh` has such a mode. If it always installs the desktop, this branch does not exist.
- Update `usage()` to say both paths install a matched runtime and desktop pair. Subtask 2 owns the final wording.
- Constraint: the `SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD`, `SKILL_BILL_RELEASE_DIR` and `SKILL_BILL_DESKTOP_APP_DIR` test hatches keep working. Fixtures supply records.
- Test (Test Obligations, installer): in `../../../runtime-kotlin/runtime-cli/src/repoTest/kotlin/skillbill/installer` (`InstallerShellDelegationTest.kt` with its `...TestSupport.kt` fixtures). Prebuilt runtime and desktop fixtures carry differing records. Assert a non-zero exit, both identities in the output, and that the pre-seeded live runtime and desktop directories are byte-identical afterwards. Existing installer fixtures gain matching records so their current assertions still hold. That is fixture repair, not new coverage.

### Task 8. Release comparison step (criterion 4)

- `../../../.github/workflows/release.yml`: after the runtime `runtimeZip` builds and the desktop `createDistributable`, add a step that compares `runtime-kotlin/runtime-cli/build/install/runtime-cli/build-identity.properties` (or the record inside the runtime image) with the desktop's packaged record using `cmp`. Clean release records have byte-identical content (task 1's fixed format). The step fails the release on a difference. Implement confirms the exact output paths in the workflow.

### Task 9. Shared library alignment and build check (criterion 5)

- `../../../runtime-kotlin/gradle/libs.versions.toml`: raise `kotlin` from `2.4.0-Beta2` to `2.4.21`, `ksp` to the release matching 2.4.21, and `kotlinx-serialization` from `1.7.3` to `1.9.0`. Add `kotlinx-coroutines` `1.10.2` only if the runtime resolves coroutines. Assumption for implement: a KSP release for 2.4.21 exists. If not, block with that finding rather than downgrade the desktop (Compose 1.12.1 needs 2.4.21). Fix any compile fallout in the runtime that the bump causes. That repair is in scope.
- `../../../desktop-tray/settings.gradle.kts`: add `substitute(module("dev.skillbill:runtime-cli")).using(project(":runtime-cli"))` to the existing `includeBuild("../runtime-kotlin")` block.
- In desktop `build-logic` (`DesktopApplicationConventionPlugin.kt` or `QualityConventionPlugin.kt`, whichever already owns `check` wiring for composeApp), register `verifySharedRuntimeVersions`. It resolves a detached configuration holding only `dev.skillbill:runtime-cli` with JVM runtime attributes (`Usage.JAVA_RUNTIME`, Kotlin JVM platform type), so the runtime resolves on its own and not in the desktop's graph. It compares versions for every `group:module` present in both that graph and composeApp `jvmRuntimeClasspath`, excluding the `dev.skillbill` group, and fails listing each differing module with both versions. Pass the two resolution results as task inputs through `incoming.resolutionResult.rootComponent` providers (configuration-cache safe).
- Wire `check` and `createDistributable` to depend on it.
- Constraint: the check compares by `group:module`. The kotlin-inject pair (`kotlin-inject-runtime-kmp` on the desktop against `kotlin-inject-runtime` on the runtime) are different modules, so this check does not catch duplicate classes between them. Subtask 2 handles that when it removes the bundled copies.
- Tests: none beyond the check itself (sub-spec Test Obligations).

### Left to subtask 2

`compileOnly` wiring, the bootstrap, removing bundled runtime jars, CI runtime provisioning, the `../../../desktop-tray/agent/decisions.md` and `ARCHITECTURE.md` entries, the README install section and the final `install.sh` usage wording.

## Validation Strategy

Validate runs the required `runtime-kotlin` and `desktop-tray` checks, including architecture guards, installer repoTests and desktop tests, plus the new shared-version build check. Manual confirmation from the parent: install the runtime from one commit and the desktop from another and confirm the dedicated state appears and `PRAGMA user_version` does not change.

## Next Path

```bash
skill-bill goal SKILL-430
```
