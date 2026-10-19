# SKILL-430 - single-runtime-copy-for-desktop

## Mode

decomposed

## Intended Outcome

SKILL-430 Run the desktop app on the installed runtime instead of a bundled copy

The machine has one Skill Bill runtime. The desktop app loads it from the runtime install root and refuses to start work against a runtime from a different build.

Problem

A Skill Bill install holds two copies of the runtime code:

- The CLI runtime under `~/.skill-bill/runtime/runtime-cli/lib` and `~/.skill-bill/runtime/runtime-mcp/lib`. The `skill-bill` and `skill-bill-mcp` launchers, agent MCP servers, goal runners and standalone monitors run on it.
- A second copy bundled into the desktop app. `desktop-tray/settings.gradle.kts` pulls `../runtime-kotlin` in with `includeBuild` and substitutes `runtime-core`, `runtime-contracts`, `runtime-infra-host`, `runtime-infra-contracts` and `runtime-infra-sqlite`. `composeApp` and `core/data` depend on `runtime-infra-host` and `runtime-infra-contracts` with `implementation`, so jpackage ships the whole runtime graph (engine, domain, sqlite, launcher, workflow, ports) in `~/.local/share/skillbill/desktop/Skill Bill/lib/app/`.

The desktop reads work in-process through its bundled copy (`RuntimeSessionProviders`, `RuntimeWorkAdapter`, `RuntimeComponent`). It acts through the installed copy: `DetachedWorkRunLauncher` resolves `RUNTIME_EXECUTABLE = "skill-bill"` from PATH and starts it under `systemd-run --user --scope`. Both copies carry their own migration list and schema check.

Nothing makes both copies come from the same commit. On 2026-10-19 the CLI runtime was installed from main (highest migration 60) at 12:11 and the desktop app from a SKILL-419 branch checkout (migration 61, `record-feature-task-qa-outcomes`) at 12:15. The branch-built code migrated `review-metrics.db` to `user_version` 61, the main CLI then refused every read ("Database read failed ... schema is incompatible with this runtime"), and after a manual rollback to 60 the desktop refused the database instead ("The database is incompatible with this app. Update the runtime and apply its supported migrations before reconnecting."). Resume on goal 0AC-64 failed throughout. Neither message named the real cause, which was two builds from different commits sharing one database.

This contradicts the SKILL-423 decision in `desktop-tray/agent/decisions.md` ("Read work through the runtime store"), whose reason says reads must not "establish a second schema authority". The bundled copy is a second schema authority.

Outcome

1. Every runtime install and every desktop bundle records the source build identity it was built from (source commit, plus a dirty marker for uncommitted source builds, or the release tag for release builds). The desktop compares its own identity with the installed runtime's before it opens a database session or offers any control. On a mismatch it opens no session, launches nothing, and shows one state that names both identities and says to reinstall. `install.sh` builds the runtime and the desktop from the same checkout in one run, or installs a matching release pair, and fails before activation when the two identities differ.
2. The desktop bundle contains no runtime module jars. At startup a bootstrap entry point puts the installed runtime's jars on the app classpath and then starts the existing app entry point. The desktop keeps its in-process read and control design from SKILL-423. It never runs the installed CLI for reads, never discovers PATH for reads, and never parses command output. The installed runtime is the only code that defines migrations and schema compatibility for `review-metrics.db`.

Subtasks

1. Build identity gate (lands first, independently useful).
2. Single runtime copy (depends on 1; uses the identity check as its startup guard).

## Acceptance Criteria

1. The runtime install writes a build identity record under the runtime install root containing the source commit SHA, a dirty flag for source builds with uncommitted changes, and the release tag for release installs. The desktop bundle carries the same record for its own build. Both come from one shared Gradle source of truth, not two hand-maintained values.
2. Before it creates a `DatabaseSession` or resolves any work control, the desktop reads the installed runtime's build identity and compares it with its own. Equal identities proceed. A missing, unreadable or different identity yields a dedicated failure state, not `incompatible_database`. That state shows both identities and the reinstall command, opens no database connection, and starts no process. Retry re-reads the installed identity.
3. A dirty source build counts as different from every other build, including another dirty build of the same commit, unless both records come from the same `install.sh` run.
4. `install.sh` (source path) builds the runtime and the desktop app from the same checkout in a single run. `install.sh` (release path) installs a runtime image and a desktop build for the same release tag. Both paths compare the two identities after staging and before activation, and on a mismatch exit non-zero without replacing the active runtime or desktop app.
5. After subtask 2, `~/.local/share/skillbill/desktop/Skill Bill/lib/app/` contains no `runtime-*-0.7.1-SNAPSHOT*.jar` or other Skill Bill runtime module jar. The desktop modules compile against the runtime with `compileOnly` (or an equivalent that keeps runtime modules out of the packaged classpath).
6. The packaged desktop starts through a bootstrap entry point that resolves the runtime install root (`~/.skill-bill/runtime/runtime-cli/lib` by default, with the same override precedence the runtime already uses), runs the identity check from criterion 2, builds the application classpath from the installed jars, and calls the existing app entry point. A missing runtime install produces the same dedicated failure state with a "runtime not installed" reason instead of a `ClassNotFoundException`.
7. Each third-party library that both the desktop and the runtime use (at least kotlin-stdlib, kotlinx-coroutines, kotlinx-serialization and the SQLite drivers) is loaded at exactly one version at runtime. A build check fails when the desktop's resolved version of a shared library differs from the runtime's, so a conflict shows up at build time instead of as a `LinkageError` in a running app.
8. With the desktop on the installed runtime, reads through `RuntimeWorkAdapter`, board and detail views, the sidecar change signal, and every work control (resume, pause, cancel, choose agent) behave as they do today. `DetachedWorkRunLauncher` still launches the installed `skill-bill` for runs.
9. Only the installed runtime defines migrations and schema compatibility. No code path in the desktop bundle applies a migration or carries its own copy of the migration list.
10. `desktop-tray/agent/decisions.md` records the new decision (desktop loads the installed runtime; build identity gates startup), and `desktop-tray/ARCHITECTURE.md` describes the bootstrap and the identity check in place of the composite-build bundling. `install.sh` usage and the README install section describe the matched runtime and desktop pair.
11. `PackagedDataSmoke` and the other desktop tests that assume a bundled runtime run against an installed or test-provisioned runtime root. `desktop-tray-ci.yml` and `release.yml` provision the runtime before packaging or smoke-testing the desktop, and the release produces a desktop asset without runtime jars.
12. A test reproduces the 2026-10-19 failure: runtime identity A installed, desktop identity B started. The desktop shows the mismatch state, opens no database session, and leaves `user_version` unchanged.

## Constraints

- Supplied requirements are authoritative and need no tracker lookup. SKILL-430 is a locally allocated issue key and does not require a tracker connection.
- Read `docs/architecture-guidelines.md` before planning or changing anything under `runtime-kotlin`, and cite its rule IDs in review.
- Keep the SKILL-423 in-process read design. Do not reintroduce CLI reads, PATH discovery for reads, or command-output parsing in the desktop.
- Subtask 1 must be shippable alone, with the desktop still bundling its runtime, so drift is caught before the bundle is removed.
- Do not weaken architecture guards, detekt baselines or desktop deletion rules to make the bootstrap fit. If a rule blocks the bootstrap classloader, record an operator-approved deviation in `desktop-tray/agent/decisions.md` the way the 2026-10-15 entry does.
- Existing `review-metrics.db` files stay readable. No new migration is required for this work.

## Non-Goals

- Not merging the CLI and MCP launchers into the desktop app, and not making the CLI depend on a desktop install.
- Not changing how `DetachedWorkRunLauncher` launches runs.
- Not supporting a desktop app that runs against a runtime from a different build, even a compatible one.
- Not adding automatic downgrade or rollback of database schemas.
- Not cleaning up databases that a mismatched pair already migrated. Operators repair those manually.

## Validation Strategy

Run the repository's required checks for `runtime-kotlin` and `desktop-tray`, including architecture guards and desktop tests. Build the packaged desktop with `install.sh --from-source` from a clean checkout, then verify on Linux that the bundle has no runtime jars, the app starts on the installed runtime, the board and detail views load, and Resume launches a run. Verify the mismatch path by installing the runtime from one commit and the desktop from another, and confirm the dedicated state appears and `PRAGMA user_version` on the selected database does not change. Verify every supplied acceptance criterion.
