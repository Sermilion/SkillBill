# SKILL-434 subtask 1 - phase instruction injection

## Scope

Make `.skill-bill/phases/<phase_id>.md` a committed, per-project source of extra instructions that the runtime injects into every launch of that phase, with skip-and-warn handling for bad files and an activity record of what was injected.

Production seams (from the preplan digest; implement confirms exact signatures):

- `../../../runtime-kotlin/runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/install/apply/InstallApplyRepoLocalConfig.kt`: `appendGitignoreEntryIfAbsent` and its `GITIGNORE_*` constants.
- Repository root `../../../.gitignore` (lines near `.skill-bill/**` and `!.skill-bill/config.yaml`).
- `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/lifecycle/checkpoint/FeatureTaskRuntimeCheckpointScope.kt`: `isRuntimePrivatePath`.
- `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/phase/prompt/compose`: `FeatureTaskRuntimePhasePromptComposerSections.kt` (`phasePromptMiddleSections`), `FeatureTaskRuntimePhasePromptComposeInputs.kt`, and a new resolver file in the same package.
- `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/attempt/PhaseLaunchPreparation.kt`: `prepareLaunch`, `composeLaunchPromptInputs`, `recordLaunchBriefing`.
- `FeatureTaskRuntimePhaseLaunchBriefing` (model/phase): `briefingArtifactWireMap`, `fromBriefingArtifactWire`, `ALLOWED_FIELDS`.

Settled decisions:

1. Gitignore lines. `.skill-bill/**` keeps files under an un-ignored directory ignored, so the decision's single `!.skill-bill/phases/` line does not track `qa.md` on its own. Emit both `!.skill-bill/phases/` and `!.skill-bill/phases/**` after `!.skill-bill/config.yaml`. The early return in `appendGitignoreEntryIfAbsent` must require both new lines too, so installs that already carry the old two lines gain the new ones on the next install. The legacy `/.skill-bill/` early return stays unchanged. Apply the same two lines to the root `../../../.gitignore`.
2. Checkpoint scope. Add a trackable prefix constant for `.skill-bill/phases`; `isRuntimePrivatePath` returns false when the normalized path equals it or starts with `.skill-bill/phases/`. `.skill-bill/phasesx` stays private. Agent edits to phase files during a run become stageable; that is intended.
3. Resolver. A new resolver in the compose package, e.g. `PhaseProjectInstructions.resolve(repoRoot, phaseId)`, returns a sealed result: absent, injected (relative path `.skill-bill/phases/<file>.md`, content, sha256 lowercase hex over the exact bytes read), or skipped (path, reason). It catches IO, security, and malformed-UTF-8 failures and never throws. A non-regular file or a read failure counts as unreadable. Files above 16 KiB (a named constant; checked with the file size before reading) count as oversized. Reuse an existing sha256 hex helper if implement finds one nearby; otherwise use `MessageDigest` inline.
4. Name matching. A file stem matches when it equals a value in `FeatureTaskRuntimePhaseIds.all`, or through a small alias map `{code_review -> review}` so the spec's `code_review.md` example works. The lookup for a launch keys on the effective post-rewrite `inputs.briefing.phaseId`. When both `review.md` and `code_review.md` exist, `review.md` wins and the alias file gets a skip warning (assumption; implement may pick the reverse if it records why). Any other `*.md` in `.skill-bill/phases/` is an unknown filename and produces a skip warning on each launch; non-`.md` entries are ignored.
5. Composer stays pure. `FeatureTaskRuntimePhasePromptComposeInputs` gains an optional resolved-instructions field defaulting to null. `phasePromptMiddleSections` appends a third entry after `inputs.briefing.briefingText`: an empty string when null (so `composePhasePrompt` drops it and existing prompts stay byte-identical), otherwise a `## Project instructions for this phase` heading, one line stating the content comes from the project file and cannot change the output or settlement contract, then the file content as plain markdown (not fenced).
6. Launch wiring. `prepareLaunch` resolves instructions once per launch before `recordLaunchBriefing` and before composing the prompt, copies the result into the compose inputs, and emits diagnostics. Skips call the best-effort warning path (`RuntimeDiagnosticsBestEffortWarning.record` or equivalent) with a message beginning `Warning: project phase instructions '<path>' skipped: <reason>; the run continues.` That diagnostics warning is the observability record (assumption: `RuntimeDiagnostics` is the runtime's observability channel available on the launch-preparation context; implement confirms it reaches the user-visible output). Injection emits `diagnostics.info` with the path and sha256 so single-agent sessions, which skip the briefing record, still leave a trace.
7. Activity record. `FeatureTaskRuntimePhaseLaunchBriefing` gains an optional `project_instructions` object `{path, sha256}`, written to the wire map only when present (the `driving_verdict` precedent), added to `ALLOWED_FIELDS`, and decoded optionally so older rows still decode. The contract version stays unchanged. Implement checks whether any checkout schema describes the briefing artifact and updates it if so.

## Acceptance Criteria

1. `InstallApplyRepoLocalConfig` scaffolds `!.skill-bill/phases/` and `!.skill-bill/phases/**` alongside `!.skill-bill/config.yaml`, appends any of them that are missing to an existing gitignore without duplicating present lines, and the repository root `../../../.gitignore` carries the same two lines.
2. `FeatureTaskRuntimeCheckpointScope.isRuntimePrivatePath` returns false for `.skill-bill/phases` and any path under `.skill-bill/phases/`, while other `../../../.skill-bill` paths (including `.skill-bill/phasesx`) stay runtime-private.
3. `phasePromptMiddleSections` emits a "Project instructions for this phase" section holding the resolved file content immediately after the briefing text and before all trailing retry, continuation, and output/settlement sections, and emits nothing when no instructions were resolved.
4. `PhaseLaunchPreparation.prepareLaunch` resolves `.skill-bill/phases/<phase_id>.md` (with the `code_review` alias for `review`) from the run's repo root on every launch, and an unknown filename, a file over the 16 KiB cap, or an unreadable file is skipped with a `Warning:`-prefixed diagnostics warning while the launch proceeds; the resolver never throws.
5. The launch briefing artifact records an optional `project_instructions` entry with the injected file's relative path and SHA-256 hex hash, serialized only when present and accepted by the briefing decoder alongside rows that lack it.

## Test Obligations

- `InstallApplyRepoLocalConfigTest`: extend the fresh-scaffold, idempotent-reapply, and existing-gitignore cases to assert exactly one occurrence of each new line, and add one case where a gitignore holding only `.skill-bill/**` and `!.skill-bill/config.yaml` gains both phases lines (bug caught: existing installs never get the new lines because the early return ignores them). AC 1.
- `FeatureTaskRuntimeCheckpointScopeTest`: one test asserting `.skill-bill/phases/qa.md` and `.skill-bill/phases` are not private while `.skill-bill/phasesx` and `.skill-bill/state.db` are (bug caught: prefix match leaks or phase files never stage). AC 2.
- New composer test (e.g. `FeatureTaskRuntimePhasePromptComposerProjectInstructionsTest`, reusing the existing compose TestSupport builders): the section appears after the briefing text and before the settlement contract; it is still present on a retry or repair composition; null instructions give output identical to today. AC 3.
- Resolver tests with a temp dir: present file yields content and the correct sha256; absent yields absent; oversized, unreadable (e.g. a directory named `qa.md`), and unknown-name files yield skipped with a reason and no exception; `code_review.md` resolves for `review`. AC 4.
- Briefing artifact round-trip: a briefing with `project_instructions` encodes and decodes to the same value, and a wire map without the key still decodes (bug caught: `ALLOWED_FIELDS` rejects new rows or old rows break). Implement places it beside the existing briefing wire tests. AC 5.

## Implementation Details

Ordered tasks. Each names the criteria it serves, the files or symbols it touches, its tests, and its constraints. All facts come from the preplan digest; lines marked "Assumption" are for implement to confirm in the tree.

### Task 1. Installer gitignore lines (AC 1)

- Touches `InstallApplyRepoLocalConfig.kt`. Add `GITIGNORE_PHASES_DIR_EXCEPTION = "!.skill-bill/phases/"` and `GITIGNORE_PHASES_FILES_EXCEPTION = "!.skill-bill/phases/**"` next to `GITIGNORE_CONFIG_EXCEPTION`.
- In `appendGitignoreEntryIfAbsent`, leave the legacy `/.skill-bill/` early return as it is. The modern early return must now require all four lines: `.skill-bill/**`, the config exception, and both phases exceptions.
- Build `linesToAppend` in the fixed order runtime-state, config exception, phases dir, phases files, keeping only the missing ones, then write through the existing `appendLines(existing, linesToAppend)`. Lines already present are never duplicated.
- Fresh scaffolds go through the same function, so they get all four lines with no other change. Assumption: no separate template string for the fresh case exists. If one does, update it the same way.
- Root `../../../.gitignore`: add `!.skill-bill/phases/` and `!.skill-bill/phases/**` directly after `!.skill-bill/config.yaml` (lines 8-10), and extend the comment above them to mention phase instructions.
- Tests: `InstallApplyRepoLocalConfigTest`. Extend the three existing cases with exactly-one-occurrence assertions for both new lines, and add the old-two-lines upgrade case from Test Obligations. Use the existing `setupApplyFixture()`, `fixture.request(agents = setOf(SupportedAgent.CODEX))`, `planInstallForTest`, `applyInstallForTest` and `fixture.repoRoot` helpers.
- Constraints: never run the installer to check this. The tests cover it.

### Task 2. Checkpoint scope treats phases as trackable (AC 2)

- Touches `FeatureTaskRuntimeCheckpointScope.kt`. Add `RUNTIME_TRACKABLE_PHASES = ".skill-bill/phases"` beside `RUNTIME_TRACKABLE_CONFIG`.
- In `isRuntimePrivatePath`, after `normalizeForAliasComparison`, return false when `normalized == RUNTIME_TRACKABLE_PHASES` or `normalized.startsWith("$RUNTIME_TRACKABLE_PHASES/")`. Put the check next to the config check, before the generic `../../../.skill-bill` prefix check. Matching is case-insensitive through the existing lowercase normalization, which is accepted.
- `decide` and `phaseWrittenPaths` take the change through the shared predicate, so they need no edits.
- Tests: the single `FeatureTaskRuntimeCheckpointScopeTest` case from Test Obligations, either beside or extending `trackable skill-bill config is not treated as runtime-private`. The `decide()` case and the `FeatureTaskRuntimePhaseSafetyPolicyTest` line stay optional and are not planned, since the predicate test already catches the realistic bug.

### Task 3. Resolver (AC 4)

- New file `phase/prompt/compose/PhaseProjectInstructions.kt` in runtime-engine:
  - `sealed interface PhaseProjectInstructionsResolution` with `Absent`, `Injected(relativePath: String, content: String, sha256: String)` and `Skipped(relativePath: String, reason: String)`.
  - `object PhaseProjectInstructions` with `const val MAX_BYTES = 16 * 1024`, `PHASES_DIR = ".skill-bill/phases"`, and alias map `mapOf("code_review" to "review")`.
  - `fun resolve(repoRoot: Path, phaseId: String): PhaseProjectInstructionsOutcome`. The outcome holds `injected: Injected?` plus `skipped: List<Skipped>`, because one launch can produce both an injection and unknown-file skips.
- Algorithm:
  1. If `<repoRoot>/.skill-bill/phases` is missing or is not a directory, return an empty outcome.
  2. List direct entries ending in `.md`. Ignore everything else.
  3. For each stem, find its target phase id: the stem itself if it is in `FeatureTaskRuntimePhaseIds.all`, else the alias, else none. A stem with no target gives `Skipped(path, "unknown phase id '<stem>'")`.
  4. The candidates for `phaseId` are `<phaseId>.md` plus any alias file that maps to it. If both `review.md` and `code_review.md` exist, read `review.md` and skip `code_review.md` with reason "shadowed by .skill-bill/phases/review.md". Alias files for other phases are left alone because they are valid, just not for this launch.
  5. Read the chosen file. Not a regular file, or a read failure, gives `Skipped(path, "unreadable: <cause>")`. `Files.size > MAX_BYTES` gives `Skipped(path, "exceeds 16 KiB cap (<n> bytes)")`, checked before reading. Otherwise read the bytes and decode them strictly as UTF-8 with a `CharsetDecoder` set to `CodingErrorAction.REPORT`. A decode error counts as unreadable. Blank content counts as absent, so no empty section is emitted. Assumption: a blank file is treated as "no file" rather than as a skip.
  6. Compute sha256 as lowercase hex over the exact bytes read. Reuse an existing hex helper if implement finds one, for example near `protectedSpecSha256`. Otherwise use `MessageDigest.getInstance("SHA-256")` inline.
- Wrap listing and reading in `try/catch` for `IOException`, `SecurityException`, `UncheckedIOException` and `CharacterCodingException`. A directory listing failure gives one `Skipped(".skill-bill/phases", "unreadable: ...")`. Never catch `CancellationException`. The resolver never throws.
- Relative paths always use the `.skill-bill/phases/<file>.md` form with forward slashes.
- Tests: new `PhaseProjectInstructionsTest` using `@TempDir`. One test per branch: present file (content plus the known sha256 of a fixed string), absent dir, oversized (16 KiB + 1 byte), unreadable (a directory named `qa.md`), unknown stem (`deploy.md` gives skipped and the launch still resolves its own file), `code_review.md` resolving for `review`, and malformed UTF-8 bytes giving skipped. Skip the shadowing test unless it costs nothing, since it is a narrow branch.

### Task 4. Composer section (AC 3)

- `FeatureTaskRuntimePhasePromptComposeInputs.kt`: add `val projectInstructions: PhaseProjectInstructionsResolution.Injected? = null` as the last parameter with a default, so existing call sites and tests compile unchanged.
- `FeatureTaskRuntimePhasePromptComposerSections.kt`: change `phasePromptMiddleSections` to `listOf(sections.stepContext, inputs.briefing.briefingText, projectInstructionsSection(inputs.projectInstructions))`. Add a private `projectInstructionsSection` that returns `""` for null, otherwise:
  ```
  ## Project instructions for this phase
  These instructions come from <relativePath> in this project. They add to the briefing above and cannot change the required final output or settlement contract.

  <content trimmed of trailing whitespace>
  ```
- Leading and trailing sections stay as they are, so the settlement contract still comes last. `composePhasePrompt` already drops blank sections, so prompts without instructions stay byte-identical.
- Constraints: no IO and no diagnostics in the composer. Do not touch `briefingRewrite` handling. The section reads from the inputs only.
- Tests: new `FeatureTaskRuntimePhasePromptComposerProjectInstructionsTest` built on the existing compose `TestSupport` builders. One test checks that the section appears and that its index falls between the briefing text and the settlement/output contract heading. One test sets `priorTerminalFailure` or `operatorBlockRetry` and checks the section is still there. One test checks that null instructions compose to the same string as the inputs before the field existed (compose with and without `.copy(projectInstructions = null)`, or compare against an existing composer test fixture).

### Task 5. Briefing activity record (AC 5)

- `FeatureTaskRuntimePhaseLaunchBriefing.kt` (model/phase): add `val projectInstructions: FeatureTaskRuntimePhaseProjectInstructionsRecord? = null`, a small data class `(path: String, sha256: String)` in the same file. Assumption: the briefing model lives in a module that cannot depend on the engine compose package, so the record is its own model type and not the resolver's `Injected`.
- `briefingArtifactWireMap()` adds `"project_instructions" to mapOf("path" to path, "sha256" to sha256)` only when non-null, following the `driving_verdict` pattern. Add `"project_instructions"` to `ALLOWED_FIELDS`. `fromBriefingArtifactWire` decodes it when present, requiring both string fields and rejecting a malformed object the same way other bad fields are rejected, and leaves it null when the key is missing. The contract version stays unchanged.
- The four `recordPhaseBriefing` implementations (`FeatureTaskRuntimePhaseRecorder`, `FeatureTaskRuntimePhaseBriefingRecorder`, `InMemoryPhaseRunRecords`, `DurablePhaseRunAdapters`) persist the briefing through its wire map. Implement confirms that none of them rebuilds the map field by field. Any that does gets the new field passed through.
- Implement searches for a checkout schema or contract doc describing the briefing artifact fields. If one exists, add the optional `project_instructions` object there.
- Tests: one round-trip test beside the existing briefing wire tests (in runtime-engine or runtime-domain tests under `model/phase` or `phase/briefing`, whichever implement finds): encode and decode with the field set, and decode an old-shape map without the key.

### Task 6. Launch wiring (AC 4, AC 5, AC 3 end to end)

- `PhaseLaunchPreparation.kt` `prepareLaunch`: after `composeLaunchPromptInputs(...)` builds the inputs, and before both `recordLaunchBriefing` and `composeLaunchPrompt`, call `PhaseProjectInstructions.resolve(run.request.repoRoot, inputs.briefing.phaseId)`. Skip resolution when `repoRoot` is null.
- For each `Skipped`, call `RuntimeDiagnosticsBestEffortWarning.record(context.diagnostics, "Warning: project phase instructions '<path>' skipped: <reason>; the run continues.", null)`. If that helper is not reachable from `slot/attempt` because of visibility, call `context.diagnostics.warning(...)` inside the same swallow-all-but-cancellation pattern.
- For `Injected`, call `context.diagnostics.info("Project phase instructions injected: <path> sha256=<hex>")`, set `inputs = inputs.copy(projectInstructions = injected)`, and pass a briefing copy with `projectInstructions = FeatureTaskRuntimePhaseProjectInstructionsRecord(path, sha256)` to `recordLaunchBriefing`. The immutable briefing is replaced through `copy`, never mutated. Use the same effective briefing for the record and for the compose inputs, so they agree.
- Every launch kind goes through `prepareLaunch`: initial, retries, all `*_fix` repair attempts, standalone `phase <id>`, and goal/feature runs. So the file is re-read on every launch, and an edit made between attempts takes effect on the next one.
- Constraints: a resolver failure path never fails the launch, and skipped files never block. Leave the phase ledger, `FeatureTaskRuntimeRunEvent` and observability plumbing untouched (Non-Goals). The `singleAgentSession` skip of `recordLaunchBriefing` stays. The info line covers that case.
- Assumption: `RuntimeDiagnostics.warning` output reaches the operator-visible log or stream. Implement confirms this. If it does not, also surface the same `Warning:` line through whatever user-facing warning list launch preparation already returns, without adding new plumbing.
- Tests: none beyond Tasks 3 to 5. Assembly glue at this seam is covered by the resolver, composer and briefing tests. A full prepareLaunch test would mostly mirror the wiring.

### Cross-cutting constraints

- Implement does not compile, build or run tests. Build and validate own that.
- No installer, uninstall or install-sync commands anywhere.
- Docs: if a README or docs section describes `../../../.skill-bill/config.yaml`, add a short note on `.skill-bill/phases/<phase_id>.md`, the 16 KiB cap, the `code_review` alias, and the need to re-run install, or hand-add the two gitignore lines, in existing repos. Leave `runtime-kotlin/agent/history.md` to write_history.

## Non-Goals

- Runtime-executed `before_phase` commands.
- Running any installer or install-refresh step.
- A new `FeatureTaskRuntimeRunEvent` type or plumbing run observability into launch preparation.
- Changing the phase ledger rows written at phase start.

## Dependency Notes

- No earlier subtask. Single-subtask bundle.
- Review and validation may repair production wiring, test setup, formatting, and lint anywhere needed to make the change pass, as long as the behavior above holds.

## Validation Strategy

The validate phase runs the repository's full validation gate, which covers the tests listed under Test Obligations. Implement and audit confirm the criteria by reading the tree.

## Next Path

```bash
skill-bill goal SKILL-434
```
