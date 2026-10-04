# Boundary History — runtime-kotlin/runtime-infra

## [2026-10-03] One-shot process success ignores leftover descendants
Areas: runtime-infra/host process
- A client exit of 0 stays 0 when an owned descendant is still alive. The runner no longer waits on that descendant or records a read failure for it. Cleanup still destroys the captured tree.
- A lifetime test checks that a zero exit with a leftover child publishes zero and that cleanup reaps the child. The test fails when the child pid file was never written.
- Pattern: one-shot success is the client's exit code. Killing leftover children is teardown.
- gradle.properties keeps the previous daemon and compiler settings. A one-second idle timeout and in-process Kotlin compilation would apply to every Gradle invocation, and this runner no longer waits on those children. Setting the daemon off did not stop Gradle 9.3 from starting one. An older installed runner that still waits can still false-fail until it includes this change.
- Reusable: BoundedExternalProcessRunner stays the single one-shot process owner for gates, git, and install.
- Limit: a leaked child and an intentional daemon are not distinguished.
Feature flag: N/A
Acceptance criteria: n/a, outside the SKILL-399 shell-content criteria

## [2026-09-28] SKILL-383 subtask 1 — re-parent sidecars and the quality-check route to skill-bill
Areas: runtime-infra/skills (install plan, platform-pack loader, review structure validator, scaffold renderers, testFixtures), runtime-infra/workflow review preflight tests, runtime-domain install policy, runtime-application InstallService, platform-packs code-review content, skills/bill-code-review-inline, skills/bill-code-check, orchestration shell contract, docs
- All 96 platform-pack code-review skills and bill-code-review-inline now declare `internal-for: skill-bill`, so pack specialists, the inline worker and quality-check overrides install as sidecars of the installed skill-bill directory rather than bill-code-review or bill-code-check.
- One constant, PACK_SIDECAR_PARENT_SKILL = "skill-bill" in runtime-domain skillbill.install.policy, is the only place the parent is named. The install gate (InstallService, InstallPlanBuilder, InstallPlanPolicyResolution), the shell-content loader, the review structure validator, the scaffold renderers and the conforming-pack test fixture all read it. reusable
- The review fallback pack is selected only when that sidecar parent is a base skill in the install set. Install tests now seed skill-bill as a base skill wherever validation needs the parent.
- Deleted QualityCheckRoute/routeQualityCheck and QualityCheckRoutingTest because nothing in production called them. The MCP quality_check_* routed_skill label stays bill-code-check.
- AuthoringRenderOutput renders agent add-on pointers on skill-bill itself but not on its sidecars.
- Known limitation: pack load does not validate declared quality-check files (the SKILL-360 decision), so the skill-bill parent rule for quality-check overrides is enforced only through validateGovernedSkill. Subtask 2 deletes the old bill-code-review and bill-code-check parents.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-09-25] SKILL-376 subtask 1 — process ownership, single owners, and adapter seams
Areas: runtime-infra/{host,launcher,workflow,sqlite,skills,contracts}, runtime-core architecture guards, runtime-application and runtime-engine test edges, runtime-kotlin documentation
- Split process ownership by lifetime: runtime-infra/host owns one-shot execution (BoundedExternalProcessRunner, BoundedExternalProcessOutput, InstallerProcessAdapter, GateJvmResolver, GitTrackedFiles) and runtime-infra/launcher owns long-running agent processes; git invocation and the PR-check runner delegate to host while keeping git result semantics (trimmed output, exit -1 on timeout, IOException as readFailure). reusable
- Removed the bespoke git session types and the duplicate accounting bounded-payload encoders; one serializer now produces persisted accounting bytes, pinned by a baseline fixture owned by the sqlite module.
- Pattern: one construction path per request type — AgentRunProcessRequest is built only from its six field groups, with the flat DSL demoted to launcher test sources; forwarding getters, the AgentRunAdapter indirection, and three typealiases are gone.
- Ambient time removed from runtime-infra production sources (injected Clock instead of System.currentTimeMillis), with the ambient-clock architecture guard extended to catch regressions. reusable
- Repo validation now reports a named issue per failed discovery source instead of silently returning an empty list, across all three validation sites.
- Six build files carry only the dependency edges their sources use; contracts and skills dropped their :runtime-infra:workflow edge and sqlite dropped its :runtime-application edge.
- Known limitation: three contracts tests and four skills install cases still exercise FileSystemReviewNativeAgentPreflight through module-internal support, so those test relocations wait for a later subtask.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-09-18] SKILL-354 subtask 2 — split runtime infrastructure by ownership
Areas: runtime-infra/{host,contracts,skills,launcher,workflow}, runtime-core architecture, runtime-kotlin documentation
- Replaced the monolithic runtime-infra/fs module with five ownership-aligned modules, preserving production and test coverage while updating package, resource, Gradle, fixture, and architecture boundaries.
- Pattern: use measured import and dependency censuses to keep module edges minimal; runtime-core retains only its seven implementation edges and skills package order is enforced.
- Governed resource copies, per-module baselines, visibility, public-surface checks, and documentation now track the split; reusable module-catalog and census guards preserve the boundary.
- Known limitation: historical runtime-infra/fs mentions may remain in decision prose where they describe superseded context.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-09-18] SKILL-353 subtask 3 — correct documentation, settle substance report, move adapter tests
Areas: runtime-infra-fs/scaffold and install, runtime-infra-fs test boundaries, runtime-engine featuretask tests, runtime-application workflow tests, runtime-kotlin architecture
- Architecture guidance now describes the typed ScaffoldGateway boundary and inventories adapter-internal raw maps under scaffold; the documentation regression guard owns the live claim.
- The unused platformPackSubstanceReport Gradle task and report entry point were removed; the audit implementation now lives under platformpack/substanceaudit and validation remains its consumer. reusable
- install/support was dissolved into plan, apply, and rendering owners; engine- and application-dependent tests moved to the module whose behavior they exercise, removing the engine test edge and friendPaths entry.
- Pattern: record ownership at the boundary while preserving typed ports and existing validation consumers.
- Known limitation: adapter-internal scaffold raw maps remain documented rather than converted to typed models.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-09-17] SKILL-353 subtask 2 — one owner per process, agent, environment, and primitive
Areas: runtime-infra-fs process/install/nativeagent/launcher/scaffold/skillremove, runtime-domain/install, runtime-ports/system, runtime-core architecture
- Bounded external-process execution, teardown, and capture settlement now have one shared owner across git, gh, validation, review, and installer seams.
- Supported agents, embedded native-agent names, host environment resolution, and agent-home/config paths now use canonical declarations and injected facts instead of duplicated literals or ambient reads.
- Atomic writes and moves, directory replacement, SHA-256 digests, and path containment route through shared filesystem primitives; install, scaffold, removal, and evidence rollback callers reuse them. reusable
- Known limitation: git and installer lifetime behavior retain dedicated sibling coverage while sharing the bounded process ownership model.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-09-17] SKILL-353 subtask 1 — one schema loader, typed failures, and recorded fallbacks
Areas: runtime-infra-fs/contracts, runtime-infra-fs validators and install seams, runtime-core validator bindings, runtime-contracts errors
- One classpath schema loader now owns schema compilation, identity, and contract-version checks; validators retain family-specific error shaping and ordered paths/values. reusable
- Fifteen forwarding adapters were removed and validators are bound directly; working-directory schema walks and duplicate loader state are gone.
- Decode seams raise typed failures, while remaining fallback sites are classified as typed errors, recorded degradation, or documented absence.
- Known limitation: the loader and a few validators still have overlapping schema caches pending architecture-gate confirmation.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-09-16] SKILL-248 subtask 3 — Validate recovery ownership and simplify persistence adapters
Areas: runtime-infra-fs/decomposition journal and contracts, runtime-infra-sqlite/goalrunner and readiness, runtime-contracts, runtime-core architecture
- Journal recovery validates the versioned envelope, transaction-owned paths, symlink/traversal containment, unique entries, and staged or already-applied digests before moves or cleanup; invalid records retain evidence and raise typed actionable failures.
- Canonical journal schema, owning wire keys/version, classpath resource copy, and parity tests keep the persisted contract and packaged resource aligned; historical 0.1 compatibility remains explicit.
- WorkflowGoalRunnerProgressBridge and duplicate private progress forwarding interfaces were removed; existing progress/ledger contracts retain transaction ownership. Database readiness reuses observed identity values for cache decisions while preserving locked recheck and recovery. reusable
- Known limitation: journal recovery remains roll-forward, not a filesystem transaction; corrupt or unsupported records require operator recovery from retained staging evidence.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-09-16] SKILL-248 subtask 2 — Bound git process lifetime
Areas: runtime-infra-fs/GitProcessCommands, GitProcessInvocation, GitProcessLifetimeBehaviorTest
- `invokeGitProcess` owns git child registration, concurrent stdin/stdout I/O, one operation deadline from `gitTimeoutSeconds`, and a finite cleanup budget for drain join and hook-descendant teardown via `ProcessHandle`.
- Interruption preserves `InterruptedException`, destroys only owned processes, and never leaves a live drain worker after cleanup. Unsettled stdout after the deadline surfaces as `readFailure` or timeout, not success with partial capture.
- `GitProcessLifetimeBehaviorTest` covers pipe backpressure, inherited stdout handles, timeout, interruption, and ordinary status; journal recovery probes (F-003) stay in subtask 3.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-09-15] SKILL-247 subtask 4 — Consolidate governed resource copying in runtime-infra-fs
Areas: runtime-infra-fs Gradle resource wiring
- `runtime-infra-fs/build.gradle.kts` now declares each governed copy once via `GovernedResourceCopy` and `registerGovernedCopy`, with main/test `processResources` dependencies driven from the same registration list (main-only: rejected-output diagnostic and producer-output evidence schemas).
- Golden manifest at `src/test/resources/governed-resource-manifest-main.json` locks packaged resource paths and SHA-256 hashes from the pre-refactor tree; `GovernedResourceCopyParityTest` guards regressions. (SKILL-368 deleted both; governed-copy behavior is now covered by build-logic tests against a synthetic project, and the main/test `processResources` split is gone because the generated root only ever joined the main source set.)
- Net line count for `build.gradle.kts`: 914 → 410 (−504).
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-09-14] SKILL-244 subtask 1 — Gate JVM resolved through the shared Java guard
Areas: runtime-infra-fs/jvm, runtime-infra-fs/validation, runtime-infra-fs/launcher/process
- `skill-bill-java-guard.sh` now ships as a runtime-infra-fs classpath resource, so the resolution rule exists at gate time and not only during install/uninstall. (SKILL-368 moved the single authored copy into this module's `src/main/resources/skillbill/infrastructure/host/jvm/` and deleted the `copyJavaGuard` governed entry.)
- New `skillbill/infrastructure/fs/jvm` package (`GateJvmResolver`, `GateJvmEnvironmentKeys`, `GateJvmResolutionErrors`) evaluates that guard: `SKILL_BILL_JAVA_HOME`, then an inherited `JAVA_HOME` accepted by `skill_bill_java_home_ok`, then a qualifying PATH `java`, then the guard's scan. reusable
- Both gate launch surfaces resolve through it: `FileSystemValidationGateRunner.applyResolvedGateJvm` before `builder.start()`, and `JvmAgentRunProcessLaunchEnvironment.configureLaunchEnvironment` (now three-arg) for the agent-run build gate that inherits the parent environment.
- The runtime's own jlink image is pruned from the gate PATH and an image-rooted `JAVA_HOME` is dropped before the guard decides, so the runtime keeps `JAVA_HOME="$APP_HOME"` for itself without leaking it into children.
- Pattern: exactly one resolution rule. Kotlin shells out to the guard; no second acceptance or scan implementation lives beside it.
- An unresolved disposition raises `GateJvmUnresolvedException` naming the rejected candidate and the required major version, and produces no `ValidationGateFinding`, so a JVM startup failure never reaches the agent as a build finding.
- `Jvm` is registered first in `infraFsAreaLayerOrder`/`infraFsAreaSourceDirs` so `verifyInfraFsAreaCompile`'s isolated launcher area can resolve the new package.
- Limitation: the guard must reach `~/.skill-bill/runtime/`, so `./install.sh` must be rerun before the fix is observable end to end. Two tests sit at narrower seams (resolver sanitation against an injected image root; `applyResolvedGateJvm`) because any host running Gradle always has a scannable JDK.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-14] SKILL-239 subtask 1 — process lifetime
Areas: runtime-infra-fs/launcher/process, runtime-infra-fs process tests
- `ProcessRunLifetime` now owns process termination, drain settlement or abandonment, stream closure, registry removal, and review-endpoint closure on every runner exit.
- Frozen drain release snapshots prevent incomplete output or digest mutation from being published as settled evidence; incomplete capture is explicit.
- Probe and stdin seam degradation uses a bounded recorder that does not recurse through a failed output sink; wait interruption preserves the interrupt signal. reusable
- Pattern: inject agent behavior as request strategies while keeping cleanup in one `try/finally` ownership scope. reusable
- Known limitation: provider selection, idle-policy semantics, telemetry transport, and coroutine migration remain outside this boundary.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-29] SKILL-219 subtask 1 — Discrete Gradle gate findings in collect-all parsing
Areas: runtime-infra-fs/validation
- COLLECT_ALL parsing now turns `:projectHealth` `incorrectConfiguration` advice, `:architectureCheck` forbidden project-dependency lines, and leftover `Execution failed for task ':…'` headers into separate `ValidationGateFinding` rows instead of one `unparseable_gate_failure` blob.
- Pattern: union compiler + artifacts first, then the Gradle-specific parsers, then residual task headers. `coveredGradleTaskKeys` skips a header when a finer parser already emitted a row for that module/task (compiler maps to `compileKotlin`). reusable
- `finalizeFindings` is unchanged: `unparseable_gate_failure` still only when the run failed and every parser returned empty. Identity stays `module|ruleOrTestId|message|location`.
- Limitation: subtask 2 still owns the briefing-only triage turn when parsing still yields only `unparseable_gate_failure`. Coordinator and repair-turn limits were not changed.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-26] Git commit and push wait for hooks instead of dying at 30s
Areas: runtime-infra-fs/GitProcessSupport
- `git commit` and `git push` wait 10 minutes; every other git call stays at 30s.
- A consumer-repo pre-commit hook that starts a cold Gradle daemon (`./gradlew ktfmtFormat`) can finish instead of being killed mid-run.
- Timeout errors name the budget that was actually used.
Feature flag: N/A
Acceptance criteria: N/A (defect fix)

## [2026-08-20] Output-gate failures block on the first invalid envelope
Areas: runtime-application/featuretask, runtime-infra-fs/contracts/workflow
- Programmatic JSON extract-and-shape-repair still runs on the existing capture.
- A remaining schema or malformed failure blocks immediately; there is no salvage relaunch.
Feature flag: N/A
Acceptance criteria: 1/1 implemented

## [2026-08-20] Phase JSON walk takes a shape-matching envelope and caps gate retries at two
Areas: runtime-infra-fs/contracts/workflow, runtime-application/featuretask
- Phase output is scanned for JSON objects; the one matching the phase's expected fields is kept and the rest (stray closers, trailing prose) is ignored.
- Missing required fields that already exist in the capture (premature `}` before `verdict`, nested `verdict` under `produced_outputs`) are restored on the existing payload.
- If programmatic repair still cannot accept the capture, one salvage launch receives the original body plus the expected shape; the result is extracted and validated the same way, then a second failure blocks.
Feature flag: N/A
Acceptance criteria: 1/1 implemented

## [2026-08-20] Keep a complete phase envelope when surrounding prose has a stray closer
Areas: runtime-infra-fs/contracts/workflow, phase-output structural repair
- Jackson still parses phase output; when one complete envelope is selected, a bare `}` or `]` in surrounding prose is dropped instead of rejecting as `NO_REPAIR_CANDIDATE`.
- The phase does not relaunch: the existing capture is repaired to the selected envelope. Two complete conflicting envelopes still reject.
- Backtick-quoted braces stay commentary and continue to accept unchanged.
Feature flag: N/A
Acceptance criteria: 1/1 implemented

## [2026-08-18] SKILL-198 subtask 1 — pack quality-check repair window engraved in contracts
Areas: orchestration/contracts, platform-packs/*/quality-check, runtime-infra-fs/scaffold/{rendering,validation}, tests
- All eight maintained pack quality-check `content.md` files lost per-fix "re-run targeted checks" language and gained a Repair Window section matching `bill-code-check`; scaffold `qualityCheckContent` starter emits the same block for new packs.
- `full_gate_command` schema description no longer calls collect-all argv an "intermediate repair-cycle" run — discovery/collect-all only; verification after repair uses cache-bypass argv. Shell contract stays 1.5.
- `QualityCheckRepairWindowConformanceTest` denylists per-fix rerun phrases across every pack sidecar; platform pack snapshot tests updated for the new sections.
- Limitation: runtime command interception during repair is subtask 2; this subtask is agent-facing text and schema wording only.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-18] Install reconcile: upstream always wins
Areas: runtime-infra-fs/install/reconcile, runtime-domain/install/model, runtime-ports/install/reconcile, runtime-application/install, runtime-cli, runtime-contracts
- `SkillReconciliationOutcome` collapsed from five cases to four: `Adopt` (upstream exists and differs from local — install it), `Unchanged` (upstream hash == local hash — no file op), `Prune` (no upstream counterpart under `skills/` or `platform-packs/` — DELETE it), `LocallyAuthored` (no upstream counterpart under `agent-addons/` — user-owned, never written, never deleted). `KeepLocal`, `Conflict`, and `NewUpstream` are gone, as are `ReconciliationPlan.conflicts` / `hasConflicts` and `ReconciliationApplyRefusedError`.
- `skills/` and `platform-packs/` mirror the source: a skill removed upstream is deleted on the next install, and `adoptPlatformPackNonSkillFiles` also deletes live non-skill pack files absent upstream then drops the emptied directories, so a whole removed pack disappears rather than leaving an orphan `platform.yaml` that pack discovery would still load. The prefix constants moved to the policy file (`SKILLS_PREFIX` / `PLATFORM_PACKS_PREFIX` / `AGENT_ADDONS_PREFIX`) because classification now needs the category, not just apply. reusable
- Prune safety: `guardPruneAgainstEmptyUpstream` loud-fails when the plan carries prune outcomes AND the upstream enumeration returned ZERO skills. A mis-staged or truncated candidate would otherwise classify the entire live install as prune and delete it irrecoverably — there is no backup, since `replaceSkillDirAtomically` drops its rename-aside copy in `finally`. Any irreversible delete driven by a diff against a staged tree needs this shape of guard. reusable
- The baseline manifest no longer influences classification — it is recorded output only. It is still written on every apply because `InstalledWorkspaceBaselineStatusPort` is an independent consumer: it reports which installed skills the user has edited since the last install, and that answer needs a per-skill baseline hash.
- `ReconciliationPlan.baselineRefreshPaths` (paths) became `baselineOverlay` (path -> upstream hash) plus `prunedPaths`. `InstallService.refreshBaselineFromPlan` applies both (`withEntries(...).withoutEntries(...)`) instead of re-deriving the same `when`, so a pruned skill also drops its baseline entry rather than lingering as a phantom 'modified' row. reusable
- Test pitfall: an empty baseline no longer forces an install. Two identically-seeded repo roots classify as `Unchanged`, so an apply-idempotence test must start from an EMPTY local tree to get a non-empty first `installedPaths` — under the old model `BaselineManifest.empty()` alone guaranteed `NewUpstream` for everything.
Feature flag: N/A
Acceptance criteria: N/A

## [2026-08-17] SKILL-190 subtask 1 — Amend and namespace-scoped checkpoint ref primitives
Areas: runtime-ports/workflow, runtime-infra-fs (GitCheckpointHistoryOperations, GitWorkflowGitOperations)
- New `CheckpointHistoryGitOperations` port: `amendHeadCommit` plus create-or-update, resolve, list-by-prefix, and delete for refs confined to a caller-supplied namespace prefix. No caller behaviour changes yet.
- Amend never stages: it rewrites HEAD from the existing index only, requires HEAD to equal the caller-supplied `expectedOwnedHeadSha`, and fails typed on missing HEAD or empty index instead of creating an empty commit.
- Pattern: capability extension via optional `CheckpointHistoryGitOperationsProvider` on `WorkflowGitOperations`, with extension functions as the call surface. Adapters that don't provide it get a refusing implementation, not a silent "ok" — a fake success would record a checkpoint identity that doesn't exist. reusable
- All git invocation goes through the existing `GitProcessSupport.runGitCommand` seam; no second `ProcessBuilder`, same `WorkflowGitOperationResult` error channel as the older operations.
- Ref writes use `update-ref` semantics (old or new value, never partial); ref names outside the prefix are rejected typed; deleting an absent ref succeeds so an interrupted prune is re-runnable.
- Adapter tests run against real temporary repositories rather than mocks, since the behaviour under test is git's.
- Limitation: nothing consumes these yet — run-loop/checkpoint wiring and the `refs/skill-bill/checkpoints/...` layout land in later subtasks; `resetSoftToCommit` and legacy `stageAll` are untouched.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-15] SKILL-192 subtask 1 — Collect-all gate declaration and complete finding extraction
Areas: orchestration/contracts, platform-packs/{kotlin,kmp}, runtime-infra-fs/validation, runtime-domain/scaffold, runtime-application/featuretask/validation, tests/fixtures/shell_content_contract
- `validation_gate` now requires pack-owned collect-all argv (cache-eligible and cache-bypassing) plus a compiler-diagnostics locator; shell contract pinned at 1.5 with schema and Kotlin together.
- A present gate missing those argv, or with empty/blank tokens, loud-fails at pack load and never resolves to `full_gate_command`.
- `FileSystemValidationGateRunner` COLLECT_ALL mode executes pack argv and unions compiler diagnostics with JUnit (or current findings format) by existing identity; `unparseable_gate_failure` only when the run failed and both sources are empty.
- Pattern: continue-on-failure and cache-bypass tokens live in pack argv; Kotlin never hardcodes `--continue`, `--rerun-tasks`, or `--no-build-cache`. reusable
- Shipped kotlin/kmp packs declare collect-all as `check --continue` and confirmation with that pack's cache-bypass tokens. BUILD_ONLY argv and non-collect-all JUnit parsing stay byte-stable for existing callers.
- Limitation: FULL validate still uses `full_gate_command`; coordinator cycle, prompt, and goal-depth switching are subtask 2. No `agent/` under `platform-packs/`.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-14] SKILL-188 subtask 3 — regression, conformance, and documentation correction
Areas: runtime-kotlin/runtime-infra-fs/nativeagent, runtime-kotlin/runtime-infra-fs/install, runtime-kotlin/runtime-infra-fs/scaffold, docs
- Regression fixtures pin the reported defect: a Harbor-shaped pack with `addon_usage` and no `content.md` link still composes distinctive add-on bytes into every rendered harness, including the external-overlay path, without mutating upstream `content.md`.
- Coverage also pins idempotent re-render, baseline/area then add-on order (entrypoint before companions), declared-plus-linked dedup, loud-fail vocabulary (slug, slot, absolute path), budget fail-without-truncate, and slug/content equivalence across harnesses.
- Pattern: treat Harbor-shaped packs as the composition oracle — assert rendered agents and overlay results, never a `content.md` link as the activation signal. reusable
- `docs/external-addons.md` and the native-agent README now state that `addon_usage` alone composes and drop any implication that editing an upstream pack `content.md` activates an add-on.
- Limitation: already-installed agents still refresh only on the next render/install. This subtask proved and documented composition; it did not change the seam.
Feature flag: N/A
Acceptance criteria: 13/13 implemented

## [2026-08-14] SKILL-188 subtask 2 — harness coverage and truthful slug projection
Areas: runtime-kotlin/runtime-infra-fs/nativeagent/{composition,rendering,validation}, runtime-kotlin/runtime-infra-fs/install/apply, runtime-kotlin/runtime-infra-fs/infrastructure/fs, runtime-kotlin/runtime-domain/review/plan, platform-packs/kmp
- Composed add-on content now rides the same governed body into every NativeAgentProvider render and install target. Harness files still differ only in provider format; add-on blocks are identical per area.
- `ReviewAddonSelectionPolicy` is the single slug set for launch-plan `addOns`, in-render composition, inline rubric append, and validation. A reported slug is a composed slug; a declared-but-uncomposed target still loud-fails with slug, slot, and absolute path.
- Pattern: `enforceAddonProjectionParity` is a runtime check, not a docs convention — compose, launch-plan projection, and `validateRepoNativeAgents` share it. reusable
- Inline review appends the same policy-selected add-on files, so delegated and inline tiers get equivalent rubrics for the same area without telling any orchestrator to read a sibling sidecar.
- Limitation: already-installed agents refresh only on the next render/install. Regression and docs pinning remain subtask 3. Pack names stay out of shell/runtime code.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-14] SKILL-188 subtask 1 — compose addon_usage into rendered native agents
Areas: runtime-kotlin/runtime-infra-fs/nativeagent/{composition,rendering}, runtime-kotlin/runtime-contracts/error, orchestration/contracts, platform-packs/ios
- Native-agent rendering now composes add-on `entrypoint` and `companion_pointers` from pack `addon_usage` for the skill-relative directory being rendered. A markdown link in the owning `content.md` is neither required nor the trigger.
- Every target resolves through that directory's `pointers` table; missing, unreadable, or undeclared targets fail with `MissingContentFileError` naming slug, slot, and absolute path. Over-budget output fails with `ComposedNativeAgentBudgetExceededError` and never truncates.
- Pattern: `SidecarInliningSession` claims add-on paths before link rewrite so a file that is both a declared add-on and a link-inlined sidecar appears once, independent of resolution order. reusable
- Stable order is baseline/area body, then add-ons in declared `addon_usage` order (`entrypoint` before companions). Render-evaluable activation is written as a Declared-scope stanza; inherently diff-time conditions still compose and defer to runtime activation.
- Limitation: this seam covers the existing governed-content render path only; per-harness coverage and launch-plan slug reconciliation are subtask 2. Pack slugs stay out of shell/runtime code. No pack `content.md` was edited to activate an add-on.
Feature flag: N/A
Acceptance criteria: 12/12 implemented

## [2026-08-11] Cursor capability projection and spawn-note ordering (review repairs)
Areas: runtime-kotlin/runtime-infra-fs/nativeagent/{composition,rendering}, runtime-kotlin/runtime-infra-fs/scaffold/rendering, runtime-kotlin/runtime-infra-fs tests
- A declared read-only toolset now reaches Cursor as `readonly: true`. Cursor has no `tools` key, so rendering name+description only had silently left every review worker on the host default of every tool the parent can reach — write and recursive-delegation capability the read-only review contract forbids. `declaresReadOnlyToolset` is the projection rule; a toolset holding `Edit`/`Write`/`NotebookEdit`/`Agent` earns no `readonly` claim. reusable: a provider whose capability vocabulary is narrower than the source needs an explicit projection, never a dropped field.
- Claude, Junie, and Cursor share one `renderFrontmatterAgent` envelope and differ only in the capability fields they pass in, so an envelope change cannot reach one provider and skip another. The dead `mode` parameter is gone.
- `yamlNeedsQuoting` now quotes a value ending in `:` and plain-resolvable tokens (`no`, `off`, `~`, numerics); the name pattern permits them, so an unquoted `no` installed as a boolean and stopped matching its file.
- The Codex wave limit renders inside the Codex block instead of after another runtime's paragraph, and Junie gets an explicit "delegated unsupported, use `mode:inline`" paragraph so no installed runtime falls through the runtime-neutral rule and invents a spawn mechanism.
- The rendered Cursor paragraph now splits lane-level from run-level failure: a lane that launches and returns nothing attributable fails that lane; no matching installed agent, or a session that cannot launch by name, stops the run. Its governed phrasing is pinned to `review-delegation/PLAYBOOK.md` by `SubagentSpawnRuntimeNotesTest`, which the renderer cannot read at runtime.
Feature flag: N/A

## [2026-08-11] Cursor agent CLI delegated refusal copy in scaffold spawn notes
Areas: runtime-kotlin/runtime-infra-fs/scaffold/rendering
- `cursorSpawnParagraph` now distinguishes Cursor `agent` CLI (Task built-ins only) from the Cursor IDE agent UI when named specialists are installed but unlaunchable, and tells the operator to re-run `mode:delegated` in the IDE or use `mode:inline` on the CLI.
- Parity/snapshot coverage pins the `agent` CLI harness and IDE agent UI phrases so the warning cannot regress to a generic unavailable line.
Feature flag: N/A

## [2026-08-11] SKILL-182 subtask 2 Cursor native-agent frontmatter vocabulary
Areas: runtime-kotlin/runtime-infra-fs/nativeagent/rendering, runtime-kotlin/runtime-infra-fs/nativeagent tests
- `NativeAgentProvider.Cursor.render` now uses a dedicated `renderCursorAgent` path that emits only `name` and `description` (shared `yamlScalar` quoting), dropping Claude's `tools` key and omitting `model` / `readonly` / `is_background`.
- Claude and Junie still share `renderFrontmatterAgent` and stay byte-identical; Cursor drift is intentional so installed `~/.cursor/agents/` files match Cursor's frontmatter set.
- Pattern: per-provider frontmatter projection over a shared source model — `NativeAgentSource.tools` remains authoritative for consumers that use it; only the Cursor projection changes. reusable
- Tests pin Cursor shape (no `tools:`, no extra keys) and keep Claude/Junie snapshots; Claude/Cursor equality assertion replaced with per-provider assertions.
- Limitation: mapping Skill Bill tools onto Cursor `readonly` / `is_background` is deferred; default model inherit is left implicit (no explicit `model` emit).
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-09] SKILL-174 boundary memory becomes a heading catalog + on-demand body resolve (subtask 2)
Areas: runtime-kotlin/runtime-infra-fs/goalplanning, runtime-kotlin/runtime-ports/goalrunner, runtime-kotlin/runtime-application/goalrunner, runtime-kotlin/runtime-domain/taskruntime, orchestration/contracts, skills/bill-feature-goal
- Discovery no longer ships byte-prefix excerpts of `agent/history.md` / `decisions.md`; it emits a heading-only catalog with stable ids, per-file and total caps, and a deterministic truncation marker.
- New `BoundaryMemoryHeadingParser` (reusable) walks headings for both history and decisions forms, skipping malformed regions without inventing or dropping entries.
- New `GoalPlanningBoundaryBodyResolver` port + FS impl (reusable): preplan picks heading ids, plan gets exactly those bodies; unknown/stale/excluded ids resolve to nothing.
- Exclusion roots centralized in `GoalPlanningRepositoryScope`; pruning applies at any depth and survives symlink canonicalization; platform pack agent trees contribute zero entries.
- Packet VERSION bumped to `0.3` with migrate-by-discard for `0.2`/`0.1` prefix payloads; projection digest gains optional `selected_boundary_headings` (older digests still validate).
- Limitation: heading selection quality depends on the preplan agent; a preplan with no selection field degrades to catalog-only rather than failing the sweep.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-08] Goal planning packet v0.2 drops platform_packs
Areas: runtime-kotlin/runtime-infra-fs/goalplanning, runtime-kotlin/runtime-ports/goalrunner
- `GoalPlanningContext` no longer carries `platformPacks`; discovery returns only `boundaryMemory` and `validationGuidance`.
- Packet key removal lives with the application-side VERSION `0.2` migrate-on-read; this module only stops inventing an empty map.
Feature flag: N/A
Acceptance criteria: N/A (follow-on to SKILL-172 deferred packet cleanup)

## [2026-08-08] SKILL-172 goal-planning context discovery stops buying platform.yaml (subtask 1)
Areas: runtime-kotlin/runtime-infra-fs/goalplanning, runtime-kotlin/runtime-application (GoalPlanningSweep packet validation), .feature-specs/SKILL-172-goal-planning-burst-and-context
- `FileSystemGoalPlanningContextDiscovery` no longer reads `platform-packs/*/platform.yaml`; `platform_packs` stays in the shared context packet as `{}` so `PACKET_FIELDS` / integrity stay resume-compatible without a VERSION bump.
- Discovery priority under `DiscoveryBudget` is load-bearing and documented: boundary_memory (history then decisions, sorted packs) before validation_guidance (`AGENTS.md`); budget exhaustion omits later categories entirely rather than silent argument-order truncation.
- Nine oversized-pack fixture proves boundary memory and AGENTS guidance survive when platform.yaml would previously exhaust the 32KB budget; allowlist/file-count and symlink-escape tests retargeted to history.md.
- Packet fixture with populated `platform_packs` still passes `GoalPlanningSharedContextPacket.validate` at VERSION `0.1`. reusable pattern: keep empty packet keys until a versioned migration removes them.
- Known limitation: `platform_packs` key and `validation_guidance` name remain until deferred versioned packet work; `MAX_DISCOVERY_*` caps unchanged.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-07] SKILL-164 checkpoint-keyed shared review evidence store (subtask 1)
Areas: runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-ports/taskruntime, runtime-kotlin/runtime-domain/workflow/taskruntime/model
- New derive-once seam: `FeatureTaskRuntimeSharedEvidenceResolverPort` resolves shared review evidence keyed solely on `FeatureTaskRuntimeRepositoryCheckpoint.fingerprint` (+ workflow id); a fingerprint hit returns the stored artifact with zero repository traversal.
- `FileSystemFeatureTaskRuntimeSharedEvidenceStore` persists artifacts under the already-ignored repo-local `.skill-bill/` run store, addressed by workflow id + fingerprint; writes stage-then-replace so an interrupted write leaves nothing a later resolve would serve. No new `.gitignore` entry.
- Outcome contract is exhaustive and documented on the port: hit / absent / fingerprint-mismatch / unreadable-or-truncated all fall through to derivation and never fail the run; only a well-formed envelope whose recorded fingerprint contradicts its address loud-fails, via `FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError` naming both fingerprints.
- Pattern followed: port request/derivation DTOs live in `skillbill.ports.taskruntime.model` (public-model-package rule enforced by `RuntimeArchitectureTest`); the deriver returns raw diff bytes and the store owns materialization. reusable seam for any future checkpoint-keyed derived cache.
- Known limitation: scope is per-workflow only — no cross-run or global cache, and fingerprint equality is the sole invalidation concept. Nothing consumes the store yet (no projection, briefing, review-lane, or telemetry wiring); that lands in later subtasks.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-05] SKILL-136 declare KMP persistence/reliability areas and route to them (subtask 3)
Areas: runtime-kotlin/runtime-infra-fs (scaffold tests), platform-packs/kmp, .feature-specs/SKILL-136-android-native-review-specialists
- The `kmp` pack now declares `persistence` and `reliability` itself, so `ReviewLaunchPlanPolicy` resolves them at composition depth 0 and shadows the Kotlin baseline's backend-framework lanes.
- Area focus text for both lanes names Android-native frameworks (Room, SQLDelight, DataStore, WorkManager) instead of backend-JVM ones; the Diff-Signal Routing Table gained matching rows.
- `architecture`, `performance`, `security`, `testing`, `api-contracts` still resolve to `bill-kotlin-code-review-*`; `platform-correctness`, `ui`, `ux-accessibility` stay KMP-declared.
- Added `ComposedReviewLaunchPlanTest` — asserts the full composed KMP resolution map and that a non-kmp pack's composed plan is unchanged. reusable shape for future pack-declaration changes.
- Known limitation: shadowing is whole-area, not per-lane additive; declaring an area replaces the baseline specialist outright.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-05] SKILL-139 drop redundant staged content.md
Areas: runtime-kotlin/runtime-infra-fs/install/staging, docs, orchestration/shell-content-contract, AGENTS.md
- Listed-skill staging no longer copies `content.md` into the installed dir; staged layout is `SKILL.md`, `.content-hash`, pointers, optional `native-agents/` only.
- Install content hash still reads source `content.md`, so authored-body edits still force re-stage; reconcile/intent name sets match the new layout.
- Internal sidecar staging drops any redundant verbatim `content.md`; rendered `<skill-name>.md` wrappers stay full and self-contained.
- Pattern: authored source remains the hash/input contract; generated install output must not re-ship source bodies agents already have inlined in `SKILL.md`. reusable
- Ceremony and docs no longer tell agents to read a sibling staged `content.md` as the body source.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-01] SKILL-153 phase-output structural repair (subtask 2)
Areas: runtime-kotlin/{runtime-application,runtime-contracts,runtime-domain,runtime-infra-fs,runtime-ports}, .feature-specs/SKILL-153-phase-output-structural-repair
- Decomposition manifests now use typed construction, canonical schema/coherence validation, YAML read-back, and atomic persistence; reusable.
- Syntax-only read failures enter a bounded deterministic repair seam: exactly one candidate is reparsed and revalidated, while ambiguity and semantic/type/coherence failures remain typed rejection.
- File-store and preparation-writer seams preserve no-partial-write behavior and redacted repair evidence with digests, format, operation, location, and contract version.
- Tests cover generation/read-back, syntax repair and ambiguity, schema/coherence failures, and atomicity.
- Known limitation: repair is syntactic only; semantic guessing and changes to manifest values, types, dependencies, statuses, or intent remain unsupported.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-01] SKILL-153 phase-output structural repair (subtask 1)
Areas: runtime-kotlin/{runtime-infra-fs,runtime-domain,runtime-contracts}, .feature-specs/SKILL-153-phase-output-structural-repair
- Added typed unchanged/repaired/rejected phase-output results and redacted repair evidence with format, contract version, digests, operation, and source location. reusable
- Structural repair uses bounded JSON/YAML candidates, preserves scalar content, requires exactly one candidate, reparses strictly, then follows the existing schema path. reusable
- Embedded envelopes retain their source bounds so a valid inner payload is not masked by an invalid surrounding response; regression tests cover extra/missing delimiters, strings, ambiguity, and evidence digests.
- Known limitation: YAML repair remains restricted to conservative parser-supported flow structures; semantic, indentation, quoting, anchors, duplicate-key, and block-structure changes stay rejected.
Feature flag: N/A
Acceptance criteria: subtask 1: 8/8 implemented

## [2026-07-27] SKILL-132 Orphan runtime contract asset audit (subtask 3)
Areas: runtime-kotlin/runtime-infra-fs, .feature-specs/SKILL-132-runtime-kotlin-dead-code-sweep
- Audited four bundled contract schemas (execution-identity, worker-ownership, goal-subtask-review-state, review-context) for orphan status; all four reached an `active` disposition, so nothing was removed.
- Producer-consumer-validation traces are recorded in the spec's evidence ledger; absence of file-name references was explicitly rejected as removal evidence.
- Added `FeatureTaskExecutionIdentitySchemaContractVersionTest`, closing the last retained-schema gap in version-parity coverage (pattern: `PlatformPackSchemaContractVersionTest`).
- Reusable: audit shape for contract assets — trace read seams, durable records, migrations, copy tasks, and `jar tf` bundling before proposing deletion; clean-rebuild + jar inspection proves the removal delta.
- Known limitation: removal delta was vacuous this subtask; configuration-cache and migration surfaces were untouched.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-07-27] SKILL-138 Cursor MCP install/uninstall, replay, and smoke tests (subtask 3)
Areas: runtime-kotlin/runtime-{cli,infra-fs}, scripts
- Cursor MCP registration targets `~/.cursor/mcp.json` with standard `mcpServers` merge semantics; register/unregister/config-path branches now cover CURSOR case in `McpJsonConfig` and `McpRegistrationOperations`.
- UninstallCommand extended to remove only managed Cursor skill links, native links, and Skill Bill's MCP entry while preserving user-owned Cursor content.
- Selection replay round-trips Cursor state and malformed input fails loudly through typed validation.
- Install-plan/apply and shell delegation allowlists now include Cursor in canonical order.
- Test coverage: MCP registration operations (idempotency, unrelated-key preservation, malformed-input failure), install plan application, and smoke test expectations all pass with isolated homes.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-07-27] SKILL-138 Cursor native agents, inventory, CLI, and removal (subtask 2)
Areas: runtime-kotlin/runtime-{application,cli,domain,infra-fs,ports,desktop}, orchestration/contracts, docs, AGENTS.md
- NativeAgentProvider.Cursor renders valid YAML-frontmatter Markdown targeting ~/.cursor/agents; provider loops now exhaustively cover Cursor in link/unlink operations, inventory schema, validation preflight, and skill removal execution.
- Added cursor-agents-path, link-cursor-agents, and unlink-cursor-agents CLI commands registered through runtime-surface with continuation guards and platform-aware path resolution.
- Repository validation rejects committed cursor-agents output and packaging excludes it while retaining provider-neutral native-agent sources under native-agents/.
- Desktop removal previews/mappings include Cursor with typed failure handling; uninstall primitives exhaustively cover cursor-agents cleanup.
- Pattern: new native-agent provider addition updates six enums (InstallAgent, NativeAgentProviderId, NativeAgentProvider, NativeAgentLinkProvider, FirstRunSetupAgent, AgentSymlinkProvider) plus link/unlink operations and inventory/preflight coverage. reusable
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-07-22] SKILL-129 native-agent reconciliation and preflight (subtask 4)
Areas: runtime-kotlin/runtime-{application,domain,infra-fs,ports}, platform-packs/{kotlin,kmp}/code-review
- Provider-neutral declarations and flattened launch plans now share one complete logical worker set; undeclared workers, duplicate provider targets, and misleading baseline identities fail before launch.
- Native-agent install stages provider artifacts atomically, verifies logical identity, digest, readability, and current-generation ownership, then reconciles the complete managed-link inventory while preserving unmanaged files.
- Pattern: classify canonical current, obsolete cache, and legacy governed artifact targets through one reusable predicate before replacing or pruning links; never infer ownership from filename alone. reusable
- Delegated review preflight resolves every selected logical worker from current installed staging and returns a typed failure with the repair command instead of falling back to `general-purpose`.
- Reconciliation regressions cover dangling Kotlin/KMP links, stale generations, missing artifacts, duplicate targets, unmanaged collisions, legacy layouts, and every supported native-agent provider.
Feature flag: N/A
Acceptance criteria: subtask 4: 5/5 implemented

## [2026-07-14] SKILL-122 agent add-on delivery and scaffolding
Areas: runtime-infra-fs/{agentaddon,install,scaffold}, runtime-cli/scaffold, runtime-desktop/feature/skillbill, install.sh, uninstall.sh
- Agent-addon delivery discovers validated declarations dynamically and generates deterministic consumer pointers for `bill-feature` and its internal sidecars only in staged output; targets must remain regular repository files and collisions, self-reference, malformed paths, or missing targets loud-fail before promotion.
- `InstallStagingIntentBuilder` is the reusable staging composition seam shared by plan, apply, and direct staging; `InstallContentHash` folds manifests, bodies, and generated pointers into only the affected installed-skill identities. reusable
- Authored-path namespace checks use normalized staging-relative paths, so nested source basenames do not falsely collide with flat generated pointers; generated `agent-addon-*.md` files committed under skills are rejected.
- Normal install and reconciliation now preserve the `agent-addons` source tree, including deterministic empty-tree handling, without changing platform-pack overlays, selection, or source-generation behavior.
- The `agent-addon` payload and CLI/desktop wizard create only `agent-addon.yaml` plus `content.md`; dry-run reports both paths and render, validation, or install failure participates in byte-for-byte scaffold rollback.
- Known limit: local install refresh remains deferred while the feature-task runtime workflow store is active; the runtime guard must not be bypassed.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-07-14] SKILL-122 agent add-on contract
Areas: orchestration/contracts, runtime-contracts, runtime-domain/agentaddon, runtime-infra-fs/{agentaddon,scaffold,validation}, docs
- Agent add-ons are user-owned `agent-addons/<slug>/agent-addon.yaml` plus `content.md`; the Draft 2020-12 schema pins contract 1.0, stays strict, documents cross-field coherence checks, and is copied onto the runtime classpath with configuration-cache-safe task inputs and an execution-time existence guard. reusable
- `AgentAddonSourceLoader` discovers declarations in deterministic slug order, treats a missing or empty root as valid, and exposes a required lookup that raises `MissingAgentAddonDeclarationError` only when a caller demands an absent declaration. reusable
- Parse and discovery failures surface through typed `InvalidAgentAddonSchemaError` variants, including malformed roots, schema drift, slug/source mismatch, content-file violations, duplicate identities, descriptions, consumers, and agents.
- Agent ids are validated through the existing `InstallAgent` registry; contract 1.0 intentionally accepts only `bill-feature` as a consumer.
- Repository validation and generated-artifact guards include agent add-ons while leaving skills, platform packs, and pack add-ons unchanged when the root is absent.
- Known limit: this contract establishes declaration/discovery/validation only; applying agent add-ons during feature execution is follow-up behavior.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-07-12] SKILL-118 unified use license
Areas: LICENSE and policy docs, GitHub release workflow, scripts, runtime-kotlin/{build-logic,runtime-application,runtime-cli,runtime-ports,runtime-infra-fs,runtime-desktop}
- `LicenseRef-Skill-Bill-Use-1.0` governs v0.1.2 prereleases distributed with it, v0.1.2, and later releases: lawful commercial use is free before stable v1.0.0; afterwards personal and qualifying open-source-project use remain free while other commercial use requires a purchased agreement. reusable
- Documented skills, packs, and orchestration materials may be customized for permitted internal use; executable runtime modification and public redistribution remain outside the public grant.
- Release refs are canonical `v`-prefixed SemVer. Stable and post-v1 releases require holder approval tied to the exact normalized governing-license hash; placeholders or alternate successor text do not pass. reusable
- Artifact verification compares staged and packaged license bytes for CLI, MCP, skills, and desktop artifacts; test positive, missing-license, duplicate-path, and byte-drift cases on every host-native extractor. reusable
- Draft releases target the triggering commit, resume only a matching draft, verify existing asset bytes, upload only missing assets, and publish last; release retries must preserve that order. reusable
- Known release gate: Braian Gapur must explicitly approve the final root-LICENSE hashes before `v0.1.2` and stable `v1.0.0`; pending records are intentional and must not be filled by automation.
Feature flag: N/A
Acceptance criteria: 8/8 review findings fixed; original 12/12 implemented

## [2026-07-06] SKILL-107 feature add-on usage schema
Areas: orchestration/contracts, platform-packs/{go,ios,kotlin,kmp,php,python}, runtime-domain/scaffold, runtime-infra-fs/{scaffold,install,validation}
- Platform-pack shell contract bumped to 1.2 and `feature_addon_usage` became a manifest-backed, runtime-anchored field; schema/Kotlin parity and 1.1 rejection fixtures must move together. reusable
- Feature-task Android add-ons moved out of `orchestration/skill-classes/feature-task.yaml` and into the KMP pack manifest, so routed support pointers now compose class ceremony pointers plus selected platform feature add-ons. reusable
- Loader/validator rule: consuming a feature add-on pointer without a matching `feature_addon_usage.feature-task` declaration, or pointing at a missing add-on file, loud-fails through manifest/schema validation.
- Install staging now carries selected-platform context into support-pointer generation; keep apply-time and preview-time staging paths aligned when adding future routed pointer families.
- Known limit: `./install.sh` refused during runtime goal continuation, so post-goal install sync is still needed before relying on the user-level `skill-bill` launcher for contract 1.2 validation.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-07-05] SKILL-104 internal review packs (subtask 2: review-pack migration and call-site rewrite)
Areas: platform-packs/{ios,kotlin,kmp,python}/code-review (34 content.md), orchestration/review-delegation
- Flattened all 34 review-pack skills to `internal-for: bill-code-review` (PD2): 4 stack entries + 30 specialists, one-line frontmatter addition each, nothing else changed in those blocks.
- Call-site rewrite to the SKILL-102 sibling-sidecar file-read contract (PD5): `orchestration/review-delegation/PLAYBOOK.md` (Claude line 43, Codex line 53) and the specialist-read lines in `bill-kotlin-code-review` (Step 6) + `bill-kmp-code-review` (Step 4) now read `<name>.md` co-located with `bill-code-review`'s `SKILL.md` with "Do not use the Skill tool — internal skill, not listed". Wording contract copied verbatim from `skills/bill-feature/content.md` / `skills/bill-feature-task/content.md`. reusable
- Inventory sweep by skill NAME (not phrasing — SKILL-102's post-merge miss lesson): source dirs + a from-source staging install (all packs). Native-agent spawn references (`subagent_type`, `@name`) are PD6 identity strings, left untouched; specialist-selection tables, `routed_skill` contract, telemetry values byte-unchanged (PD4). Copilot delegation line 32 left unchanged (refers to parent-forwarded rendered runtime instructions, not standalone resolution). reusable
- KMP baseline composition renderer text UNCHANGED: `renderBaselineLayerLabel` emits `kotlin/bill-kotlin-code-review` as an identity string (`platform/skill`), not a path or Skill-tool call — Step 2.4 criterion 5 says leave identity-only text alone; `PlatformPackCompositionTest` already covers it.
- Rendered-output sweep confirmed all 34 sidecars co-located in `bill-code-review`'s staged directory alongside `SKILL.md`; no Skill-tool/slash-command/standalone-skills-dir resolution of the 34 in staged wrappers or support pointers.
- Validation: `skill-bill validate` PASS (0 issues, via rebuilt runtime-cli with subtask-1 mechanism); `agnix --strict` PASS; `scripts/validate_agent_configs` PASS (57 skills, 38 native agents); `./gradlew check` — only the pre-existing `CliFeatureTaskRuntimeRuntimeTest > feature-task-runtime run requires issue key and spec path` failure (environmental zcode prose-only refusal; markdown-only change cannot affect it).
Feature flag: N/A
Acceptance criteria: subtask-2 7/7 implemented

## [2026-07-05] SKILL-104 internal review packs (subtask 1: pack-aware internal-skill mechanism)
Areas: runtime-infra-fs/scaffold/authoring, runtime-infra-fs/install/plan, runtime-infra-fs/install/staging, runtime-infra-fs/install/apply, runtime-infra-fs/scaffold/runtime, runtime-domain/install/policy, runtime-domain/install/model, runtime-contracts/error
- `InternalSkillClassification.kt` base-skill-only violation removed (PD1); the `isBaseSkill` flag now feeds only the parent-side rule. Every preserved rule keeps its exact message; tests add pack-skill variants for blank/self/unknown/pack-parent/chained.
- `discoverInternalSidecarTargets` gains a `selectedPackSkills` param (PD3): union of skills-root scan + selected pack skills declaring `internal-for == parent`. Renders through the SAME `discoverTargets`+`renderWrapper` path (full governed wrapper, SKILL-102 PD6 parity). Three callers thread it: `InstallPlanBuilder.buildStagingIntent`, `InstallApplyStaging.materializeValidatedPlannedStaging`, `InstallStaging.stageInstalledSkill`. reusable
- Inertness: with no opted-in pack skill the new arg is empty and behavior is byte-identical (test + real-repo `skill-bill validate` 0 issues).
- PD8: new `MissingBaselinePlatformSelectionError` (carries selecting/required slugs + manifest path) + plan-time guard in `InstallPlanPolicy.buildPlanDraft`. ALL selection is trivially safe; packs without baseline layers unaffected.
- Validate-seam parity: `validateInternalSidecarReferences` and `internalSkillNames` (README exclusion) now scan the union of base+pack skill files; `validateInternalSidecarCollisions` already unioned both.
- detekt: `InstallPlanPolicy` and `stageInstalledSkill` crossed thresholds (TooManyFunctions / LongParameterList+LongMethod) — a `@Suppress` with one-line rationale was applied for the threshold breach; that records what landed, not a preferred pattern over root-cause fixes.
- Pre-existing unrelated red: `CliFeatureTaskRuntimeRuntimeTest "feature-task-runtime run requires issue key and spec path"` fails on the clean tree too (zcode prose-only env refusal precedes the issue_key check) — not caused by this change.
Feature flag: N/A
Acceptance criteria: subtask-1 10/10 implemented

## [2026-07-03] SKILL-100 zcode agent support (subtask 2: native-agent-mcp-runtime)
Areas: runtime-infra-fs/install/apply, runtime-infra-fs/launcher/agentrun, runtime-infra-fs/launcher/mcp
- Closed the six exhaustive-`when` sites subtask 1 flagged as won't-compile: `linkZcodeAgents`/`unlinkZcodeAgents` (modeled on Junie) wired into `FileSystemInstallAdapters` link/unlink + a `nativeAgentInstallers` registry entry.
- New `McpZcodeConfig` reads/writes `~/.zcode/cli/config.json` under a nested `mcp.servers.skill-bill` key — a different on-disk shape than the flat `mcpServers` key every other agent uses; reuses `McpJsonConfig`'s shared read/write/mutable-map helpers. reusable
- `McpRegistrationOperations.register/unregister/configPathFor` add a ZCODE case; `unregister` drops `servers` then `mcp` only when both go empty, preserving sibling JSON keys at both nesting levels.
- New `ZcodeAgentRunCommandBuilder` builds `zcode --prompt <p> --json --cwd <dir> --mode yolo --no-color`, inheriting `usePtyStdio=false`/`idlePolicy=DB_PROGRESS_ONLY` defaults (no override needed, unlike opencode's PTY path); registered in `headlessAgentRunAdapters()` and survives `RUNTIME_REFUSED_AGENTS` (only OPENCODE is refused).
- Known non-blocking gap flagged in-code: the `--json` envelope parser shape is UNCONFIRMED against a live ZCode session — follow-up verification needed before relying on structured output.
- Tests extended in lockstep: `AgentRunLauncherTest` (adapter-presence + command-shape for ZCODE alongside CLAUDE/CODEX/JUNIE) and `McpRegistrationOperationsTest` ("non-claude agents stay single-target" expected-paths map).
Feature flag: N/A
Acceptance criteria: subtask-2 10/10 implemented

## [2026-07-03] SKILL-100 zcode agent support
Areas: runtime-infra-fs/install, runtime-infra-fs/nativeagent, runtime-domain/install/model, runtime-desktop/core/domain, orchestration/contracts
- Adding a new supported agent = fan out across SIX enums, modeled on the adjacent Junie entry (reusable checklist): InstallAgent, NativeAgentProviderId, NativeAgentProvider, NativeAgentLinkProvider, FirstRunSetupAgent (+ its `supportedIds` companion), AgentSymlinkProvider & DesktopAgentSymlinkProvider.
- `NativeAgentProvider.Zcode("zcode-agents","md")` uses `render = renderFrontmatterAgent(mode = null)` and `homeAgentDirs = listOf(home.resolve(".zcode/agents"))` — mode=null (like Junie, unlike Claude).
- Install-path layer: `SUPPORTED_AGENTS += "zcode"`; `agentPaths` "zcode"→`.zcode/skills`; `agentIsPresent` "zcode"→`listOf(.zcode)`; `agentDirectory` "zcode"→`InstallOperations.zcodeAgentsPath(home)`, backed by new `zcodeAgentsPath(home)=home.resolve(".zcode/agents")`.
- Deliberate non-changes: `RUNTIME_REFUSED_AGENTS` untouched (only OPENCODE refused); `INVOKING_AGENT_CONTEXT_SIGNALS` left CLAUDE/CODEX/OPENCODE only — zcode has no distinct invoking-context signal yet (Decision C, AC14).
- Exhaustive-`when` sites that WON'T compile until a ZCODE branch is added: McpRegistrationOperations (register/unregister/configPathFor), SkillRemoveJvmFileSystem.nativeProvider, JvmRuntimeSkillRemoveGateway, ConfirmDeletionDialog.displayLabelFor, FileSystemInstallAdapters NativeAgentLinkProvider link/unlink (+ new Junie-modeled `InstallNativeAgentOperations.link/unlinkZcodeAgents`).
- detekt `TooManyFunctions` trips on `InstallOperations` and `InstallNativeAgentOperations` once the zcode path resolver + link/unlink pair land — a `@Suppress("TooManyFunctions")` with one-line rationale was applied for the threshold breach; that records what landed and does not rule out refactoring or prescribe suppression as the preferred fix.
- Tests extended in lockstep: FirstRunSetupModelsTest (supportedIds contains "zcode"), InstallPlanContractCoverageTest + InstallPlanSchemaValidatesExistingFixturesTest (add `.zcode` fixture-dir literal), and hardcoded provider-COUNT asserts bumped 5→6 (SkillRemoveTest, InstallPlanModelTest).
- Pre-existing unrelated red: `InstallerShellDelegationTest "install plan summary is printed before any mutation"` fails on the base commit too — not caused by this change.
Feature flag: N/A
Acceptance criteria: 14/14 implemented

## [2026-06-23] SKILL-89 per-subtask agent attribution — Seam D fs adapter
Areas: runtime-infra-fs/fs (new `FileSystemFeatureTaskRuntimeSpecStatusWriter`)
- `FileSystemFeatureTaskRuntimeSpecStatusWriter` implements the new `FeatureTaskRuntimeSpecStatusWriter` port; writes an idempotent `Agent: <id>` line immediately after the `Status:` line under `## Status` in a tracked `spec.md`. Three cases: (1) `## Status` heading absent → no-op; (2) `Agent:` line present → update in place; (3) heading present, no `Agent:` line → insert on the next line.
- File read/write is line-by-line via `readLines()`/`writeText`; no regex replace — plain index insertion avoids clobbering adjacent heading content. reusable
- Accepts a null `specPath` gracefully (port contract); wired into `FeatureTaskRuntimeSpecGate.finalizeSingleSpecOnTerminal` where it is called only when `specPath != null`. No loud-fail on a missing spec path — SMALL/no-spec runs are a no-op. reusable
- The acceptance-criteria reader (`FileSystemFeatureTaskRuntimeRunInvariantsSource`) keys off `## Acceptance Criteria`; `Agent:` lives only under `## Status`, so the two headings never collide.
Feature flag: N/A
Acceptance criteria: part of SKILL-89 12/12 — see runtime-kotlin/agent/history.md

## [2026-06-22] SKILL-88 opencode-pty-stdio
Areas: runtime-infra-fs/launcher/process, runtime-infra-fs/launcher/agentrun, runtime-application/featuretask
- PTY-backed stdio spawn path for opencode: `startPtyProcess()` in `JvmAgentRunProcessRunner` allocates a POSIX master fd via JNA (`PosixCLibrary` / `PosixLib`), builds the child process with `redirectInput/Output/Error(File(slavePath))`, and reads output through `PtyMasterInputStream` → `CappedUtf8Drain`. Fixes Bun-compiled opencode aborting status 1 when its stdout is a JVM pipe
- `ProcessStart.PtyStarted` seals alongside `Started`/`Failed`; `runStartedProcess()` receives `stdoutStream`, `stderrStream`, `ptyMasterCloseable` params; for PTY: stdoutStream=ptyMasterStream, stderrStream=nullInputStream (PTY master fd merges stdout+stderr)
- Fd lifecycle: `masterCloseable.close()` called BEFORE stdout/stderr drain joins to send EIO and unblock the drain — ordering is critical
- `usePtyStdio: Boolean = false` threaded through `AgentRunCommand` → `AgentRunProcessRequest` → `JvmAgentRunProcessRunner`; `OpencodeAgentRunCommandBuilder` sets it true; claude/codex/junie unchanged
- JNA 5.13.0 added to `runtime-infra-fs/build.gradle.kts` (version in `libs.versions.toml`) — provides `Native.load("c", PosixCLibrary::class.java)` without pty4j (unavailable offline)
- Linux-only guard: `check(os.name.startsWith("linux"))` is the first call in `startPtyProcess()` — macOS lacks `ptsname_r` and has a different `O_NOCTTY` constant
- `openMasterFd()` wraps `grantpt`/`unlockpt` in try/catch that closes the fd before rethrowing ISE (prevents fd leak on partial PTY init)
- `infraFailureReason` in `FeatureTaskRuntimeRunner` now appends a bounded stderr/stdout excerpt (reuses `stderrExcerpt` / `GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS`) when a phase agent exits non-zero
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-06-10] SKILL-76 baseline-reconciliation (subtask 2)
Areas: runtime-infra-fs/install, runtime-domain/install/model, runtime-ports/install, runtime-application/install, runtime-cli, install.sh
- Reconcile-on-reinstall uses dpkg-conffile semantics per installed skill, comparing three `computeInstallContentHash` values: upstream (staged candidate), local (`~/.skill-bill` copy), baseline (last copied in). `classifySkill` (`InstallReconcilePolicy.kt`) is the single classifier; `applyReconciliation` (`InstallReconcileApply.kt`) re-derives the same plan from the same inputs instead of carrying it across the process boundary, so compute and apply cannot disagree
- Outcomes: local==baseline→Adopt; local!=baseline & upstream==baseline→KeepLocal; both diverged→Conflict; no upstream counterpart→LocallyAuthored (never deleted); all equal→KeepLocal no-op. `baselineRefreshPaths` (Adopt+NewUpstream+Conflict) is the one refresh-eligibility set, reused by `refreshBaselineFromPlan`
- Pitfall: the shell must not bulk-`cp -R` candidate platform-packs over live before apply; that defeats keep-local/conflict for pack skills. The runtime per-skill apply is the sole writer of reconciled skill dirs in `skills/` and `platform-packs/`; `adoptPlatformPackNonSkillFiles` copies only non-skill pack files, and install.sh `adopt_non_skill_source_trees` only replaces orchestration
- Pitfall: no baseline + divergent local must be Conflict, not NewUpstream (silent overwrite in the migration window is data loss). `classifyNoBaseline`: local null or local==upstream→NewUpstream, else Conflict
- Pitfall: `replaceSkillDirAtomically` renames live aside to a sibling backup, moves staged in, drops the backup in `finally`, and restores it if move-in throws. Never delete-then-move
- Conflict UX (AC-7): decide before any live mutation. install.sh stages to `.candidate-*`, runs compute-only `install reconcile`, prompts, then runs `--apply`; abort changes nothing; no TTY → abort with a message. Test seam `SKILL_BILL_RECONCILE_CONFLICT_CHOICE` bypasses only the TTY check
- Baseline manifest `~/.skill-bill/baseline-manifest.json` ({contract_version, baselines: sorted path→16hex}) persists via the InstallSelectionPersistence-mirror trio (port + FS adapter + wire codec), atomic temp+ATOMIC_MOVE, sorted keys (AC-9). uninstall.sh preserve mode keeps it; explicit `./uninstall.sh` removes it
- Shell↔runtime line contract (reusable): `key=value` fields with the free-form token last, e.g. `reconcile_outcome: kind=<k> [upstream_hash=<hex>] path=<p>`. The shell greps `^reconcile_outcome: kind=conflict ` and extracts via `sed 's/^.* path=//'`, so paths with spaces survive; gate decisions on the typed `conflict_count`, not the grep
- Ownership: `InstallService` (application) refreshes the baseline from the returned plan; the infra adapter does per-skill file ops and conflict gating only. Port KDoc must match, or it invites a double-write
Feature flag: N/A
Acceptance criteria: subtask-2 AC-3/5/6/7/8/9 implemented + covered

## [2026-06-10] SKILL-76 migration + parity closeout (subtask 3)
Areas: runtime-infra-fs/install (tests), README
- Migration to the copied-source model needed NO new production code: `--replace-existing-skill-bill-links` (`InstallSymlinkReplacement.createManagedSymlinkWithGuidance(replaceExisting=true)` + `readSymlinkTargetOrNull`) already repoints a clone-pointing managed agent link onto the copy and leaves no dangling clone link. Locked by an `InstallApplyReplacementCleanupTest` case that seeds a link into a sibling clone and asserts the repoint resolves under `~/.skill-bill/installed-skills` with zero surviving links into the clone (AC-10)
- The SKILL-74 claude multi-profile fan-out, SKILL-75 per-profile MCP registration, and `CLAUDE_CONFIG_DIR` honoring are all SOURCE-LOCATION-AGNOSTIC: they key off `home`, not `--repo-root`. Moving `--repo-root` to the copy changed nothing. Pattern for locking this: a `copiedSourceFixture` that copies the seed repoRoot under `~/.skill-bill` and re-runs the existing multi-root assertions, plus a guard that `repoRoot.startsWith(home/.skill-bill)` — proves the invariant without forking the fan-out (AC-11)
- AC-4/AC-12 gap closed: the non-content-managed fallback test previously could only prove verbatim pass-through; strengthened to materialize an identically-named skill in a sibling clone and assert the fallback target resolves under the copy and NEVER into the clone
Feature flag: N/A
Acceptance criteria: subtask-3 AC-10/AC-11/AC-12 verified + covered; AC-3 clone-deletable guarantee documented

## [2026-06-09] SKILL-75 claude-mcp-registration-per-profile
Areas: runtime-infra-fs/launcher, runtime-infra-fs/install, runtime-domain/install/model, runtime-cli, uninstall.sh
- Extends SKILL-74's `claudeConfigRoots(home, environment)` fan-out to MCP registration. `McpRegistrationOperations.register/unregister` fan out across profiles for the CLAUDE branch ONLY; non-claude agents stay single-target via `configPathFor`. No second discovery path — reuses `claudeConfigRoots` and `McpJsonConfig` (no forked JSON merge)
- Per-profile config-file mapping is asymmetric and lives in `claudeProfileConfigPaths`: default root (`home/.claude`) → `$HOME/.claude.json` (sibling, NOT `~/.claude/.claude.json`); every named/`CLAUDE_CONFIG_DIR` root → `<root>/.claude.json`. Compare against a normalized `home.resolve(".claude")` or the default/named split misclassifies
- Loud-fail isolation pattern (reusable): `claudeFanOut` is collect-and-surface — attempt every profile, write siblings, collect failures, then throw. The thrown `ClaudeMcpProfileFailure(message, succeeded)` carries already-written profiles so callers report partial state truthfully instead of total failure
- Pitfall caught in review: a typed exception caught by runtime-cli CANNOT live in runtime-infra-fs (`RuntimeAdapterDependencyAllowlistTest` forbids cli→infra-fs). Put it in runtime-domain (`skillbill.install.model`, exempt from the implementation-import ban) and extend `IllegalArgumentException` so `CliRuntime` still maps it to exit 1
- AC9 summary: human-facing CLI/uninstall text filters to `changed` profiles; the structured payload (`mcpProfilesMap`, apply `outcomes[].profiles[]`) keeps every profile with its `changed` flag. install.sh/uninstall.sh argv unchanged (no shell-side profile loop); uninstall.sh captures stdout to surface removed + partially-removed paths
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-06-09] SKILL-74 auto-detect-claude-profiles
Areas: runtime-infra-fs/install, runtime-infra-fs/nativeagent, runtime-domain/install/policy, runtime-cli, install.sh, uninstall.sh
- `claudeConfigRoots(home, environment)` in `ClaudeConfigPaths.kt` is the single source of truth for the claude profile set: default `~/.claude` first, then marker-filtered top-level `$HOME/.claude-<name>` dirs (markers `.claude.json`/`.credentials.json`/`commands`/`agents`/`history.jsonl`), then a distinct non-blank `CLAUDE_CONFIG_DIR`; deduped by normalized abs path. The single-root `claudeConfigRoot` stays for AC9 (`agent-path claude`/`claude-agents-path` return only the active root)
- Multi-root fan-out is achieved at the plan-target seam, NOT by changing apply iterators: claude expands to N `InstallAgentTarget` rows (one `<root>/commands` each) in `InstallPlanBuilder`/`InstallPlanPolicy`, so `linkPlannedSkill`, the orphan sweep, and `InstallApplyCleanup` fan out unchanged. Pattern reusable: to make one agent target many dirs, expand the target list upstream and leave the `plan.agents` iterators alone
- `requireNoDuplicate*Targets` re-keyed from `agent` to `(agent, normalized path)` so N claude rows at distinct roots are legal while true same-path duplicates still fail
- Native subagents fan out via `NativeAgentProvider.Claude.homeAgentDirs` returning every `<root>/agents` (single source for both link and unlink); `linkClaudeAgents` must materialize EVERY resolved root uniformly (no per-root existence gate) or commands and agents diverge into a half-installed default root
- Pitfall caught in review/audit: env-default leaks. `claudeConfigRoots` env contribution and `InstallAgentService.claudeRoots` must take `environment` explicitly (threaded from `state.environment` at the CLI) — defaulting to `System.getenv()` in the application layer fails `RuntimeArchitectureTest`; infra-fs may keep the `System.getenv()` default
- Pitfall: shell→runtime contract changes break `InstallerShellDelegationTest`. New `run_runtime_cli install <subcommand>` calls (here `claude-roots`, `unlink-claude-agents`) must be whitelisted in BOTH fake-CLI stubs, and dropping a `--agent-target` pin (claude) requires updating the expected-argv test
- Runtime owns discovery: `install claude-roots` CLI command (port→service→adapter→`InstallOperations.claudeRoots`) is the only enumerator; install.sh drops its claude `--agent-target` pin and uninstall.sh loops the command for per-root commands+agents cleanup — neither shell re-globs `$HOME`
Feature flag: N/A
Acceptance criteria: 14/14 implemented

## [2026-06-08] orchestration-content-delivery
Areas: runtime-infra-fs/install, runtime-infra-fs/scaffold, runtime-domain/install/model, runtime-cli, skills/bill-feature-spec, skills/bill-feature-task-prose
- `writeRenderedSupportPointerFiles` now inlines the canonical orchestration doc bytes (via `normalizeMarkdownLineEndings + trimEnd + "\n"`) instead of a repo-relative path — the cache is detached so relative paths dangled
- `computeInstallContentHash` folds `Files.readAllBytes(pointer.target)` for each support pointer (was the relative-path string) — doc edits now invalidate the cache and force a re-inline
- `OrchestrationLinkStatus`, `OrchestrationLinkOutcome`, `ORCHESTRATION_LINK_FAILED`, `orchestrationLinks` field, `applyOrchestrationLinks`, `InstallApplyOrchestrationLinks.kt`, cleanup block, CLI mapping all removed — the symlink was near-vestigial once sidecars became self-contained
- `validateNoOrchestrationPathsInSkillBodies` added to `RepoValidationRuntime` — scans `skills/*/content.md` and platform-pack content files for bare `orchestration/[\w/.-]+` tokens; wired into `validateRepo`
- Pattern: install-cache content must be self-contained; orchestration content the runtime needs is bundled as a classpath resource (`*SchemaPaths`), not accessed by agents via paths
- Pattern reusable: any new sidecar or pointer that references an external file should inline the content at render time, not carry a path
Feature flag: N/A
Acceptance criteria: 8/8 implemented
