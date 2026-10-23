# SKILL-441 subtask 1: project store resolver, identity and install lifecycle

## Scope

Introduce the single project-store resolver that every later subtask uses. It maps a checkout (main
checkout, linked worktree, or non-git directory) to `<state dir>/projects/<project-id>/` without
writing any marker into the project or its `.git`. This subtask also adds the `project.json` record
contract, the `skill-bill project link <id>` command that the multiple-candidate record offers, and
the uninstall and reinstall preservation of `~/.skill-bill/projects/`.

Nothing in this subtask moves existing runtime state. Config, run evidence, tracking, acceptance
repair and spec bundles keep their current paths until subtasks 3, 4 and 5.

## Acceptance Criteria

1. A project-store port and its host implementation resolve the store location for any working directory.
   runtime-ports gains `ProjectStorePort` (package `skillbill.ports.projectstore` or the nearest existing ports package) with `fun resolve(workingDirectory: Path): ProjectStoreLocation`, plus the model `ProjectStoreLocation(projectId: String, root: Path, specsRoot: Path, configPath: Path, runEvidenceRoot: Path, trackingRoot: Path, acceptanceRepairRoot: Path)`.
   The children are fixed: `specs`, `config.yaml`, `run-evidence`, `feature-task-tracking`, `acceptance-repair`, and the record file `project.json`, all directly under `root`.
   The store parent is `resolveTelemetryStateDir(environment, userHome).resolve("projects")` from runtime-infra/host `TelemetryConfigPaths.kt`, so the existing state-dir environment override isolates tests and custom homes.
   The `@Inject` implementation lives in runtime-infra/host next to `CanonicalRepositoryRoot`. Its first layer is the exact match: it computes the real path of the git common dir through `RepositoryEnclosingRootPort.primaryRepositoryRoot` and the common-dir resolution it already performs without spawning git, then returns the store whose `project.json` records that exact `common_dir`. A main checkout and every linked worktree of it therefore return the same `projectId`.
   On every successful resolve the implementation creates `root` if missing, writes `project.json` through a temp file in the same directory followed by an atomic move, and refreshes `last_seen` (ISO-8601 UTC). It caches the resolved location per canonical common dir for the life of the process so repeated resolves in one process do not re-scan stores or rewrite `project.json`.
   It creates no file or directory inside the project or its `.git`. Child directories such as `specs` are created lazily by their writers, not by the resolver.
   Lineage, fallbacks and records are criterion 3. The record schema is criterion 2. Audit confirms by reading the port, the model and the host implementation.
2. `project.json` is a governed runtime contract with a schema, a version constant and a non-blocking parse seam.
   A new schema `orchestration/contracts/project-store-record-schema.yaml` defines an object with required `contract_version` (const `"0.1"`), `project_id` (string), `key_kind` (enum `lineage`, `no_commits_path`, `non_git_path`), `common_dir` (string, absolute real path), `root_commits` (array of 40-hex strings, empty for non-lineage kinds), `last_seen` (ISO-8601 UTC string), and optional `remote_url` (string or null) and `linked_to` (string, a project id, set by criterion 4).
   A Kotlin constant `PROJECT_STORE_RECORD_CONTRACT_VERSION = "0.1"` lives with the other `*_CONTRACT_VERSION` constants, and the wire keys are declared once in a `*Keys` object so WireVocabularyArchitectureTest accepts them. Assumption for implement to confirm: the existing constant home is runtime-contracts.
   A new `RuntimeFailureCode` entry (named to the existing convention, for example `PROJECT_STORE_RECORD_INVALID`) identifies a record that fails to parse or carries another `contract_version`.
   The parse seam raises that failure code internally, and the resolver catches it, emits one diagnostics warning `seam=project_store_record value_expected=contract_version 0.1 value_used=<observed version or parse error> cause=<failure code> path=<record path>`, and treats that store as absent for matching. The resolver never overwrites or deletes a malformed record and never blocks; when the id it wanted is taken by a malformed record it uses the next free suffixed id from criterion 3.
   Audit confirms by reading the schema, the constant, the failure code, and the catch site in the resolver.
3. When the exact match misses, the resolver falls back through lineage, candidate selection, no-commit and non-git keys, and emits one bounded diagnostics record for every fallback.
   A new port `RepositoryLineagePort` in runtime-ports returns `RepositoryLineage(rootCommits: List<String>, remoteUrl: String?)` or null. Its implementation in runtime-infra/workflow next to `GitRepositoryOriginScopeKey` runs `git rev-list --max-parents=0 HEAD` (sorted, de-duplicated) and reads the `origin` remote URL, returning null when the directory has no commits or git fails.
   Project ids are deterministic: lineage `l-<first 16 hex of sha256(sorted root commits joined by "\n")>`; additional stores for the same lineage `l-<hex>-2`, `l-<hex>-3` and so on, taking the next free suffix; no-commit repositories `p-<16 hex of sha256(common-dir real path)>`; non-git directories `d-<16 hex of sha256(absolute real path)>`.
   Candidates are stores whose record has `key_kind: lineage` and shares at least one root commit with the current lineage. When more than one candidate exists and exactly one has a `remote_url` equal to the current remote URL, that one is the only candidate.
   Exactly one candidate whose recorded `common_dir` no longer exists is adopted: its record gets the new `common_dir` and `last_seen`, and the resolver emits `value_used=lineage_adopted`. Exactly one candidate whose `common_dir` still exists means a second clone: the resolver creates a new suffixed store and emits `value_used=second_clone matched=<id>`. More than one candidate creates a new suffixed store and emits `value_used=multiple_candidates candidates=<ids>` with the hint `run skill-bill project link <id> to attach this checkout`. No candidate creates the plain lineage store without a record.
   A git repository with no commits gets the `p-` key and `value_used=no_commits_path_key`. When the exact match finds a `no_commits_path` store and the repository now has commits, the resolver renames that store directory to the lineage id with an atomic move, rewrites its record as `lineage`, and emits `value_used=rekeyed_to_lineage`. A non-git directory gets the `d-` key and `value_used=non_git_path_hash`. Any git or filesystem failure during identity falls back to the `d-` key of the working directory and emits `value_used=git_unavailable cause=<exception class>`; resolve never throws for identity reasons.
   Every record follows docs/observability-policy.md: `seam=project_store_identity value_expected=exact_common_dir_match value_used=<outcome>`, emitted through `RuntimeDiagnostics.warning`, bounded, with no payloads.
   The `link` command is criterion 4. Audit confirms by reading the lineage port, its git implementation and each branch in the resolver.
4. `skill-bill project link <id>` attaches the current checkout to an existing store.
   `ProjectStorePort` gains `fun link(workingDirectory: Path, projectId: String): ProjectStoreLocation`. It rewrites the target store's record with the current checkout's `common_dir`, current lineage and `last_seen`, and sets `linked_to: <target id>` on any other store whose record held the same `common_dir`. The exact-match layer from criterion 1 skips records that carry `linked_to`. It deletes no store and no project file, and it clears the per-process cache entry for that common dir.
   An unknown or malformed target id fails the command with a non-zero exit and a message naming the id and the store parent. That is a user command, so it fails loudly; runtime launches still never block on identity.
   runtime-cli gains a top-level `project` command group registered in `cli/core/CliCommandGroups.kt` next to `configCommand`, with the `link <id>` subcommand injected through kotlin-inject. It completes through `state.completeText(text, payload)` with payload keys `project_id` and `store_root` declared once in a `*Keys` object.
   The `project path` subcommand and `config edit` belong to subtask 6. Audit confirms by reading the port method, the host implementation and the CLI wiring.
5. Reinstall and full uninstall never delete `~/.skill-bill/projects/`.
   In `uninstall.sh`, `remove_install_state` adds `projects` to the `preserved` names in the full-uninstall loop next to `backups` and the `*.db*` files, and the help text near line 222 lists `projects/` among the preserved state. The `SKILL_BILL_PRESERVE_SOURCE_ON_WIPE=1` reinstall path keeps deleting only its explicit list (runtime, installed-skills, .candidate-source and the native-agent-link-inventory files), so it keeps skipping `projects`.
   In runtime-application, `UninstallMutations.removeInstallState` skips an entry named `projects` in the same condition that skips `backups` and `DATABASE_STATE_NAME`, so `SkillBillUninstallService.apply()` leaves it in place.
   `install.sh` `clean_install_state` is not changed because it never touches `projects`. Audit confirms by reading both wipe paths in `uninstall.sh` and the skip condition in `UninstallMutations.kt`.

## Criterion Sizing

1. About 220 production lines, 4 files, 2 areas (`runtime-ports`, `runtime-infra/host`). Fits.
2. About 150 production lines, 4 files, 3 areas (`orchestration/contracts`, `runtime-contracts`, `runtime-infra/host`). Fits.
3. About 280 production lines, 4 files, 3 areas (`runtime-ports`, `runtime-infra/workflow`, `runtime-infra/host`). Fits.
4. About 140 production lines, 4 files, 3 areas (`runtime-ports`, `runtime-infra/host`, `runtime-cli`). Fits.
5. About 15 production lines, 2 files, 2 areas (`uninstall.sh`, `runtime-application`). Fits.

## Non-Goals

- Moving config, run evidence, tracking, acceptance repair or spec bundles (subtasks 3 to 5).
- The legacy `./.skill-bill/` notice (subtask 2).
- `skill-bill project path` and `skill-bill config edit` (subtask 6).

## Dependency Notes

First subtask; no dependencies. Every later subtask consumes `ProjectStorePort` and `ProjectStoreLocation`. The DI wiring that hands the runtime-infra/workflow `RepositoryLineagePort` implementation to the runtime-infra/host resolver goes through the existing composition root; implement confirms where that root lives.

## Test Obligations

- Resolver identity (runtime-infra/host, temp home through the state-dir override, real temp git repos): a linked worktree resolves to the main checkout's id; a moved checkout with one missing-path candidate is adopted; a second clone with the original still present gets a new suffixed store; a no-commit repo is re-keyed to lineage after its first commit; a non-git directory gets the `d-` key. Each would catch a wrong id, which silently splits or merges project state.
- A malformed `project.json` is skipped, not overwritten, and resolve still returns a location.
- `link` moves the exact match to the target store and the old store stops matching.
- Uninstall preservation: one Kotlin test in `SkillBillUninstallDestructiveSemanticsTest` that `projects/` survives `apply()`, and one shell case in `InstallerShellDelegationTest` that `uninstall.sh` full uninstall keeps `projects/`. The spec requires this enforcement.
- Contract parity for the new schema and constant, following the existing parity-test pattern.

## Implementation Details

Ordered tasks. Each names the criteria it serves, what it touches, the tests it owns and its constraints. Paths are relative to `runtime-kotlin/` unless they start at the repository root.

1. Contract first (criterion 2).
   - Add `orchestration/contracts/project-store-record-schema.yaml` with the fields, types, enum, `const "0.1"` and the 40-hex `root_commits` item pattern from criterion 2. Set `additionalProperties: false` so a stray key fails parity rather than silently passing.
   - Add `PROJECT_STORE_RECORD_CONTRACT_VERSION = "0.1"` next to the existing `*_CONTRACT_VERSION` constants. Assumption for implement to confirm: that home is runtime-contracts. If the constants live elsewhere, follow them there.
   - Add a `ProjectStoreRecordKeys` object holding `contract_version`, `project_id`, `key_kind`, `common_dir`, `root_commits`, `remote_url`, `last_seen` and `linked_to`. Put it beside the constant so the host parser and the CLI payload never redeclare the strings.
   - Add `RuntimeFailureCode.PROJECT_STORE_RECORD_INVALID`, or the closest name the enum's convention allows.
   - Add a parity test for the schema and the constant, copying the nearest existing schema parity test. It catches a schema/constant version drift or a key renamed on one side only.

2. Ports and model (criteria 1, 3, 4).
   - Under runtime-ports, add `skillbill/ports/projectstore/`, or the nearest existing ports package if the module layout rejects a new one. It holds:
     - `ProjectStorePort` with `resolve(workingDirectory: Path): ProjectStoreLocation` and `link(workingDirectory: Path, projectId: String): ProjectStoreLocation`, with KDoc on the interface only.
     - `ProjectStoreLocation`, with exactly the seven fields from criterion 1. Add a `recordPath` computed property only if `project.json` callers need it; otherwise the host derives it.
     - `RepositoryLineagePort` with `fun lineage(repoRoot: Path): RepositoryLineage?` and the `RepositoryLineage(rootCommits: List<String>, remoteUrl: String?)` model.
   - Constraints: no `//` comments, KDoc only on interfaces, no inline FQNs.

3. Lineage implementation (criterion 3).
   - Add `GitRepositoryLineage` as an `@Inject` class in runtime-infra/workflow next to `GitRepositoryOriginScopeKey`. Reuse that class's git process helper.
   - It runs `git rev-list --max-parents=0 HEAD`, then sorts and de-duplicates the output, and reads `git remote get-url origin`. A missing origin gives `remoteUrl = null`.
   - It returns null for a repository with no commits (non-zero exit on `HEAD`) and catches its own process failures as null.
   - Decision, because the criterion maps both cases to null: the resolver treats a null lineage inside a git repository as the no-commit `p-` key. The `git_unavailable` `d-` fallback covers exceptions that escape to the resolver, such as filesystem errors or `RepositoryEnclosingRootPort` failures. A git failure in a repository that has commits therefore gets the `p-` key, and the re-key path moves it to lineage on the next resolve where git succeeds. Implement confirms this is acceptable or narrows it.
   - No dedicated test. The resolver tests use real temp git repositories and exercise this class end to end.

4. Host resolver: exact layer, record I/O and cache (criteria 1, 2).
   - Add `@Inject class ProjectStoreResolver`, or a similar name, implementing `ProjectStorePort` in runtime-infra/host next to `CanonicalRepositoryRoot`. It takes `RepositoryEnclosingRootPort`, `RepositoryLineagePort`, `RuntimeDiagnostics` and `EnvironmentContext` for the environment and `userHome`.
   - Store parent is `resolveTelemetryStateDir(environment, userHome).resolve("projects")`.
   - Common dir: call `enclosingRepositoryRoot(workingDirectory)`. A null result means a non-git directory. Otherwise call `primaryRepositoryRoot` and take the real path of its `.git` directory as `common_dir`. That is the same directory `git rev-parse --git-common-dir` reports for a standard checkout. Implement confirms this against `CanonicalRepositoryRoot`'s linked-worktree `.git` file parsing.
   - Exact layer: list the record files under the store parent and parse each one. Return the store whose `common_dir` equals the current value and whose record has no `linked_to`.
   - Record codec: put a small `ProjectStoreRecordCodec` in the same package with the parse seam. It throws a typed exception carrying `PROJECT_STORE_RECORD_INVALID` on bad JSON, a missing required key or a foreign `contract_version`. Use the JSON library host already uses.
   - The scan loop catches that exception per file, emits the `seam=project_store_record ...` warning from criterion 2, and drops the store from matching. It never rewrites or deletes that file.
   - Write path: create `root` with `createDirectories`. Write `project.json.tmp-<random>` in `root`, then `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`. Never create `specs` or any other child.
   - Cache: a `ConcurrentHashMap<Path, ProjectStoreLocation>` keyed by the canonical common dir, or by the real path of the working directory for non-git directories. A cache hit returns the location without a scan or a write. Assumption for implement to confirm: the class is bound once per process in the composition root. If it is not, scope it as a singleton there.
   - Tests: linked-worktree identity (criterion 1) and the malformed-record test (criterion 2), both in the host test source set with a temp home through the state-dir environment override.

5. Host resolver: fallbacks (criterion 3).
   - Id helpers: `l-`, `p-` and `d-` plus the first 16 hex of sha256, as specified. Pick the next free suffix by probing `-2`, `-3` and so on until no directory exists under the store parent. A directory holding a malformed record counts as taken.
   - Branch order after an exact miss:
     1. Non-git: `d-` key, with `non_git_path_hash`.
     2. Lineage null: `p-` key, with `no_commits_path_key`.
     3. Otherwise, collect lineage candidates (see criterion 3), applying the remote-URL tie-breaker when there are several.
        - With no candidate, create the plain `l-` store and emit no record.
        - With one candidate whose `common_dir` is missing, adopt it as `lineage_adopted`.
        - With one candidate whose `common_dir` exists, create a new suffixed store as `second_clone matched=<id>`.
        - With several, create a new suffixed store as `multiple_candidates candidates=<ids>`, with the link hint.
   - Re-key: when the exact match hits a `no_commits_path` record and lineage is now non-null, move the store directory to the `l-` id (atomic move, suffixed if that id is taken). Rewrite the record as `lineage` and emit `rekeyed_to_lineage`.
   - Wrap the whole identity computation in one `runCatching`. A failure emits `git_unavailable cause=<exception class simple name>` and resolves the `d-` key of the working directory. Rethrow `CancellationException`.
   - Every record goes through `RuntimeDiagnostics.warning` in the `seam=project_store_identity value_expected=exact_common_dir_match value_used=<outcome>` shape. Records list ids only, never record contents.
   - File ceiling: if the class approaches `ProductionFileLineCeilingArchitectureTest`, move candidate selection into a separate `ProjectStoreCandidateSelection.kt` in the same package.
   - Tests (one per branch that would silently split or merge state): moved-checkout adoption, second clone getting `-2`, no-commit repo re-keyed after its first commit, and a non-git directory getting `d-`. Assert the returned `projectId` and the on-disk store directories, not the diagnostics call order. The single-candidate tie-break and the git-unavailable branch need no test of their own, because the branch tests cover the shared code.

6. Link (criterion 4).
   - In the host resolver, implement `link`:
     - Load the target record. A missing directory or malformed record throws an exception whose message names the id and the store parent.
     - Rewrite the target record with the current `common_dir`, lineage and `last_seen`, and clear its `linked_to`.
     - Set `linked_to: <target>` on every other parseable record with the same `common_dir`.
     - Evict the cache entry and return the target location.
   - In runtime-cli, add a `ProjectCommand` group as a `DocumentedNoOpCliCommand` registered in `cli/core/CliCommandGroups.kt` beside `configCommand`, and a `ProjectLinkCommand` subcommand (`link <id>`) injected through kotlin-inject. It completes through `state.completeText(text, mapOf(PROJECT_ID to ..., STORE_ROOT to ...))`. Declare the keys once, either in the criterion 2 `*Keys` object or in a CLI `ProjectCommandKeys`, whichever WireVocabularyArchitectureTest expects for CLI payloads.
   - A link failure becomes a Clikt usage or program error with a non-zero exit.
   - Test: one host test where, after `link`, resolving the checkout returns the target id and the old store's record carries `linked_to`. The CLI wiring is thin glue and needs no test of its own.

7. Uninstall preservation (criterion 5).
   - In `uninstall.sh` (repository root), add `projects` to `preserved` in the full-uninstall loop of `remove_install_state`, and add `projects/` to the help text near line 222. Leave the `SKILL_BILL_PRESERVE_SOURCE_ON_WIPE=1` explicit list as it is.
   - In runtime-application `uninstall/UninstallMutations.kt`, extend the skip condition to `name == "backups" || name == "projects" || name == DATABASE_STATE_NAME`. Use a named constant if the file already keeps the names that way.
   - Do not touch `install.sh`.
   - Tests: one case in `SkillBillUninstallDestructiveSemanticsTest` where `projects/<id>/project.json` survives `apply()`, and one case in runtime-cli repoTest `InstallerShellDelegationTest` (helpers from `InstallerShellDelegationTestSupport.kt`) where a full `uninstall.sh` run keeps `projects/`. Both are spec-mandated enforcement.

8. DI wiring (criteria 1, 3, 4).
   - Bind `ProjectStorePort` to the host resolver and `RepositoryLineagePort` to `GitRepositoryLineage` in the existing composition root. Implement confirms where that root lives.
   - No production caller consumes `ProjectStorePort` yet. Later subtasks wire it, so this subtask only binds it and the CLI `link` command uses it.

Cross-cutting constraints:
- Zero footprint: no write under the working directory or its `.git`.
- Identity resolution never throws. Only the user-invoked `link` command fails loudly.
- No installer or install-sync commands appear in this plan.
- Tests use temp homes and temp git repositories, never the real `~/.skill-bill`.
- This phase runs no build or check. Validation belongs to validate.

## Validation Strategy

The validate phase runs `./gradlew check` in `runtime-kotlin`, including repoTest architecture guards (wire vocabulary, KDoc, line ceilings) and the installer shell delegation tests. This phase does not run them.

## Next Path

```bash
skill-bill goal SKILL-441
```
