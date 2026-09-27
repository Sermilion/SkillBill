## [2026-09-27] SKILL-380 subtask 12 — skill-bill dispatcher for the full run and phases
Areas: skills/skill-bill (new), orchestration/{skill-classes,contracts}, agent-addons/execution-budget, runtime-kotlin/runtime-domain/{install/model,agentaddon/model}, runtime-kotlin/runtime-infra/skills/{install,scaffold}, runtime-kotlin/runtime-cli (repoTest, test), README.md, AGENTS.md, runtime-kotlin/ARCHITECTURE.md
- New `/skill-bill` entry skill. With no `phase:` it runs the full skeleton behind one confirmation gate, and a missing spec goes to `skill-bill phase plan`, not `bill-feature-spec`. `phase:<name>` routes to `skill-bill phase <name>` for plan/implement/review/validation/pr. `code-review:` and `mode:` tokens pass through unchanged. `phase:` with `operation:` is a dispatcher usage error.
- `ListedSkillNames` (runtime-domain, `bill-` prefix plus the `skill-bill` dispatcher) is now the single owner of the "listed skill" rule. Install discovery, platform-from-path, README-row regexes and skill-reference regexes all use it. Add any new non-`bill-` listed skill there. reusable
- `skill-bill` uses the `feature-launch-warning` class (`exact: skill-bill`), so it stages the same sidecars as `bill-feature`. `AgentAddonConsumer.SKILL_BILL` is added, and execution-budget consumers are `[bill-feature, skill-bill]`. Addon schema contract_version stays 1.0, and persisted `bill-feature` selections still verify.
- `bill-feature` and every old listed skill are unchanged. The dispatcher sits beside them rather than replacing them. Uninstall is unchanged and removes the `skill-bill` link.
- Limitation: `PhaseInvocationParser` still reads `operation:` as intake. Only the dispatcher skill refuses it; the phase CLI does not.
Feature flag: N/A
Acceptance criteria: 6/6 implemented (validate: ./gradlew check green)

## [2026-09-11] SKILL-233 — named standalone code-review targetsAreas: skills/bill-code-review
- `/bill-code-review` accepted arguments now include `pr`, `last`, a commit SHA, and `uncommitted` (plus the narrower staged/unstaged packets).
- The skill maps those tokens to the driver; it does not treat `pr` as a git revision.
Feature flag: N/A
Acceptance criteria: n/a
## [2026-08-24] SKILL-200 subtask 5 — collapse feature entry family to one skill
Areas: skills/bill-feature, skills/bill-feature-spec, docs, orchestration/contracts, runtime-kotlin
- Rewrote `bill-feature` as the sole feature entry point for intake, update checks, token forwarding, one preflight gate, selective rehydration, launch, and verbatim relay.
- Removed the `bill-feature-goal` sidecar and obsolete feature-family references from documentation, staging/rendering checks, telemetry contracts, and runtime adapters while preserving durable goal identities.
- Pattern: keep confirmation and issue rehydration in the entry skill; keep goal orchestration, telemetry, and durable state in their owning runtime boundaries. reusable
- Breaking changes or known limitations: direct callers of removed skill names have no compatibility shim; the durable workflow identity and CLI goal command remain.
Feature flag: N/A
Acceptance criteria: 15/15 implemented

## [2026-08-24] SKILL-200 subtask 3 — delete unread bill-feature prose
Areas: skills/bill-feature-goal, skills/bill-feature
- Removed runtime-enforced internals, CLI-rendered output instructions, duplicated intake and handoff prose, and excess status catalogue text while retaining agent-owned guidance.
- Reunited the `bill-feature` Update Check heading with its body.
- Pattern: keep feature skills focused on action-bearing guidance; leave runtime behavior and generated output to their owners. reusable
- Breaking changes or known limitations: content-only deletion; runtime, contract, CLI, test, and install behavior are unchanged.
Feature flag: N/A
Acceptance criteria: 10/12 implemented

## [2026-08-24] SKILL-200 subtask 1 — orphaned feature-task prose removal
Areas: skills/bill-feature, orchestration/skill-classes, platform-packs/kmp, install/render/validator tests
- Removed the orphaned feature-task prose surfaces and repointed surviving dispatch and add-on declarations to the goal entry surface.
- Retired matcher and launch-warning references without producers; kept governed pointers on the surviving feature surface.
- Updated staging, rendering, and validator expectations so the feature family retains supported sidecars without deleted outputs.
- Pattern: remove a governed skill surface together with its manifests, matchers, add-on consumers, and generated-output assertions. reusable
- Breaking changes or known limitations: direct callers of the removed skill names have no compatibility shim; durable runtime identity and CLI remain unchanged.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-20] Validate runs only the pack collect-all command
Areas: skills/bill-feature-task-runtime
- Feature-task validate uses only the pack-declared collect-all command. Repo-root `skill-bill validate` / agnix / validate_agent_configs stay off that phase.
- Pattern: pack gate owns validate; maintainer lists do not. reusable
Feature flag: N/A
Acceptance criteria: 1/1 implemented

## [2026-08-20] Validate agent runs collect-all then confirms
Areas: skills/bill-feature-task-runtime, skills/bill-code-check
- Validate and quality-check repair windows tell the session to run collect-all, read the output, fix the set, then confirm once. They no longer forbid checks during repair.
- Pattern: one process, one collect-all, one confirm. reusable
Feature flag: N/A
Acceptance criteria: 1/1 implemented

## [2026-08-18] SKILL-198 subtask 1 — repair window in bill-code-check
Areas: skills/bill-code-check
- `bill-code-check` is no longer frontmatter-only: Purpose, Repair Window, and Routing sections state one collect-all finding set, zero checks while the set is open, and one post-repair verification gate.
- Forbidden-command list names gate variants, `bill-code-check` re-invocation, Gradle module tasks (`detekt`, `ktlintCheck`, `test`, `compileKotlin`), pack checkers, and subagent-delegated checks; allowed work is read, search, and source edits only. reusable PATTERN: horizontal router skill carries the same repair-window contract as pack sidecars and validate briefing.
- `BillCodeCheckRepairWindowContractTest` pins substantive content and the prohibition vocabulary.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-10] SKILL-178 subtask 4 — Governed content and parity-lock sweep
Areas: skills/bill-feature-goal, skills/bill-feature-task-runtime, skills/bill-code-review, orchestration/review-orchestrator, runtime-kotlin/agent, runtime-kotlin content-lock tests
- Aligned governed remediation prose so a remediation round is handed all findings and both Blocker and Major reopen `implement_fix` / block advance; Minor and Nit stay ledger-only via `skill-bill goal findings`
- Remediation-delta scope is all findings addressed in that round unioned with the pre-fix-to-post-fix diff; evidenced dispositions required for every addressed finding (PLAYBOOK + bill-code-review)
- Non-convergence wording is the unresolved Blocker-or-Major set with a human-resumable uncapped pause; no live Blocker-only reopen statements remain in skills or playbooks
- Content-lock/parity tests retargeted to the new rule (none deleted); boundary decision recorded under `runtime-kotlin/agent/decisions.md`; durable wire key `blocker_dispositions` left named as-is
- reusable PATTERN: when runtime severity gates widen, update governed markdown and content-lock tests in the same pass so locks cannot keep encoding the old rule
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-09] SKILL-175 subtask 3 — prose skill tree deleted, briefing SoT relocated
Areas: skills/bill-feature-task-prose, skills/bill-feature-task-subtask-runner, skills/bill-over-engineering-review, runtime-kotlin/{runtime-application,runtime-domain,runtime-infra-fs}, docs, orchestration/shell-content-contract, scripts
- Deleted `skills/bill-feature-task-prose/` (content.md + `native-agents/agents.yaml` with all seven phase agents and the subtask-runner bundle entry) and `skills/bill-feature-task-subtask-runner/`. Runtime is now the only feature-task engine with a skill source tree.
- Relocated the mutating-phase briefing source of truth into `FeatureTaskRuntimePhasePromptDirectives.kt`: the old "keep in lockstep with prose native agents" comment is gone and the Kotlin file is declared sole owner. reusable PATTERN for retiring a duplicated-prompt surface — move the SoT into the surviving owner *before* deleting the copy, then flip parity tests to single-source.
- Parity/governed-content tests converted from prose+runtime lockstep to runtime-only (`UnboundedRemediationLoopGovernedContentTest`, `FeatureSpecSkillWiringContractTest`); `RepoValidationRuntime` governed-content map lost its prose content.md entry; `FeatureFamilyRenderingIntegrationTest` no longer stages `bill-feature-task-prose.md` under the feature family.
- Peak-hours warner sidecar scope narrowed to `bill-feature` + the runtime launch surface; install/nesting smoke scripts dropped prose MCP-tool and `goal mode:prose` assertions; docs (getting-started, capabilities, internal-skills-architecture, skill-source-generation, ARCHITECTURE) no longer map prose sidecars.
- Known limitation: MCP `feature_task_prose_*` / `goal_prose_*` tool registrations and CLI `workflow` family still exist (subtask 4), as does `FeatureImplement*` persistence (subtask 6). English "prose" wording elsewhere is intentional.
- Breaking change: any external caller invoking `bill-feature-task-prose` or its native agents by name now has no target; there is no shim or redirect.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-05] SKILL-160 subtask 3 — scoped-replan operator guidance
Areas: skills/bill-feature-goal, skills/bill-feature
- Documented `skill-bill goal replan <key> --subtask <id>` as first-choice for mid-goal subtask-spec amendments; default preserves sibling plans, shared preplan, and runtime rows; `--include-shared-preplan` discards sibling plan rows for provenance safety.
- Named idle-goal and terminal-subtask refusals (reopen terminal via `reset`); kept hard/soft reset accurate for goal-wide invalidation. reusable PATTERN: when shipping a scoped operator command, update both goal planning prose and parent continuation rules in the same pass so agents do not keep the lossy workaround.
- Explicit anti-workaround: do not hard-reset then compensate with `accept --restore-after-hard-reset` to replan one subtask.
- Content-only subtask; quotes match ST1/ST2 shipped CLI/flags/refusal messages; no runtime/persistence edits.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-05] SKILL-162 subtask 3 — bill-shortcut-debt ledger skill
Areas: skills/bill-shortcut-debt, skills (README catalog)
- Scaffolded and filled `bill-shortcut-debt`: one-shot harvester for comment-prefixed `shortcut:` markers into a grouped ledger (ceiling + upgrade trigger), with `no-trigger` rot tags, summary line, and `No shortcut debt. Clean ledger.` empty verdict.
- Boundaries are read-only by default (explicit path only for optional ledger write); honesty rule forbids invented per-repo savings; marker convention ownership stays with subtask 1. reusable PATTERN: horizontal ledger/report skill over an existing comment convention, not a new persistence surface.
- Catalog lists `/bill-shortcut-debt`; no benchmark/savings claims and no marker-convention or runtime-kotlin edits in this subtask.
- Known limitation: `./install.sh` staging refresh deferred to the goal boundary per the goal-continuation installer prohibition.
Feature flag: N/A
Acceptance criteria: 5/5 implemented (AC#5 install.sh refresh deferred to the goal boundary)

## [2026-08-05] SKILL-162 subtask 2 — bill-over-engineering-review horizontal skill
Areas: skills/bill-over-engineering-review, skills (README catalog)
- Scaffolded and filled `bill-over-engineering-review`: complexity-only review/audit with diff (default) and repo scopes, five tagged finding lines (`delete`/`stdlib`/`native`/`yagni`/`shrink`), net-lines scoring, and `Lean already. Ship.` empty verdict.
- Boundaries route correctness/security/performance to `bill-code-review`; carve out governed contracts and `shortcut:` markers (subtask 1 convention); never flag minimal smoke tests; report-only (no auto-fix).
- Attribution points at the single SKILL-162 MIT home in `bill-feature-task-prose`; no benchmark/savings claims. reusable PATTERN: focused horizontal review skill shaped like `bill-unit-test-value-check`.
- Known limitation: `./install.sh` staging refresh deferred to the goal boundary per the goal-continuation installer prohibition.
Feature flag: N/A
Acceptance criteria: 6/6 implemented (AC#5 install.sh refresh deferred to the goal boundary)

## [2026-08-05] SKILL-162 subtask 1 — ponytail minimalism discipline in implementation prompts
Areas: skills/bill-feature-task-prose, runtime-application/featuretask, runtime-infra-fs/launcher/agentrun, runtime-domain/install/model, runtime-cli, runtime-ports/agentrun
- Ported ponytail's seven-rung reuse-before-write ladder, anti-abstraction rules, root-cause bug-fix rule, never-simplify carve-outs (including skill-bill governed contracts), and `shortcut: <ceiling>, <upgrade trigger>` marker into both implementation-phase briefings.
- Dual-surface lockstep: identical block in `native-agents/agents.yaml` + `content.md` and in runtime `minimalismDisciplineDirective` for mutating phases (runtime uses a separate source). reusable PATTERN: keep prose agent briefings and FeatureTaskRuntimePhasePromptDirectives in lockstep when guidance must hold in both modes.
- Single MIT attribution home under bill-feature-task-prose; no benchmark or savings claims in the added content.
- Collateral on the same branch: `AGENT_LAUNCHER_CLIS` + `ExecutableLookup` preflight refuses agents whose headless CLI is missing before spawn, with legacy-name substitution and stderr-preserving failure renderers. reusable
- Known limitation: `./install.sh` staging refresh deferred to the goal boundary per the goal-continuation installer prohibition.
Feature flag: N/A
Acceptance criteria: 6/6 implemented (AC#5 install.sh refresh deferred to the goal boundary)

## [2026-08-03] SKILL-157 subtask 3 — governed prose and validation parity for unbounded remediation
Areas: skills/bill-feature-task-runtime, skills/bill-feature-task-prose, skills/bill-feature-goal, skills/bill-feature-task-subtask-runner, runtime-kotlin/runtime-domain
- Removed every governed claim that review is capped at two passes; review and audit remediation now loop until Blocker and Major findings are cleared, with crossing iteration 3 into 4 printing an advisory warning rather than terminating the loop.
- Review pass one keeps the caller-selected mode; every later pass runs inline, so remediation re-reviews never re-fan-out specialists.
- Regeneration loops stay bounded (MAX_RECORD_REGENERATION_ATTEMPTS=2, three regeneration edges): "unbounded" applies only to review/audit fix loops. reusable distinction for future loop-governance work.
- Parity is test-enforced, not prose-only: `UnboundedRemediationLoopGovernedContentTest` binds the runtime advisory threshold, asserts semantic fix-loop edges carry no finite per-edge cap, and denylists legacy cap vocabulary across `skills/` and `platform-packs/`; red-checks confirmed each assertion fails on drift. reusable PATTERN: when documented behavior must match a runtime constant, assert the constant from the governed markdown rather than restating it.
- Known limitation: `./install.sh` staging refresh is deferred to the parent goal boundary per the goal-continuation installer prohibition, so local installs lag until the goal finalizes.
Feature flag: N/A
Acceptance criteria: 6/7 implemented (AC#6 install.sh refresh deferred to the goal boundary)

## [2026-07-25] SKILL-142 subtask 4 — lane severity calibration test infrastructure
Areas: runtime-kotlin/runtime-infra-fs, skills/bill-code-review
- Fixed `ReviewSkillStructureConformanceTest.kt` compilation: added YAMLMapper import, helper functions `manifest()` and `declaredAreas()` for manifest parsing, converted string-labeled blocks to kotlin.test assertions. All 9 tests pass.
- Test infrastructure supports validator coverage that rejects specialists defining their own severity vocabulary or omitting consequence requirements — enforced against shared review rubric in `orchestration/review-orchestrator/review-skill-structure-standard.md`.
Feature flag: N/A
Acceptance criteria: 1/7 (AC#7) implemented

## [2026-06-23] SKILL-94 release-tooling (bill-release skill)
Areas: skills/bill-release, runtime-kotlin/runtime-application, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp
- New governed skill `bill-release` orchestrates the full release process: find prior stable tag via `git tag --sort=-version:refname`, categorize commits into New Features / Bug Fixes / Other using editorial judgment, draft changelog inline for user review, suggest semver bump per RELEASING.md, require explicit confirmation before `git tag` and again before `git push --tags`.
- Double-gate pattern: step 7 opens with "Ready to create tag vX.Y.Z — shall I proceed?" before running the tag command; step 8 has a separate push gate — two independent user-facing asks for the two irreversible actions. reusable PATTERN: each irreversible git action (tag, push) gets its own confirmation gate; never combine them.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-06-23] SKILL-93 update-check-on-bill-feature (bill-feature gate)
Areas: skills/bill-feature
- `bill-feature` now calls `mcp__skill-bill__update_check` as the **first** action before any intake or spec-prep work
- Gates on `update_available`: surfaces installed vs latest version, asks update-now-or-continue; stops with `recommended_install_command` if user chooses update
- All other statuses (up-to-date, ahead, unknown) proceed to intake silently — no user prompt (reusable gate pattern for skill entry-point version checks)
Feature flag: N/A
Acceptance criteria: 12/12 implemented

## [2026-06-23] SKILL-92 bill-feature-goal prose lifecycle telemetry wiring
Areas: skills/bill-feature-goal
- `bill-feature-goal mode:prose` now emits goal lifecycle telemetry: calls `goal_prose_started` at goal start, `goal_prose_subtask_finished` after each subtask, and `goal_prose_finished` at goal completion/termination.
- Idempotency is documented in the skill: all three tools are safe to re-call; re-emitting an already-recorded boundary is a no-op.
Feature flag: N/A
Acceptance criteria: 1/1 (AC#5) implemented

## [2026-06-13] prose-goal-subtask-isolation
Areas: skills/bill-feature-goal, skills/bill-feature-task-prose, skills/bill-feature-task-subtask-runner, scripts
- Goal orchestrator (bill-feature-goal mode:prose) stays thin: holds only decomposition manifest + per-subtask terminal outcomes; no phase artifacts accumulated
- Subtask execution delegated to Level-1 Agent-tool spawn (bill-feature-task-subtask-runner) with self-contained briefing; continuation via feature_task_prose_workflow_continue with suppress_pr=true
- Terminal outcome verified via feature_task_prose_workflow_get after each subtask-agent returns; in-session return value is signal only
- Stop-loudly contract: on subtask failure, orchestrator surfaces subtask ID, blocked_reason, workflow ID, and last_resumable_step; does not advance
- New native agent entry bill-feature-task-subtask-runner in skills/bill-feature-task-prose/native-agents/agents.yaml (reusable)
- New skill skills/bill-feature-task-subtask-runner/content.md — Level-1 subtask agent; required by validateAgentConfigs for bill-* references in prose .md files (reusable)
- 3-level Agent nesting verified via scripts/agent_nesting_smoke_test.sh go/no-go gate (pattern reusable for nesting depth validation)
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-06-08] decomposition-manifest-schema-in-skills
Areas: skills/bill-feature-spec, skills/bill-feature-task-prose
- Added v0.3 manifest YAML template to bill-feature-spec decomposed Output Rules; agents fill it directly from planning subagent RESULT
- Updated Shared Preparation Path: explicitly states agent writes YAML directly — no CLI/MCP route exists for standalone spec preparation (FeatureSpecPreparationWriter is internal Kotlin only)
- Extended bill-feature-task-prose planning subagent decomposition RESULT block with all manifest fields: execution_model, base_branch, feature_branch, stack_branches, current_subtask_intent (top-level) and status/branch/commit_sha/workflow_id/blocked_reason/last_resumable_step/linear_issue_id/dependencies (per-subtask)
- Skill source dirs (skills/<skill>/) may only contain content.md and native-agents/ — do NOT create agent/ subdirectories inside them; boundary history belongs at skills/agent/history.md
Feature flag: N/A
Acceptance criteria: 4/4 implemented
