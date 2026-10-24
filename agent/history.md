## [2026-10-24] SKILL-420 subtask 2 backend and web pack QA guidance
Areas: platform-packs (kotlin, go, rust, python, php, typescript manifests and qa/ guidance)
- Six packs now declare a block-style qa_capability placed just before validation_gate, matching kmp, with no discovery_hints and no contract_version change. kotlin, go and rust: platforms backend, guidance qa/backend-qa.md. python and php: backend and web, qa/backend-web-qa.md. typescript: web and backend, qa/backend-web-qa.md.
- The platform token pattern in platform-pack-schema.yaml already allowed backend and web, so no loader or schema edit was needed. generic, kmp and ios are untouched.
- Backend guidance follows one fixed order per stack: environment discovery, disposable database and migrations, synthetic data, start the app, exercise acceptance endpoints. Each uses stack names (Spring test profiles and Testcontainers, testcontainers-go, testcontainers-rs and sqlx or diesel).
- Python and php add a Web part: browser driver discovery or naming the missing capability, storage-state authentication, server-rendered journeys with screenshots, and backend reach versus mocks. typescript folds the backend points into one Backend services section, then frontend environment, mocks, authentication, browser journeys, layout and keyboard (reusable shape for new web-capable packs).
- Guidance holds only placeholders such as localhost and a port, never real URLs, credentials or tokens; guidance paths stay pack-relative.
- Limit: guidance is prose that QA agents read; PlatformPackComposition and schema repo tests only prove the declarations load and the files exist.
Feature flag: N/A
Acceptance criteria: 3/3 implemented

## [2026-10-23] SKILL-440 subtask 2 installer and uninstaller remove retired IDE plugin copies
Areas: install.sh, uninstall.sh, scripts/install_smoke_test.sh
- install.sh gains cleanup_retired_ide_plugins, run on every full install after the legacy config migration. It removes copies of the retired IntelliJ plugin (id dev.skillbill.status) and VS Code extension (skill-bill.skill-bill-status) left by the retired IDE plugin trees.
- IntelliJ-family plugins are matched by the id inside META-INF/plugin.xml of lib jars or loose jars, never by directory name. Roots cover macOS JetBrains and AndroidStudio, Linux modern (XDG data home) and Linux legacy (~/.config) layouts.
- VS Code, Insiders, VSCodium and Cursor use their CLI uninstall only when a matching directory exists, then rm -rf any leftover. Every match goes through one remove helper.
- Always returns 0: removed items print ok lines plus one restart note and one desktop-app notice; nothing found prints one line; a failed removal warns "Could not remove". bash 3.2 safe (reusable shape for best-effort installer cleanups).
- uninstall.sh carries a byte-identical marker-delimited copy, gated off when SKILL_BILL_PRESERVE_SOURCE_ON_WIPE=1 (the pre-install wipe), and leaves REMOVED_TARGETS and SKIPPED_TARGETS alone.
- Smoke test gains fixture-jar seeding, a read-only root, recording and failing CLI stubs, an XDG_DATA_HOME override, a byte-identical block parity check, and unsets SKILL_BILL_GOAL_CONTINUATION so it runs inside goal sessions.
- Retired plugin-v* and extension-v* releases are still skipped by the latest-release resolver; comments now call them retired.
- Limit: the plugin trees' own uninstall path is gone, so a missed layout leaves stale copies.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-10-21] SKILL-430 subtask 1 build identity records and matched-pair install
Areas: build-identity (new included plugin build), install.sh, .github/workflows/release.yml, runtime-kotlin (catalog, runtime-cli, runtime-mcp, build-logic), desktop-tray, scripts smoke tests
- build-identity/ adds plugin skillbill.build-identity. writeBuildIdentity writes commit, dirty, release_tag, install_run_id in fixed order with no timestamp; all four are task inputs. Both builds include it from settings (reusable).
- A git failure writes commit=unknown, dirty=true. Untracked files count as dirty, so .feature-specs makes local builds dirty; they match only through SKILL_BILL_INSTALL_RUN_ID.
- runtime-cli and runtime-mcp ship the record at the distribution root; verifyRuntimeImageBuildIdentity guards installDist and the image, and runtimeZip depends on it.
- install.sh stages runtime and desktop, compares the records, then activates the runtime and finally the desktop. A desktop stage failure or a mismatch prints both identities and exits non-zero before any live rm or mv.
- The source install strips the leading v from the stamped version so jpackage accepts it and both records carry the same tag. Skip-hatch runs with no build compare against the live runtime record.
- release.yml cmps the runtime-cli install record against the desktop app record. Watch the first release run for CRLF or untracked files marking the tree dirty.
- Runtime catalog moved to kotlin 2.4.21, ksp 2.3.12, serialization 1.9.0. slf4j-api 2.0.17 and org.jetbrains:annotations 23.0.0 are pinned on both sides.
- Smoke-script sed function lists must include new install.sh stage and activate functions.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-27] SKILL-210-remove-copilot-support
Areas: runtime-kotlin/install, install.sh, uninstall.sh, docs, orchestration/review-delegation
- Removed GitHub Copilot as a supported Skill Bill install agent; `InstallAgent` and install-plan schema 0.3 now list only `claude`, `codex`, `junie`, and `cursor`.
- Install detection, planning, apply, MCP registration, and installer UX no longer reference `copilot` or `~/.copilot`; uninstall still sweeps `~/.copilot/skills` as a historical cleanup path only.
- Copilot had been an install-only harness with no runtime launch path; removal leaves a clean re-entry point for future native Copilot integration.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-08-18] Install reconcile: upstream always wins
Areas: install.sh, runtime-kotlin/runtime-cli (update command)
- Reconcile no longer preserves local edits. `install.sh` lost `--prefer-upstream`, `PREFER_UPSTREAM`, the y/n conflict prompt, the no-TTY conflict abort, the `SKILL_BILL_RECONCILE_CONFLICT_CHOICE` test seam, `--accept-conflicts`, `RECONCILE_CONFLICT_PATHS` and the summary block that reported overwritten conflicts. `skill-bill update` lost its `--prefer-upstream` passthrough.
- `parse_reconcile_report` now only fail-closes on a missing/unparseable `reconcile_summary:` line (it keys off `applied=`); `has_conflicts` / `conflict_count` are gone from the line protocol.
- The compute-only `install reconcile` pass is retained ON PURPOSE even though it no longer gates a decision: it is the pre-mutation failure detector that sets `RECONCILE_FAILURE_KIND=compute`, which is the only trigger for `reconcile_and_commit_authored_source_with_recovery`'s clean copied-source reset. Deleting the pass would leave a stale copied source (e.g. a pack manifest at an older `contract_version`) failing inside `--apply` instead, after partial mutation and with no recovery. reusable
- Preserved on purpose: `clean_install_state` still wipes only `skills/`, `platform-packs/`, `orchestration/` and the baseline manifest, so `agent-addons/`, `install-selection.json`, and the durable DBs survive both a `--clean` install and the recovery reset.
- Mirror semantics: `skills/` and `platform-packs/` under `~/.skill-bill` now mirror the source — content removed upstream is deleted on the next install. `agent-addons/` is exempt because it is user-owned. The line protocol carries `pruned_count` in `reconcile_summary:` and a `kind=prune` outcome line.
Feature flag: N/A
Acceptance criteria: N/A

## [2026-08-07] SKILL-167 subtask 2 latest-release resolution hardening
Areas: install.sh, scripts, runtime-kotlin/runtime-application (updatecheck tests), .feature-specs/SKILL-167-intellij-plugin-ci-and-release
- `install.sh` no longer calls `/releases/latest`: that endpoint returns the most recently *published* release of any kind, so an IntelliJ `plugin-v*` release would win and the installer would try to fetch runtime assets from a plugin tag. It now lists `/releases?per_page=100` and picks the highest plain `vMAJOR.MINOR.PATCH` tag. reusable — reuse this shape for any repo publishing more than one release series.
- Version comparison is a zero-padded `%010d.%010d.%010d` awk sort key compared with `>`, not list order and not `sort -V`; keeps the resolver pure POSIX-ish and bash 3.2 compatible (no jq, no bash 4+ constructs), which the macOS-ships-3.2 constraint requires.
- The resolved tag is memoized in `RESOLVED_LATEST_RUNTIME_TAG` and primed by `init_latest_runtime_release_tag` in the *parent* shell. Non-obvious: an assignment inside `$(...)` is discarded, so without the priming call the API is re-queried per asset, per `.sha256` sibling and per asset listing — enough unauthenticated requests to hit rate limits.
- `RELEASE_TAG` / `SKILL_BILL_RELEASE_DIR` short-circuits are preserved ahead of resolution, so pinned and offline installs still make no network call.
- `scripts/install_smoke_test.sh` gained scenario 9 (a `plugin-v*` newest release must still resolve the runtime tag on both the installer-tag and asset-listing paths); scenarios 1-8 assert byte-identical tags to the old behavior, which is the regression guard for this swap.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-06] SKILL-163 subtask 4 telemetry privacy documentation
Areas: docs, README.md, .feature-specs/SKILL-163-telemetry-release-attribution
- Added `docs/telemetry-privacy.md`: a per-event, per-level (`off`/`anonymous`/`full`) field table covering the envelope, every `skillbill_*` event, destination and self-hosting, opt-out mechanisms, the correlating identifier, and retention. reusable
- Every documented field is traced to a shipped source symbol (`telemetryProperties`, `clearsPendingOutbox`, the `telemetry_outbox.skill_bill_version` column) rather than transcribed from spec prose — do this when documenting payloads, since spec prose drifts from the emitter. reusable
- `docs/review-telemetry.md`'s level table was expanded to match the privacy doc field-for-field and verdict-for-verdict; the two tables are now a paired invariant, so changing one requires changing the other.
- Corrected two stale claims: config lives at `~/.config/skill-bill/config.json` (not the legacy `~/.skill-bill/` path), and `telemetry disable` is an in-place level write that retains `install_id` rather than deleting config.
- Known limit: `off` still queues `skillbill_runtime_exception` and projection-measurement rows locally, and enabling later can upload them — documented, not changed here.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-06] SKILL-136 subtask 7 documentation and full maintainer gate
Areas: docs, AGENTS.md, README.md, .feature-specs/SKILL-136-android-native-review-specialists
- Refreshed the public docs surface for Android/KMP review specialists: README's KMP catalog now names Android+KMP and lists the persistence/reliability lanes, and stale composition-only claims were purged repo-wide (the single remaining hit is an immutable dated SKILL-114 history entry — leave it).
- `docs/review-telemetry.md` now documents the routing fields (`routed_skill`, `detected_stack`, `detected_scope` plus the unresolved-value convention), per-lane `specialist_reviews` with `findings.lane_skill_name` attribution, and the shared `(review_run_id, finding_id)` key that joins both stores. reusable
- Documented snapshot retention plus the opt-in prune command; prune flags and JSON payload were verified against `PruneReviewSnapshotsCommand`/`toCliMap` rather than transcribed from the spec. reusable
- Declared-area counts in docs are cross-checked against `platform.yaml` `declared_files` entries — do that instead of hand-counting when the catalog changes. reusable
- Known limit: AC-011 (installer-invocation acceptance) is a maintainer gate, deferred out of this subtask; a branch-diff guard confirms no installer invocation and no generated SKILL.md wrappers or provider-specific native-agent output were committed.
Feature flag: N/A
Acceptance criteria: 12/12 implemented (AC-011 maintainer-gated)

## [2026-07-14] SKILL-122 subtask 4 execution-budget agent add-on
Areas: agent-addons/execution-budget, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-desktop, runtime-kotlin/runtime-cli, docs
- Added the Codex-only `execution-budget` agent add-on for `bill-feature`, with concise authored guidance that preserves user stopping boundaries, scoped hand-offs, and the existing explicit-delegation rule.
- Agent add-on catalogue/desktop inspection uses tolerant discovery so valid entries remain visible beside invalid sources; strict runtime resolution remains unchanged and fails unsupported-agent selection before workflow creation. reusable
- Desktop extension browsing distinguishes Agent Add-ons from platform add-ons and exposes authored content, complete manifest metadata, validation state, source path, and a read-only manifest document without slug-specific discovery. reusable
- Real feature-task coverage proves selected guidance and provenance reach launched phases, while unsupported-agent selection fails early and omitted selection leaves prompts unchanged.
- Known limit: `execution-budget` supports only Codex and must be explicitly selected; it does not alter workflow gates, terminal phases, model settings, or context/compaction behavior.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-07-03] SKILL-100 subtask 3 cli-shell-schema
Areas: install.sh, uninstall.sh, runtime-kotlin/runtime-application (InstallAgentService), runtime-kotlin/runtime-cli (InstallCliCommands, ScaffoldCliCommands), runtime-kotlin/runtime-core (InstallerShellDelegationTest)
- Completed zcode native-agent CLI/shell/schema wiring on top of subtasks 1-2 (enum/path fan-out, MCP+runtime): added `InstallZcodeAgentsPathCommand`/`InstallLinkZcodeAgentsCommand`/`InstallUnlinkZcodeAgentsCommand` modeled 1:1 on the junie equivalents, registered as `InstallTopLevelCommands` subcommands. reusable
- `install.sh` `SUPPORTED_AGENTS` array and both help strings now list zcode; `uninstall.sh` gained a `remove_zcode_agent_mds()` block (mirrors junie, removes `~/.zcode/skills`) and the MCP-unregister agent loop now includes zcode.
- `orchestration/contracts/install-plan-schema.yaml` needed NO edit here — subtask 1 (foundation) already added zcode to the `agentId` enum; verified via grep before touching it. reusable
- Pitfall for future agent-onboarding subtasks: `InstallerShellDelegationTest.kt`'s three literal command-list fixtures must each list the new agent's `install unlink-<agent>-agents` entry, or the test silently drifts from the real CLI surface. reusable
- Known pre-existing failure, NOT introduced by this subtask: `InstallerShellDelegationTest > install plan summary is printed before any mutation()` fails identically with this branch's diff fully stashed and on a clean worktree at the merge-base (ce422474) — a fresh-temp-HOME pre-install-cleanup guard mismatch, unrelated to any agent list. Leave it out of scope rather than re-diagnosing it as a zcode regression.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-06-29] SKILL-97 fresh-install-curl-fixes
Areas: install.sh, uninstall.sh, scripts/install_smoke_test.sh
- Piped `curl … | bash` regressions: guard every `ORIGINAL_ARGS`/bootstrap-arg expansion with `${arr[@]+"${arr[@]}"}` so a no-arg piped install doesn't trip `set -u` (install.sh:270/272/276). reusable
- `bundle_bootstrap_if_needed` early-return now gates on `[[ "$INSTALLER_FROM_STDIN" -ne 1 && -d "$SKILLS_DIR" ]]`: piped installs ALWAYS fetch the release bundle and re-point `PLUGIN_DIR`/`SKILLS_DIR` to the extracted bundle, so a stray `skills/` in the caller's CWD can't make it resolve `uninstall.sh` against CWD. reusable
- Fresh-machine footprint gate in `run_pre_install_uninstall`: when `! -x $RUNTIME_CLI_BIN && ! -d $SKILL_BILL_STATE_DIR`, return 0 with "No prior Skill Bill install detected" before the `uninstall_script -x` check — never run the bundle's uninstall.sh on a clean box. reusable
- uninstall.sh made non-fatal when nothing is installed: no-wrapper/no-CLI branch warns + `return 0` (was `err … exit 1`); `run_runtime_cli` no-ops (returns 0) when neither runtime CLI is executable, so the unguarded `unlink-*` helpers don't abort under `set -e`. reusable
- Smoke fixture must mirror the REAL release bundle: `make_skills_bundle` tars `skills/ platform-packs/ orchestration/ uninstall.sh` (+ .sha256) per release.yml:144 — a skills-only fixture breaks downstream staging. Scenario 7 = piped foreign-dir w/ stray skills/; scenario 8 = fresh-machine footprint gate. reusable
- Pitfall: install.sh refuses to run with `SKILL_BILL_GOAL_CONTINUATION=1` (set by the feature-task runtime loop); reproduce the green suite locally via `env -u SKILL_BILL_GOAL_CONTINUATION bash scripts/install_smoke_test.sh` (var is unset in CI). reusable
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-06-13] SKILL-82 subtask 2 install-bootstrap-and-clean
Areas: install.sh
- `bundle_bootstrap_if_needed()`: when `SKILLS_DIR` is absent at startup, prints info line, calls `check_prebuilt_dependencies`, resolves the skills bundle asset name via `resolve_skills_bundle_asset_name()` (uses `list_release_asset_names` → respects `SKILL_BILL_RELEASE_DIR` offline seam), fetches + verifies `.tar.gz` via `fetch_release_asset`/`verify_sha256`, extracts to `$PREBUILT_WORK_DIR/skills-bundle`, re-points `PLUGIN_DIR`/`SKILLS_DIR`/`PLATFORM_PACKS_DIR` as plain globals in the parent shell. When `SKILLS_DIR` exists: no-op (local tree wins). reusable
- `resolve_skills_bundle_asset_name()`: walks `list_release_asset_names` output and returns first `skill-bill-skills-*.tar.gz` match; fails loudly if absent. reusable
- `clean_install_state_if_requested()`: when `CLEAN_INSTALL=1`, guards `SKILL_BILL_STATE_DIR` non-empty, then `rm -rf` `skills/`, `platform-packs/`, `orchestration/` under state dir. reusable
- Call order in `run_full_install()`: `bundle_bootstrap_if_needed` → `clean_install_state_if_requested` → `run_pre_install_uninstall` → `copy_in_authored_source`. Both calls sit between the reuse-selection block and `run_pre_install_uninstall`.
- Post-extract layout validation: after `tar -xzf`, checks `$extract_dir/skills` exists; falls back to `find -mindepth 2 -maxdepth 2 -name skills`; fails loudly with recognisable error if neither layout found. reusable
- `--clean` documented in `usage()` with "Composable with --prefer-upstream" note; no mutual-exclusion guard — both flags are independent. reusable
- Pitfall: tar layout is flat (`skills/ platform-packs/ orchestration/ uninstall.sh` at root, no versioned top-level dir) — confirmed from `release.yml` tar command. Bundle-PLUGIN_DIR reassignment works because all downstream callers (`copy_in_authored_source`, `stage_authored_candidate`) consume `SKILLS_DIR`/`PLATFORM_PACKS_DIR` globals in the same process. reusable
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-06-13] SKILL-82 subtask 1 commit-and-release-bundle
Areas: install.sh, README.md
- `--prefer-upstream` flag: auto-accepts upstream for all conflicts without prompting; `PREFER_UPSTREAM=0` variable + parser arm + usage text. reusable
- `--clean` flag stub: `CLEAN_INSTALL=0` variable + parser arm only; wipe logic deferred to subtask 2.
- No-TTY conflict behavior changed: abort→warn-and-keep-local; `accept_conflicts` stays 0, install continues; warn message MUST contain substring "no TTY is attached to prompt" (asserted by InstallerShellReconcileTest:153,201). reusable
- README: replaced `git clone && ./install.sh` quickstart with `curl -fsSL .../install.sh | bash` one-liner in Quickstart and Install details sections. reusable
Feature flag: N/A
Acceptance criteria: 1/4 parent ACs (install.sh + README; bundle download bootstrap in subtask 2, smoke test in subtask 3)

## [2026-06-11] WE-4435 prose-runtime workflow-store clarification
Areas: skills/bill-feature-task-prose, runtime-kotlin/ARCHITECTURE.md, docs
- Corrected current-state wording: `bill-feature-task-prose` is a first-class parallel implementation mode, not legacy; the `feature_implement_*` MCP tools and `feature_implement_workflows` store are stable prose-mode durable state. reusable
- Router invariant: `bill-feature-task` defaults to `mode:prose`; `mode:runtime` selects `bill-feature-task-runtime`. Do not describe prose workflow rows as fallback or legacy state when diagnosing feature-task runs.
Feature flag: N/A
Acceptance criteria: documentation/source clarification only

## [2026-06-10] SKILL-76.1 copyin-repoint-foundation
Areas: install.sh, uninstall.sh, runtime-kotlin/runtime-infra-fs (tests), runtime-kotlin/runtime-core (tests)
- Copy-in pattern: `install.sh` `copy_in_authored_source()` reuses the `install_packaged_runtime_distribution` atomic idiom (rm tmp; cp -R; rm target; mv) to copy `skills/` + `platform-packs/` + the WHOLE `orchestration/` tree into `$SKILL_BILL_STATE_DIR` as REAL files before any skill linking, then repoints `--repo-root`/`--skills`/`--platform-packs` at that copy. reusable
- AC-2/AC-4 are a PURE SHELL repoint — zero Kotlin change: `InstallStaging.resolveStagedSymlinkTarget`, `ScaffoldSupport.supportingFileTargets`, `SkillClassLoader` all resolve relative to the injected `repoRoot`; cp -R MUST produce real files because `InstallPlanBuilder.validatePointerInputs` require()s `toRealPath().startsWith(realRepoRoot)` containment (a symlink to the clone fails). reusable
- Wipe-exemption pattern: `install.sh` sets `SKILL_BILL_PRESERVE_SOURCE_ON_WIPE=1` ONLY on the pre-install uninstall exec; `uninstall.sh` split into `remove_state_dir_full()` (explicit uninstall → full `rm -rf ~/.skill-bill`) vs `remove_state_dir_preserving_source()` (preserves {skills, platform-packs, orchestration, baseline-manifest.json}, clears runtime/ + installed-skills/ + *.db). reusable
- Reserved seam for subtask 2: the baseline manifest path is `$SKILL_BILL_STATE_DIR/baseline-manifest.json` — already a live preserve-list entry so a future manifest survives the pre-install wipe. reusable
- Pitfall: changing install.sh copy-in/argv breaks SIBLING installer-shell tests — must update BOTH `InstallerShellDelegationTest` AND `InstallerShellReuseLastSelectionTest` `expectedApplyArgs` and seed real skills/+platform-packs/+orchestration/ content (all three source roots must exist or `install_packaged_runtime_distribution` errors under `set -e`). reusable
- `orchestration/` is now a hard install-time dependency (copied whole; `orchestration/contracts/*.yaml` unneeded — loaded as classpath resources).
Feature flag: N/A
Acceptance criteria: 5/5 implemented (subtask-local ACs; AC-3 reinstall-without-clone + edit-preservation deferred to subtask 2)

## [2026-06-08] SKILL-71.1 prose-goal-orchestrator
Areas: skills/bill-feature-goal, skills/bill-feature-task-prose, README.md
- Added `mode:prose` to `bill-feature-goal` (mirrors SKILL-70 `mode:runtime`/`mode:prose` convention): mode:runtime (default) drives the foreground `skill-bill goal` runtime; mode:prose loops decomposed subtasks in-session through the existing continuation seam. reusable
- Structure decision: documented mode:prose INLINE in the single `bill-feature-goal/content.md` (no router file-split, no sibling -prose/-runtime skills) — spec Design Notes mandate one orchestrator file; only the `mode:` argument convention is reused from bill-feature-task. reusable
- Prose subtask entry is `skill-bill workflow continue <issue_key> --subtask-id <id>` / MCP `feature_implement_workflow_continue` with integer `subtask_id`; durable outcome is written by the existing bill-feature-task-prose goal-continuation worker (no second record-writing path/store). reusable
- Flag-name pitfall: CLI flag is `--subtask-id`, not `--subtask` — verify continuation flag names against `WorkflowCliCommands.kt`, not spec prose. reusable
- Load-bearing test: `FeatureSpecSkillWiringContractTest.kt:47-57` asserts exact substrings in bill-feature-goal/content.md AND `Ask one confirmation question` exactly once — both goal modes must share the single confirmation gate; preserve those strings when editing. reusable
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-06-07] SKILL-70 feature-task-mode-router
Areas: skills/bill-feature-task, skills/bill-feature-task-prose, skills/bill-feature-task-runtime, skills/bill-feature, skills/bill-code-review, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-application
- Split the feature-task skill surface into router/prose/runtime tiers: `bill-feature-task` accepts `mode:prose` (default) or `mode:runtime`, `bill-feature-task-prose` owns the prose orchestrator, `bill-feature-task-runtime` owns the thin runtime-backed skill. reusable
- Relocation pattern: prose `native-agents/agents.yaml` moved byte-for-byte, legacy source directory removed, install/validation references retargeted; keep historical references only in history entries. reusable
- Parallel review runtime added: `skill-bill code-review-parallel` runs two agent lanes concurrently on the same diff; `skill-bill code-review-merge` coalesces findings with provenance labels. reusable
- CLI-to-domain boundary rule: CLI commands must route through an application service layer (e.g. `ParallelCodeReviewMergeService`), never import domain types (`skillbill.review.*`) directly — enforced by Detekt architecture rule.
- Large-diff safety: codex lane uses `codex exec - < file` (stdin pipe) not `$(cat file)` arg expansion to avoid ARG_MAX; SKILL-70.1 specced for full threshold-based CLI delegation.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-06-05] SKILL-67 subtask 4 dispatcher-docs-and-recorded-promote-decision
Areas: skills/bill-feature, README.md, AGENTS.md, runtime-kotlin/ARCHITECTURE.md, .feature-specs/SKILL-65-*, .feature-specs/SKILL-67-*, agent/decisions.md
- `bill-feature` now explicitly dispatches single-spec work only to canonical runtime-backed `bill-feature-task`, dispatches decomposed work to `bill-feature-goal`, and names `bill-feature-task-legacy` as non-dispatch fallback. reusable
- Promote decision source-of-truth stays in SKILL-65 parent spec: the dated 2026-06-05 maintainer decision was recorded under the existing authoritative section; root decisions, README, ARCHITECTURE, and AGENTS remain pointers instead of duplicating the rule. reusable
- Deprecation window source-of-truth stays in SKILL-67 parent spec as `SKILL-67 One-Release Legacy Compatibility Window`; docs should point there rather than restating window mechanics. reusable
- Validation/audit pattern for promotion docs: pair `skill-bill validate`, `npx --yes agnix --strict .`, `scripts/validate_agent_configs`, `git diff --check`, and scoped grep audits for legacy dispatch, experimental wording, and single authoritative criterion/window sources.
Feature flag: N/A
Acceptance criteria: 6/6 implemented (subtask scope)

## [2026-06-05] SKILL-67 subtask 2 skill-promotion-and-legacy-deprecation
Areas: skills/bill-feature-task, skills/bill-feature-task-legacy, skills/bill-feature-task-runtime (removed), install.sh, uninstall.sh, README.md
- Promoted `skills/bill-feature-task-runtime/content.md` to canonical `skills/bill-feature-task/content.md` (EXPERIMENTAL framing stripped, commands now `skill-bill feature-task`), git-moved the former prose orchestrator + its `native-agents/agents.yaml` to `skills/bill-feature-task-legacy/` with a loud DEPRECATED banner + canonical pointer + removal-window note, and removed the emptied runtime dir. reusable
- Skill-relocation pattern: `install.sh` enumerates skills dynamically via `--skills "$SKILLS_DIR"`, so a new skill directory is auto-discovered as a real installed skill with no install.sh edit. Only canonical-NAME changes need the alias arrays (Kotlin `renamedSkillPairs` + bash `RENAMED_SKILL_PAIRS`, kept in sync). README catalog rows must be added/repurposed or `validate_agent_configs` fails. reusable
- Frontmatter `name` must equal the directory name for both canonical and legacy skills (skill-bill validate enforces parity); legacy skill keeps the prose orchestrator body and `feature_implement_*` dependency intact (not deleted) for the deprecation window.
- Goal-continuation: install/uninstall self-refuse with exit 64, so the install refresh that would let generated wrappers pick up the new catalog is deferred to after the goal run. Validators (skill-bill validate, validate_agent_configs, agnix --strict, gradle check on touched modules) all passed; install sync intentionally skipped.
Feature flag: N/A
Acceptance criteria: 5/5 implemented (subtask scope)

## [2026-06-01] SKILL-61 subtask 4 validation-docs-operator-scenarios
Areas: runtime-kotlin/runtime-cli, runtime-kotlin/runtime-application, runtime-kotlin/runtime-infra-fs, install.sh, uninstall.sh, docs, skills/bill-feature-goal
- Added end-to-end goal observability coverage for interrupted resume, terminal cleanup, default progress, watch/status, diff stat, bounded hunks, and clean/dirty/renamed/deleted/untracked worktree activity. reusable
- Goal-continuation now propagates `SKILL_BILL_GOAL_CONTINUATION=1`; `install.sh`, `uninstall.sh`, and mutating install/link/MCP/native-agent CLI paths refuse with exit 64 so active workflow stores are not mutated mid-continuation. reusable
- Interrupted resume assertions now prove the original child workflow completes and no duplicate running child remains; terminal/stale cleanup remains workflow-store authoritative, not stdout-derived. reusable
- Docs and governed skill source describe the runtime-owned flat worker model, with nested subagents framed only as optional debug/context convenience.
- Maintainer validation passed: `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`; install sync intentionally skipped during goal-continuation.
Feature flag: N/A
Acceptance criteria: 7/7 implemented (subtask scope)

## [2026-05-31] SKILL-59 subtask 4 regression-and-maintainer-validation
Areas: runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/FeatureSpecPreparationRuntime.kt, runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/FeatureSpecPreparationWriter.kt, runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/featurespec/FeatureSpecPreparationPolicy.kt, runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/featuretask/prepare/FeatureSpecPreparationWriterTest.kt, runtime-kotlin/runtime-core/src/test/kotlin/skillbill/di/featurespec/FeatureSpecPreparationWriterValidationTest.kt, FeatureSpecPreparationRuntimeTest and FeatureSpecSkillWiringContractTest (both since removed)
- Added explicit shared-runtime coverage for `bill-feature-spec` alongside `bill-feature-task` and `bill-goal` so all three wrappers are locked to one preparation core entrypoint with equality assertions. reusable
- Hardened `single_spec` guardrails in `FeatureSpecPreparationWriter` by rejecting decomposed-only `subtasks` payloads in single-spec mode with a typed loud-fail request error. reusable
- Expanded decomposition validation coverage to assert runnable subtask specs and goal-runner-compatible manifest intent/projection wiring, not just file existence. reusable
- Strengthened wiring contract regression checks so `bill-goal` keeps exactly one confirmation gate and the consumer-only `skill-bill goal` boundary remains anchored to shared preparation language.
- Maintainer validation gate passed in this run: `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.
Feature flag: N/A
Acceptance criteria: 6/6 implemented (subtask scope)

## [2026-05-31] SKILL-59 subtask 3 skill-and-workflow-wiring
Areas: skills/bill-feature-spec/content.md, skills/bill-feature-task/content.md, skills/bill-goal/content.md, README.md, docs/getting-started.md, docs/getting-started-for-teams.md, FeatureSpecSkillWiringContractTest (since removed)
- Added new horizontal `bill-feature-spec` governed source as standalone preparation entry point, with explicit intake contract, `single_spec`/`decomposed` mode guidance, and consumer-only `skill-bill goal` boundary language. reusable
- Updated `bill-feature-task` decomposition guidance so planning returns a package and orchestrator persistence routes through the shared feature-spec preparation path instead of bespoke decomposition-write prose. reusable
- Updated `bill-goal` so missing decomposition artifacts are prepared through `bill-feature-spec` before the single confirmation gate, while preserving `skill-bill goal` as consumer-only runner behavior. reusable
- Added workflow-content contract regression test (`FeatureSpecSkillWiringContractTest`) to lock shared preparation wiring language across `bill-feature-spec`, `bill-feature-task`, and `bill-goal`.
- Updated README and getting-started docs catalogs to include `bill-feature-spec` as standalone preparation.
Feature flag: N/A
Acceptance criteria: 9/9 implemented (subtask scope)

## [2026-05-29] SKILL-55 subtask 6 launch-kit
Areas: .feature-specs/SKILL-55-launch-readiness/launch-kit/ (Reddit drafts, subreddit plan, objection FAQ, Product Hunt kit, go/no-go checklist, sequencing note)
- Capstone of the SKILL-55 launch-readiness decomposition (subtasks 1-6 now complete): assets-and-plan-only, 7 Markdown files, no code/installer change. Kit lives in-repo so it versions with the product and the go/no-go gate stays honest against what actually shipped. reusable
- Launch/marketing copy MUST be accuracy-gated against shipped reality — treat it like docs, not aspiration. Anchor every kit file to a single ground-truth fact sheet (launch-kit/README.md) and forbid stating unshipped features as shipped. reusable
- Live accuracy traps for skill-bill launch copy (all enforced here): installers are UNSIGNED v1 so the Gatekeeper/SmartScreen "won't open" FAQ is load-bearing (steps verbatim from runtime-kotlin/agent/decisions.md, Linux has no gate); the demo is a placeholder SVG, the real GIF is not recorded, so any "demo video" is PLANNED/storyboarded only; there is NO `skill-bill desktop install` subcommand (`./install.sh --desktop-app-only`); no public release tag is cut yet and .rpm/.dmg/.msi extraction is CI-unexercised, so the go/no-go checklist keeps those gates OPEN, never pre-checked. reusable
- Sequencing: Reddit first (narrative-led soft launch to sharpen the pitch) once go/no-go open items clear; Product Hunt later as the artifact-gated set-piece (all-OS prebuilt artifacts live + verified clean-machine install + recorded demo). reusable
- Docs-only validation is repo-native (`npx --yes agnix --strict .` + `scripts/validate_agent_configs`); both passed clean. agnix --strict fails on a referenced-but-missing asset, so the kit links only to on-disk assets (placeholder SVG, storyboard, capture-instructions), never the unrecorded GIF.
Feature flag: N/A
Acceptance criteria: 6/6 implemented (subtask scope)

## [2026-05-29] SKILL-55 subtask 5 front-door-readme-and-demo
Areas: README.md, docs/getting-started.md, docs/assets/ (demo placeholder + storyboard + capture instructions)
- README front door rebuilt by REORDERING (drop nothing): hero+H1 → one-sentence jargon-free hook → demo asset → ≤60s prebuilt quickstart → split prerequisites → complexity-objection paragraph; the 11-capability deep dive / architecture / reference-pack / validation all moved below the fold. reusable
- Demo asset convention: binary capture is produced out-of-band, so commit a real on-disk placeholder SVG (agnix --strict fails on a referenced-but-missing asset) plus a storyboard.md and capture-instructions.md that name the canonical swap-in filename (docs/assets/skill-bill-demo.gif). The missing binary is explicitly called out, never silently omitted. reusable
- Install docs now lead with the prebuilt default; from-source/Gradle (+JDK) is the opt-in path. README and getting-started reconciled so the pinned-install path uses install.sh `--release TAG` / `SKILL_BILL_RELEASE_TAG`, not `git clone --branch`. Prebuilt prereqs documented as curl/tar/unzip/shasum — NO JDK, NO gh.
- Spec/reality correction carried from subtask 4: there is NO `skill-bill desktop install` subcommand; the documented add-it-later one-liner is `./install.sh --desktop-app-only`. Do not reintroduce the fictional subcommand. reusable
- PRESERVE-EXACTLY landmarks for any README restructure: the 5 "Start here" docs links and the `| Skill | Purpose |` backtick-wrapped alphabetical `/bill-*` catalog table — the scaffold append path (ReadmeCatalogEdits.kt / ReadmeCatalogAppendTest) and scripts/validate_agent_configs depend on that exact shape; never renumber, re-sort, or change backtick style. reusable
- Docs-only validation is repo-native (`npx --yes agnix --strict .` + `scripts/validate_agent_configs`); bill-quality-check's Kotlin/KMP routing does not cover a Markdown-only diff.
Feature flag: N/A
Acceptance criteria: 7/7 implemented (subtask scope)

## [2026-05-29] SKILL-55 subtask 4 install-prebuilt-first
Areas: install.sh (repo-root installer), uninstall.sh, runtime-kotlin/runtime-cli InstallerShellDelegationTest
- install.sh now fetches + checksum-verifies the prebuilt per-OS runtime images by DEFAULT (no JDK, no Gradle); `--from-source` restores the old Gradle path and is also the automatic fallback when no asset matches the host token. Seam: new `install_runtime_distributions` dispatcher in front of the existing `build_kotlin_runtime_distributions`; unpacked image dirs feed the EXISTING `install_packaged_runtime_distribution` (cp→.tmp + atomic mv) unchanged. reusable
- Release assets are resolved by SUFFIX match (`runtime-cli-*<os>-<arch>.zip`, `runtime-mcp-*…`, `SkillBill-*…{deb,rpm,dmg,msi}`) via the GitHub Releases API, NOT by reconstructing the version — the CI build does not inject the tag as project.version, so filenames may embed `0.1.0-SNAPSHOT`. Default repo `oila-gmbh/skill-bill`; offline/test seam `SKILL_BILL_RELEASE_DIR` (copy, no network) + `SKILL_BILL_RELEASE_BASE_URL` (curl base, supports file://); `--release <tag>`/`SKILL_BILL_RELEASE_TAG` to pin. reusable
- Prebuilt desktop app is EXTRACTED, never system-installed: `.deb`→ar+data.tar, `.rpm`→rpm2cpio|cpio/bsdtar, `.dmg`→hdiutil attach, `.msi`→msiexec /a; the extracted app tree is fed to the EXISTING `install_desktop_app` via `DESKTOP_APP_PREBUILT_SOURCE_PATH`, so per-user install dirs + skillbill-desktop launcher + uninstall.sh stay unchanged (AC8 needed NO uninstall edit). reusable
- New hard deps detected up front: curl + tar/unzip + shasum|sha256sum (NO gh, NO JDK). Downloads use one top-level `mktemp -d` + a single EXIT-trap cleanup (registered ONCE at top level, NOT in a `$(...)` helper, or the subshell exit wipes the dir mid-install — real bug fixed in impl). reusable
- Display-gating (AC4): `desktop_session_default_yes` reports PURELY desktop-session presence (Linux DISPLAY/WAYLAND_DISPLAY; macOS/Windows always); BOTH the non-interactive default AND the interactive prompt default gate on it. Pitfall fixed in review: an earlier version `&&`-ed `-t 0` inside the helper but only called it from the non-interactive branch, making the DISPLAY check dead code and defaulting interactive-headless to yes. reusable
- `--desktop-app-only` is the documented post-install one-liner (chosen over a Kotlin `skill-bill desktop` subcommand to keep one bash implementation): skips clean-slate/runtime/prompts/apply, idempotent. AC6 transparency summary (`print_install_plan`) prints before any mutation incl. the clean-slate reset. Unsigned "open anyway" hint (macOS Gatekeeper / Windows SmartScreen, none on Linux) reused verbatim from runtime-kotlin/agent/decisions.md.
- Bash has no shell-lint gate; it is verified ONLY through `InstallerShellDelegationTest` (real install.sh/uninstall.sh via ProcessBuilder + staged-release fixtures over `SKILL_BILL_RELEASE_DIR`, no network). `.rpm/.dmg/.msi` extraction is unexercised by CI (only `.deb` on linux-x64; gated behind `assumeDebHost`) — needs a manual macOS/Windows smoke before a tagged release. Fixtures need `bsdtar`/`ar` (skip-guarded so absent tools skip, not error).
Feature flag: N/A
Acceptance criteria: 8/8 implemented (subtask scope)

## [2026-05-24] SKILL-52.1 final-validation-and-contract-lock
Areas: runtime-kotlin hexagonal runtime hardening, repo validation, governed source/generated boundaries
- Final SKILL-52.1 lock ties the runtime architecture tests, golden tests, and repository validation gates to the same governed contracts: typed boundaries, schema parse seams, inert path handling, install-plan dual validation, and runtime-core as composition only. reusable
- Source/generated boundaries remain part of the release gate: governed `content.md` stays source, while SKILL.md wrappers, support pointers, provider-native outputs, and install staging artifacts must stay uncommitted. reusable
- Final validation passed with `skill-bill validate`, `scripts/validate_agent_configs`, `npx --yes agnix --strict .`, and `(cd runtime-kotlin && ./gradlew check)`.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-05-22] scaffold-payload-cli
Areas: orchestration/shell-content-contract, runtime-kotlin/runtime-core scaffold, runtime-kotlin/runtime-cli, docs
- SKILL-50 subtask 3 extended platform-pack scaffold payloads with optional `baseline_layers`, validating references before mutation and rendering them as `code_review_composition.baseline_layers`. reusable
- Dry-run and execute now share the same planned manifest model; dry-run exposes `manifest_edit_previews`, while legacy payloads without `baseline_layers` still omit composition.
- `skill-bill show` now surfaces manifest-declared review composition with required/optional layer counts, preserving `baseline_layers[].required` semantics. reusable
- Pitfall fixed in review: composition mode support lives behind shared `unsupportedCompositionModeReason` so scaffold payload validation and manifest loading cannot drift.
- Tests cover valid write, legacy no-composition payloads, structural invalid payloads, invalid-reference rollback, non-platform-pack rejection, dry-run preview, and show visibility.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-05-17] final-integration-docs-validation
Areas: README.md, docs/getting-started.md, docs/desktop-skill-bill-app, docs/skill-source-generation.md, runtime-kotlin install/desktop validation
- Closed SKILL-45 parent traceability in user-facing docs, including native package host/toolchain limits for DMG/MSI/Deb/RPM production and Arch/CachyOS RPM or loose-distribution fallback guidance.
- Reaffirmed the install source boundary: `install.sh` and desktop first-run choices feed runtime install plan/apply, while generated `SKILL.md`, support pointers, provider-native outputs, install staging, and native package outputs remain generated artifacts. reusable
- Regression evidence maps to existing Kotlin tests for install plan/apply, shell delegation, agent/platform/telemetry/MCP selection, desktop wizard state, runtime lookup, and package task wiring.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-09] generated-skill-artifact-deletion-policy
Areas: AGENTS.md, skills/, platform-packs/{kotlin,kmp}/, runtime-kotlin/runtime-core scaffold validation/install
- SKILL-40 subtask 4 retired committed governed generated outputs: source now keeps authored `content.md`, sidecars, manifests, renderer code, and install staging behavior, while `SKILL.md` wrappers and platform.yaml pointer files are render/install output only. reusable
- Pack manifests now declare `content.md` baselines/areas/quality-check files; loader/validator paths must loud-fail invalid declared content rather than falling back to generated wrappers. reusable
- Policy added to AGENTS.md: do not commit generated governed `SKILL.md` files or platform.yaml-declared pointer files.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-08] pointer-codegen-platform-packs
Areas: runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold/, runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/scaffold/model/, runtime-kotlin/runtime-infra/skills/src/test/kotlin/skillbill/infrastructure/skills/scaffold/, platform-packs/{kotlin,kmp}/platform.yaml, platform-packs/{kotlin,kmp}/code-review/**/, platform-packs/kotlin/quality-check/**/, runtime-kotlin/runtime-core/src/main/kotlin/skillbill/nativeagent/README.md
- SKILL-39 replaced 60 hand-authored single-line "pointer" .md files with codegen driven by an additive `pointers:` block in each pack's `platform.yaml`. `PointerOperations.regenerate` mirrors `NativeAgentOperations.regenerate` (same `originalBytes`/`createdPaths` rollback hooks, atomic tmp+ATOMIC_MOVE write) and is wired into `AuthoringOperations.upgrade`; `validatePlatformPackPointers` is wired into `RepoValidationRuntime.validateRepo`. No top-level `skill-bill regenerate` exists: `upgrade` / `render` are the entry points. reusable
- Byte-deterministic rendering: `Path.relativize` from the normalized absolute repoRoot, `/` separators, no leading `./`, collapsed `//`, no trailing newline, pointers sorted by (skillRelativeDir, name). reusable
- Path traversal checked in two layers: the parser (`parsePointerEntry`/`parsePointers` in `ShellContentLoader.kt`) rejects absolute paths and `..` in `name`, `target`, and `skillRelativeDir`; the renderer re-asserts target and pointer stay under repoRoot, rejects self-reference, and rejects symlinked targets escaping repoRoot (`NOFOLLOW_LINKS` + `toRealPath()` containment). reusable
- Rule: code inside an upgrade-rollback boundary must throw via `require(...)`, not `check(...)`. `runWithUpgradeRollback` only catches `SkillBillRuntimeException`/`IOException`/`IllegalArgumentException`, so an `IllegalStateException` skips rollback. reusable
- Rule: cross-platform byte comparisons strip both `\n` and `\r` (`trimEnd('\n', '\r')`); `\n`-only normalization false-flagged every pointer on CRLF checkouts. reusable
- Migration changed 23 pointer files, not the 3 the spec predicted: 10 `..//` typo fixes plus 13 kmp addon pointers shortened by `Path.relativize` (`../../addons/X.md` instead of `../../../../platform-packs/kmp/addons/X.md`, same file).
- `ScaffoldSupport.ORCHESTRATION_PLAYBOOKS` + `supportingFileTargets()` duplicates the `pointers:` blocks at scaffold time. `validatePointerTargetParity` in `RepoValidationRuntime` asserts they agree; `pointers:` is authoritative; a TODO tracks driving `supportingFileTargets` from `pointers:`. Rule: when two registries cannot be unified in one PR, add a cross-validator that fails CI on divergence. reusable
- Orphan detection skips `addons/` and `native-agents/` subtrees structurally; the content heuristic (<=500B, single relative `.md` path regex, <=1 newline) is a second gate.
- The architecture test puts public DTOs in `runtime-domain/.../model/` (`PointerSpec`); `PointerRendering`/`PointerOperations`/`PointerValidationReport` live in `runtime-core`. ktlint single-class filenames force the name `PointerValidationReport.kt`. reusable
- Tests: `PointerRoundTripTest` (all 60 pointers byte-equal), `PointerRenderingTest`, `PointerManifestParsingTest` (12 invalid-entry shapes), `PointerOperationsTest` (idempotence, ordering, rollback bookkeeping), `PointerValidationTest` (drift/orphan/missing, excluded subtrees, multi-issue ordering).
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-08] native-agent-rendering-followups
Areas: runtime-kotlin/runtime-core/src/main/kotlin/skillbill/nativeagent/, runtime-kotlin/runtime-infra/skills/src/test/kotlin/skillbill/infrastructure/skills/nativeagent/
- SKILL-38 hardened the native-agent rendering pipeline introduced by SKILL-37: render dispatch (and `homeAgentDirs`) now lives as per-constant overrides on `NativeAgentProvider`, so adding a fifth provider is one enum entry plus its own renderer/agent-dir overrides — no central `when` switch to update. reusable
- Parser now strict: `decodeYamlScalar`/`decodeYamlDoubleQuoted` honor the renderer's YAML escape table (sourced from a single `internal val` shared by encode and decode) and `require(...)` reject unterminated quotes, embedded unescaped `"`, trailing `\`, and unknown `\X` escapes. New round-trip test walks every repo `native-agents/*.md` to lock the contract. reusable
- Renderer is platform-independent: every `appendLine` was replaced with explicit `append('\n')` so byte-exact snapshots and the `regenerate` skip-if-equal check do not drift on Windows hosts. Snapshot tests pin one plain and one quoting-triggering fixture across all four providers. reusable
- Validator (`validateNativeAgentSources`) gained a provider-agnostic body rule: a single case-insensitive regex built from `NativeAgentProvider.entries` rejects `{{#claude}}`/`{{#codex}}`/`{{#opencode}}`/`{{#junie}}` (with whitespace/case tolerance), and lowercased substrings catch `if provider ==` / `if (provider`. Rule auto-extends with the enum.
- Install cache root now `~/.skill-bill/native-agents/<slug>-<8byte-hash>/` where slug is the platform-packs parent basename sanitized to `[a-z0-9-]+ ≤32 chars` (empty slug falls back to hash-only). Hash math unchanged; existing CLI tests using a synthetic stale key remain green. reusable
- Pitfall: the public `renderNativeAgentSource` writes name/description raw (no `yamlScalar`) — round-trip is correct only because all 17 in-repo sources use plain ASCII. Future hand-edited sources with reserved chars need either yamlScalar emission here or a constraint test.
- New README at `runtime-core/.../nativeagent/README.md` documents source format, the body-provider-agnostic rule, and the install/symlink fallback (`InstallNativeAgentResult.{Linked,Skipped}` plus the Windows-developer-mode hint).
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-07] native-agent-codegen
Areas: runtime-kotlin/runtime-core/src/main/kotlin/skillbill/nativeagent/, runtime-kotlin/runtime-core/src/main/kotlin/skillbill/install/, runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold/, runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/, skills/*/native-agents/, platform-packs/*/**/native-agents/, install.sh, README.md, docs/
- SKILL-37 moved native subagent authoring to one provider-neutral `native-agents/<name>.md` source per subagent, with deterministic install-time generation for Codex TOML, OpenCode markdown, and Junie markdown. `skill-bill render` validates source renderability; `scripts/validate_agent_configs` fails if generated provider artifacts are checked into the repo. reusable
- Install renders native-agent sources into a persistent per-repo user cache under `~/.skill-bill/native-agents/<repo-key>/<provider>/` and symlinks those generated files into Codex/OpenCode/Junie agent directories. This keeps installed files self-contained without dirtying the repo during normal install. reusable
- Pitfall found by completeness audit: generated KMP UI agents still referenced repo-local markdown sidecars (`compose-guidelines.md`, add-on review files), which violates the installed-file self-contained contract because install links only the generated agent file. Fixed by inlining the add-on/rubric concerns into the provider-neutral source. Future native-agent sources should avoid instructions that require sibling repo-local markdown files unless the generator inlines that content. reusable
- Scaffolding now emits only `native-agents/` source stubs for orchestrator subagents. Authors fill source TODOs, run `skill-bill render` to validate, and rely on install to generate provider-specific artifacts.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-03] python-package-removal
Areas: skill_bill/, pyproject.toml, tests/, .mcp.json, runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-core, skills/bill-quality-check, skills/bill-create-skill, skills/bill-skill-remove, skills/bill-feature-task/parsing_tolerance.md, orchestration/shell-content-contract, README.md, RELEASING.md, .gitignore
- SKILL-36 subtask 4 finalizes Python tooling retirement: deleted the entire `skill_bill/` package (12 files), `pyproject.toml`, and 10 obsolete Python tests under `tests/`. Each deleted Python test had pre-existing Kotlin coverage (CliInstallRuntimeTest, ShellContentLoaderParityTest, ScaffoldServiceParityTest, CliRepoValidationRuntimeTest, CliAuthoringParityTest, FeatureImplementWorkflowRuntimeTest, FeatureVerifyWorkflowRuntimeTest).
- Pitfall surfaced and fixed before deletion: 5 Kotlin sites used presence of `skill_bill/` as the repo-root sentinel (McpScaffoldRuntime.kt, ScaffoldCliCommands.kt, ShellContentLoaderParityTest.kt, CliAuthoringParityTest.kt, CliExternalAuthorDryRunTest.kt). Switched all five to a robust replacement marker: `Files.isRegularFile(runtime-kotlin/settings.gradle.kts)` AND `Files.isDirectory(skills)`. Reusable rule: when retiring a marker file, audit and switch every sentinel site BEFORE deletion. reusable
- RuntimeArchitectureTest.kt's exact-match Files-reference assertion (assertMcpScaffoldRuntimeOnlyUsesFilesForRepoRootDiscovery) and banned-token list must be updated together with any marker change. detekt MaxLineLength forced extracting `hasSettings`/`hasSkills` local vals in McpScaffoldRuntime.kt, which then required a third assertion line — the test became a self-aware governance gate that catches incomplete marker swaps. reusable
- Banned-token list collapsed `skill_bill.cli` and `skill_bill.mcp_server` into a single bare `skill_bill` ban — broader regression coverage for any future `skill_bill.*` reintroduction. reusable
- `.mcp.json` re-pointed from `python3 -c 'from skill_bill.launcher import mcp_main'` to the packaged Kotlin `runtime-kotlin/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp` shim, mirroring `install.sh`. The bin path requires `./gradlew :runtime-mcp:installDist` to populate; same constraint as `installer-test-runtime-bootstrap` history entry above.
- Skill prose and orchestration docs (skills/bill-quality-check, bill-create-skill, bill-skill-remove, bill-feature-task/parsing_tolerance.md, orchestration/shell-content-contract/PLAYBOOK.md and SCAFFOLD_PAYLOAD.md) re-pointed at Kotlin module names: `ShellContentLoader.loadQualityCheckContent`, `ScaffoldService.scaffold`, `ShellContentContractException` (runtime-contracts), `skillbill.install` (runtime-core).
- Governed contracts kept intact: shell contract version still 1.1; manifest-driven routing untouched; loud-fail behavior preserved via runtime-contracts ShellContentContractException; scaffold atomicity preserved via runtime-core ScaffoldService.
- Validation: bill-quality-check pass (./gradlew check + agnix --strict + scripts/validate_agent_configs); 7 → 0 failures after extracting `hasSettings`/`hasSkills` vals.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-05-03] installer-test-runtime-bootstrap
Areas: tests/test_install_script.py, install.sh, runtime-kotlin/runtime-cli, runtime-kotlin/runtime-mcp
- Fixed clean-CI installer tests after SKILL-36 moved `install.sh` to strict packaged Kotlin runtime bins: `test_install_script` still set `SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD=1`, but a fresh GitHub Actions checkout had no `runtime-cli`/`runtime-mcp` installDist outputs. reusable
- The installer remains strict: skipped builds still require executable packaged CLI/MCP bins. The test suite now builds both distributions once in `InstallScriptTest.setUpClass` only when those bins are absent, so per-test installer runs continue exercising the skipped-build path deterministically.
- Verified the fix by temporarily moving local installDist outputs away and running an installer test from that clean state; setup rebuilt the distributions and the test passed.
Feature flag: N/A
Acceptance criteria: CI installer unittest failure fixed

## [2026-05-03] feature-task-telemetry-fallback
Areas: skills/bill-feature-task/content.md, skills/bill-feature-task/reference.md, tests/test_feature_implement_routing_contract.py, Codex MCP registration
- Added a feature-task finalization guard after SKILL-36 exposed two flow failures: in-session Skill Bill MCP returned `Transport closed`, and review telemetry import was attempted with prose-only text missing `Review run ID`. reusable
- New workflow rule: health-check Skill Bill MCP before owned review/final telemetry; if the session transport is closed, call the same tool through packaged Kotlin `runtime-mcp` direct stdio and record the fallback in step artifacts. reusable
- New review telemetry guardrail: only import complete `bill-code-review` output containing metadata header lines, especially `Review run ID: <review-run-id>`; re-run/reformat the real review output instead of synthesizing a summary.
- Local Codex MCP registration was corrected from the retired Python launcher shim to direct packaged `runtime-mcp`; future installer registrations already use this path.
Feature flag: N/A
Acceptance criteria: flow reliability follow-up implemented

## [2026-05-02] enforce-skill-overrides
Areas: orchestration/shell-content-contract/shell-ceremony.md, skills/bill-feature-task/reference.md, agent/decisions.md, tests/test_feature_implement_routing_contract.py
- Closed the gap that let `.agents/skill-overrides.md` mandates be silently skipped across four 2026-05-02 runs (SKILL-33 FU2/FU3/FU4 + SKILL-34). The `shell-ceremony.md` override rule did not say the read is orchestrator-owned, did not require executing action mandates, and named no lifecycle positions. Skills that delegated the read to a subagent (e.g. `bill-feature-task` pre-planning) had mandates paraphrased into `standards_notes` and dropped; personal-graph `session_start`, `write_state`, and `write_episode` were never called. reusable
- Tightened `## Project Overrides` in `orchestration/shell-content-contract/shell-ceremony.md` (linked by 27 governed skills, so one edit propagates): the read is orchestrator-owned and not delegable; the orchestrator executes action mandates at the position the bullet names; mandates are detected by substring (`mcp__*` tool calls, `read \`<path>\`` files, `write_state`/`write_episode` writes); two positions exist (preflight before the skill body, postflight before terminal `*_finished` telemetry); results are recorded in a structured `actions_executed` shape. Subagents surface mandates verbatim and never execute them. reusable
- `skills/bill-feature-task/reference.md` pre-planning briefing now requires verbatim copy plus structured surfacing; the pre-planning RESULT JSON gains `override_action_mandates` (`must_call_tools`, `must_read_files`, `must_write_paths`, `lifecycle_position_notes`, `raw_override_block`) so mandates survive the subagent boundary. reusable
- Decision recorded in `agent/decisions.md` as `[2026-05-02] orchestrator-owned-skill-override-enforcement`: override enforcement is a ceremony-layer concern, not per-orchestrator.
- Pitfall: orchestration ceremony belongs in `shell-ceremony.md` (or `SKILL.md` for descriptor concerns), never in `content.md`, which is the user-editable execution body. An early attempt put Step 0 / Step 9b in `content.md` and was reverted. reusable
- Predecessor: `[2026-05-02] remove-model-only-supported-agent` surfaced the gap. Every skill linking `shell-ceremony.md` inherits the enforcement with no per-skill work.
Feature flag: N/A
Acceptance criteria: 8/8 implemented (Step 0 / Step 9b expressed as ceremony-layer preflight/postflight rather than orchestrator-specific steps; the spec's content.md-targeted ACs were satisfied by the equivalent shell-ceremony.md change — see verification.md for plan deviation note)

## [2026-05-02] remove-model-only-supported-agent
Areas: skill_bill/install.py, runtime-kotlin/runtime-core/src/main/kotlin/skillbill/install/InstallPrimitives.kt, install.sh, uninstall.sh, config.yaml, README.md, docs/getting-started.md, scripts/skill_repo_contracts.py, orchestration/review-delegation/PLAYBOOK.md, skills/bill-create-skill/SKILL.md, skills/bill-feature-task/parsing_tolerance.md, tests/
- Removed a model-only family as a first-class Skill Bill install target after user reconsideration on 2026-05-02: model families are not CLI harnesses, and the speculative command install path matched no real CLI tool. reusable
- Dropped the retired target from `SUPPORTED_AGENTS` in both runtimes (`skill_bill/install.py` and `runtime-kotlin/runtime-core/.../InstallPrimitives.kt`), from `install.sh` (case branch, supported-agents echo, MCP register dispatch), from `config.yaml`, and from the review-delegation contract (`scripts/skill_repo_contracts.py` REVIEW_DELEGATION_REQUIRED_SECTIONS plus the matching H2 in `orchestration/review-delegation/PLAYBOOK.md` and the synthetic playbook fixture in `tests/test_validate_agent_configs_e2e.py` — these three must change atomically because the validator gate fails mid-stream otherwise). Canonical insertion order across both runtimes is `(copilot, claude, codex, opencode)` — NOT alphabetical; preserve this on future ordered-tuple edits.
- `uninstall.sh` kept an idempotent retired-target cleanup branch with a one-line operator note explaining that model-family use through Claude Code installs is unaffected, plus a `TODO(SKILL-34-followup)` to drop the cleanup branch after one deprecation window. The uninstall test seeded a legacy symlink and asserted removal — this was the explicit cleanup-proof contract from AC #8. reusable
- Description-string sweep dropped the retired target from `skills/bill-create-skill/SKILL.md` runtime list and `skills/bill-feature-task/parsing_tolerance.md` runtime enumeration; README.md and `docs/getting-started.md` removed the bullet/table row and added a short prose note that model-family use through Claude Code needs no separate install target.
- Future direction: if first-class support for a concrete CLI is wanted later, file it as a new real-agent target rather than reviving the retired model-only target. The deprecation cleanup branch was the only remaining retired-target reference in production paths and should be removed in the SKILL-34 follow-up once the deprecation window closes.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-05-02] create-skill-scaffolds-subagents
Areas: skill_bill/scaffold.py, skill_bill/scaffold_template.py, skills/bill-create-skill/content.md, orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md, tests/test_scaffold.py
- Extended the new-skill scaffolder so orchestrator kinds (`horizontal`, `platform-override-piloted`, `platform-pack`) can declare specialist subagents at scaffold time via two new payload fields: `subagent_specialists: list[str]` (default `[]`) and `no_subagents: bool` (default `false`). When non-empty (and not suppressed), the scaffolder emits one Codex TOML stub + one OpenCode markdown stub per specialist under `<orchestrator-skill-dir>/codex-agents/*.toml` and `<orchestrator-skill-dir>/opencode-agents/*.md`. Validation rejects non-list/non-string entries, names that violate `^[a-z][a-z0-9-]*$`, duplicates, the `no_subagents=true` + non-empty list combo, and any use on leaf kinds (`code-review-area`, `add-on`). reusable
- Added two new template renderers in `scaffold_template.py`: `render_codex_agent_toml_stub(name, parent_skill)` produces top-level `name`/single-line `description`/`developer_instructions` with a TODO body and a free-text `Specialist contract pointer` to `specialist-contract.md`; `render_opencode_agent_md_stub(name, parent_skill)` produces YAML frontmatter (`name`/`description`/`mode: subagent`) plus a TODO markdown body. Stubs land under `skills/`-tree paths which are exempt from the F-XXX Risk Register assertion (`test_developer_instructions_inline_specialist_contract` and `test_markdown_agents_inline_specialist_contract` in the test files restrict that check to `platform-packs/` only) — so stubs intentionally do NOT emit literal `[F-001]`/`Shared Contract For Every Specialist`/`Shared Report Structure` markers. reusable
- Added `render_subagent_spawn_runtime_notes(orchestrator_name, specialists)` that emits a runtime-neutral `## Subagent Spawn Runtime Notes` section mirroring the Kotlin baseline (`platform-packs/kotlin/code-review/bill-kotlin-code-review/content.md:102-108`) — Claude `Agent` tool, Codex name resolution + `agents.max_threads`/`agents.max_depth`, OpenCode markdown name resolution and `@<name>` invocation. The section auto-appends to the orchestrator's `content.md` in `scaffold()` for both the platform-pack baseline and the non-platform-pack content path. Fan-out > 6 emits the Codex `agents.max_threads = 6` wave caveat. reusable
- Pitfall: do NOT widen the `CLAUDE_ONLY_FORBIDDEN` token tuples in `tests/test_codex_agents_toml.py` / `tests/test_opencode_agents_md.py`; stubs must avoid `Agent(subagent_type=`, `Task tool`, `subagent_type=`, `Agent tool`, `general-purpose`, `@agent-`. Pitfall: when emitting stubs for `platform-pack`, attach to the baseline_skill_path only — NOT to the specialist or quality-check skill paths.
- Predecessors: stacked on `[2026-05-02] multi-runtime-subagents-feature-task-{foundation,authoring,validation}` (install primitive `discover_*` walks `skills/<slug>/{codex,opencode}-agents/` so emitted stubs are auto-installed by `install.sh`), `[2026-05-02] multi-runtime-kotlin-review-feature-verify-subagents` (the Kotlin runtime-notes wording shape this scaffolder mirrors), and `[2026-05-02] codex-native-subagents`/`[2026-05-02] opencode-native-subagents` (the authoring conventions for TOML and MD agent files). Together these complete SKILL-33 follow-up 4: any future orchestrator skill can declare its specialists at scaffold time and ship the multi-runtime native-subagent layout from day one.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-02] multi-runtime-subagents-feature-task-validation
Areas: tests/test_codex_agents_toml.py, tests/test_opencode_agents_md.py, README.md, docs/getting-started.md, docs/getting-started-for-teams.md, .feature-specs/SKILL-33-multi-runtime-subagents-feature-task/smoke_test_results.md
- Extended `tests/test_codex_agents_toml.py` and `tests/test_opencode_agents_md.py` to walk both `platform-packs/` and `skills/` via the `discover_codex_agent_tomls` / `discover_opencode_agent_mds` install primitives (with `skills_root=SKILLS_DIR`), so the file-shape gate now covers the 14 new feature-task subagent files alongside the existing Kotlin/KMP review specialists. reusable
- Universal contract assertions added: filename stem matches `name`, `description` is single-line and non-empty, body contains zero of `Agent tool` / `subagent_type=` / `general-purpose` / `Agent(subagent_type=` / `Task tool` (Claude-only tokens). The pre-existing `[F-001]` / `Shared Contract For Every Specialist` / `Shared Report Structure` assertion is now scoped to `platform-packs/` only — feature-task workflow-phase subagents have no F-XXX Risk Register contract and would otherwise fail the assertion. Pitfall: do NOT widen `CLAUDE_ONLY_FORBIDDEN` beyond exact-substring tokens; do NOT extend the F-XXX assertion to `skills/`.
- Added a `test_feature_implement_subagents_discovered_from_skills_root` test in both files that explicitly asserts the 7 expected feature-task stems are present in the `skills/`-tree subset of the discovery walk; this fails loudly if the install primitive's `skills_root` arg is dropped from either test. reusable
- Added a `test_feature_implement_briefing_structural_shape` test that asserts each of the 7 feature-task TOMLs and 7 markdown files contains at least one `{placeholder}` token AND either a literal `RESULT:` block or a "return contract" reference (the implementation-fix variant inherits via "Return the standard implementation return contract" instead of restating a `RESULT:` block — that is the documented design and the test accepts both shapes).
- Codex CLI smoke test executed end-to-end: `bash install.sh` selecting codex+opencode + all platform packs, verified 7+7 symlinks resolved to `skills/bill-feature-task/{codex,opencode}-agents/` (NOT platform-packs), then ran `codex exec --skip-git-repo-check --sandbox read-only -C /tmp ...` on a minimal disposable prompt (3-criteria sign-out button) to prove Codex 0.128.0 boots cleanly with the 14 new agents on disk. Full per-phase run NOT exercised — that exceeds smoke-test budget. Artifact: `.feature-specs/SKILL-33-multi-runtime-subagents-feature-task/smoke_test_results.md`. reusable
- Workflow-state resume posture documented: support contract is intra-runtime resume only (any runtime that registers the skill-bill MCP server can call `feature_implement_workflow_continue` against the shared `~/.skill-bill/review-metrics.db`). Cross-runtime resume is technically possible because the DB is shared but is best-effort and out of scope for AC6 — runtime-specific spawn semantics differ enough that mid-phase handoff is not guaranteed. reusable
- README.md and `docs/getting-started.md` / `docs/getting-started-for-teams.md` updated to advertise `bill-feature-task` as a multi-runtime workflow alongside the Kotlin/KMP review specialists, with explicit references to the `skills/<slug>/**/codex-agents/*.toml` walk root and `skills/bill-feature-task/parsing_tolerance.md` for runtime-specific RESULT-block tolerance.
- Predecessors: stacked on `[2026-05-02] multi-runtime-subagents-feature-task-authoring` (14 subagent files) and `[2026-05-02] multi-runtime-subagents-feature-task-foundation` (discovery primitive + skills_root wiring + parsing_tolerance.md). Together these three subtasks complete SKILL-33 follow-up 3.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-02] multi-runtime-subagents-feature-task-authoring
Areas: skills/bill-feature-task/codex-agents/, skills/bill-feature-task/opencode-agents/, .agnix.toml
- Authored 14 native subagent definition files (7 Codex TOML + 7 OpenCode markdown) at `skills/bill-feature-task/{codex-agents,opencode-agents}/` covering every phase that runs as a subagent today: pre-planning, planning, implementation, implementation-fix (fix-loop variant), completeness-audit, quality-check, pr-description. Each file inlines the corresponding briefing fenced body from `skills/bill-feature-task/reference.md` VERBATIM, preserving `{placeholder}` tokens (`{feature_name}`, `{plan_json}`, etc.) for orchestrator substitution at spawn time. reusable
- Mirrored canonical Kotlin code-review prior art structurally: Codex TOML uses top-level `name` + `description` + triple-double-quoted `developer_instructions` (mirror `bill-kotlin-code-review-architecture.toml`); OpenCode MD uses unquoted YAML frontmatter (`name`, `description`, `mode: subagent`) followed by a blank line and the briefing body verbatim (mirror `bill-kotlin-code-review-architecture.md`). Filename stem == `name` field for all 14 files. NO F-XXX Risk Register markers (review-specialist-only contract); these workflow-phase subagents return their own briefing-defined `RESULT:` JSON shapes.
- Did NOT wrap OpenCode body in a triple-backtick fence (initial implementation chose to fence-wrap to suppress agnix `xml_balance` warnings on `<placeholder>` tokens; corrected in code-review fix-loop). Instead extended `.agnix.toml` `exclude = [...]` with two new entries: `skills/bill-feature-task/opencode-agents/**` (briefing placeholders are intentional, not unclosed XML) and `.feature-specs/**` (spec narratives are author-facing design docs and legitimately reference absolute paths and runtime install dirs). reusable
- Foundation from subtask 1 (discovery walks `skills/<skill>/{codex,opencode}-agents/`, runtime-neutral spawn-by-name in reference.md, parsing_tolerance.md Resolution A) carried through unchanged. Existing tests still pass (220/220); the file-shape gate at `tests/test_codex_agents_toml.py` and `tests/test_opencode_agents_md.py` does NOT yet scan the `skills/` tree — subtask 3 will extend discovery and assert universal contracts (filename==name, mode=subagent, no Claude-only tokens) on the new files.
- Branch reused after PR #93 merged; this commit stacks on PR #94 (foundation). PR for this subtask must wait for #94 to merge before landing cleanly on main.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-02] multi-runtime-subagents-feature-task-foundation
Areas: skill_bill/install.py, skill_bill/__main__.py, install.sh, uninstall.sh, skills/bill-feature-task/
- Extended `discover_codex_agent_tomls` and `discover_opencode_agent_mds` (plus their uninstall counterparts) with an additive optional `skills_root: Path | None = None` so subagent definitions co-located with author-owned skills (e.g. `skills/bill-feature-task/codex-agents/`) are picked up alongside platform packs. Dedup via `dict[Path, None]` insertion order then `sorted()`. reusable
- Wired the new `--skills` CLI argument through all four agent subcommands in `__main__.py`, and extended the `install.sh` / `uninstall.sh` shell fallbacks to walk `$SKILLS_DIR/**/{codex,opencode}-agents/*` in lockstep with the Python primitive — Python invocation always passes `--skills "$SKILLS_DIR"`, the discover_* primitives tolerate non-existent dirs.
- Rewrote the one remaining Claude-specific spawn instruction in `skills/bill-feature-task/reference.md:180` to runtime-neutral spawn-by-name. Greps confirm zero remaining `Agent tool` / `subagent_type=` / `general-purpose` occurrences in `reference.md` or `content.md`.
- Authored `skills/bill-feature-task/parsing_tolerance.md` adopting Resolution A (best-effort recovery, single corrective re-spawn, then escalation) and cross-linked it from the Error Recovery section in `reference.md`. Documents that the orchestrator parses `RESULT:` blocks inline; no machine parser exists in `skill_bill/`. reusable
- Foundation for SKILL-33 follow-up 3 subtasks 2 (authoring 14 native subagent files) and 3 (test extensions + Codex smoke test). Existing tests still pass (220 OK); no regression in Kotlin/KMP pack discovery.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-02] multi-runtime-kotlin-review-feature-verify-subagents
Areas: platform-packs/kotlin/code-review/bill-kotlin-code-review/, skills/bill-feature-verify/, README.md, docs/, agent/history.md
- Extended the SKILL-33 Codex/OpenCode native-subagent precedent from KMP into `bill-kotlin-code-review`: eight Kotlin specialist definitions now ship as Codex TOML and OpenCode markdown under the Kotlin pack, each inlining the specialist content plus the shared F-XXX Risk Register contract because installed native agents cannot rely on repo-local sibling sidecars. reusable
- Kept the restored Kotlin backend review depth from [2026-04-18] intact: architecture and platform-correctness remain the baseline specialists, while security, performance, testing, api-contracts, persistence, and reliability can all be selected without collapsing backend/server coverage to fit one native wave.
- Added runtime-neutral Kotlin orchestrator notes for Claude/Codex/OpenCode spawning and documented stable fan-out chunking: Kotlin can select up to 8 specialists, so Codex/OpenCode runs should use waves of at most 6 specialists and never drop backend/server specialists just to fit one wave.
- Verified `bill-feature-verify` has no verify-specific specialist delegates; it continues to run `bill-code-review` as the child review path while feature-flag, completeness, and verdict audits stay inline through `audit-rubrics.md`. No verify-specific native agent files were created.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-02] opencode-native-subagents
Areas: skill_bill/install.py, skill_bill/__main__.py, install.sh, uninstall.sh, platform-packs/kmp/code-review/bill-kmp-code-review/, README.md, docs/, tests/
- Mirrored the SKILL-33 Codex native subagent precedent for OpenCode: added `~/.config/opencode/agents/` markdown-agent install primitives, CLI delegates, and shell fallbacks that discover `platform-packs/<slug>/**/opencode-agents/*.md` without hardcoding pack slugs. reusable
- Authored OpenCode markdown agents for the 2 KMP specialist reviewers with filename/name parity, required `mode: subagent` frontmatter, and the shared F-XXX Risk Register contract inlined because global OpenCode agents cannot rely on sibling repo sidecars. reusable
- Kept `bill-kmp-code-review` runtime-neutral and documented that OpenCode resolves specialists by installed markdown name, including manual `@<name>` invocation; OpenCode has no documented conflicting concurrency cap, so the conservative <=6 specialist-wave limit remains.
- Extended install/uninstall and markdown validation coverage alongside README/getting-started docs; baseline Kotlin and other orchestrators remain out of scope.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-02] codex-native-subagents
Areas: skill_bill/install.py, skill_bill/__main__.py, install.sh, uninstall.sh, platform-packs/kmp/code-review/bill-kmp-code-review/, README.md, docs/, tests/
- Added a `_codex_agents_path` primitive in `skill_bill/install.py` that mirrors the existing `_codex_path` skills primitive: prefers `~/.codex/agents/`, falls back to `~/.agents/agents/`. Exposed it as a secondary Codex install surface (`detect_codex_agents_target`) and added manifest-driven discovery (`discover_codex_agent_tomls`) plus install/uninstall helpers that walk `platform-packs/<slug>/**/codex-agents/*.toml` rather than hardcoding any pack slug. Reuses the same `InstallTransaction` model rather than introducing a parallel transaction system. reusable, follows [2026-04-17] new-skill-scaffolder precedent.
- Wired `install.sh` and `uninstall.sh` to delegate Codex subagent TOML symlinking to the Python module via `python3 -m skill_bill install link-codex-agents` / `unlink-codex-agents`, with inline manifest-driven shell fallbacks (`shopt -s globstar` over `platform-packs/**/codex-agents/*.toml`) so fresh-machine bootstrap and uninstall both work before Python deps are installed. Mirrors the [2026-04-11] opencode-agent-support docs/install precedent.
- Authored TOML subagent defs for the 2 KMP-specific specialists (`bill-kmp-code-review-ui`, `bill-kmp-code-review-ux-accessibility`) under `platform-packs/kmp/code-review/bill-kmp-code-review/codex-agents/`. The F-XXX Risk Register bullet contract from `specialist-contract.md` is inlined VERBATIM into each `developer_instructions` block because Codex agents cannot follow sibling symlinks. No new JSON envelope was introduced; baseline Kotlin specialists remain out of scope per the spec.
- Verified `bill-kmp-code-review`'s orchestrator prose is already runtime-neutral (no `Agent(subagent_type=...)` or `Task tool` literals) and appended a one-paragraph note explaining how Codex resolves each spawn by `name` against installed TOMLs in `~/.codex/agents/` (with `~/.agents/agents/` fallback), plus a one-line statement that KMP fan-out is at most 2 specialists per wave (well under Codex's `agents.max_threads = 6`).
- Documented the new install path and natural-language spawn caveat in `README.md`, `docs/getting-started.md` (added a row to the install-path table), and `docs/getting-started-for-teams.md` (added a paragraph in Runtime Expectations explaining that other orchestrators still run inline on Codex until follow-up issues ship their TOMLs).
- Test coverage: `tests/test_install_script.py` now creates `~/.codex/agents/` in `prepare_agent_homes` and asserts both pilot TOML symlinks materialize when codex is selected; `tests/test_uninstall_script.py` seeds TOMLs into BOTH `~/.codex/agents/` and `~/.agents/agents/` and verifies uninstall removes them via the manifest-driven fallback path; new `tests/test_codex_agents_toml.py` walks `platform-packs/**/codex-agents/*.toml`, parses each with `tomllib`, and asserts required-field presence, `name` matches filename stem, names unique, and no Claude-only references (`Agent(subagent_type=`, `Task tool`).
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-05-01] python-runtime-retirement
Areas: skill_bill.launcher, pyproject entrypoints, docs/migrations, tests
- Retired Python CLI/MCP runtime selection: `skill-bill` and `skill-bill-mcp` now enter packaged Kotlin through `skill_bill.launcher`, and removed runtime env vars have no effect.
- Deleted the Python runtime entry files and their active Python-side runtime tests after the Kotlin CLI/MCP contract net and 3b bridge teardown covered normal-use paths. reusable
- Rollback guidance changed to install the previous release; do not reintroduce a Python runtime selector for rollback.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-04-30] feature-task-large-work-decomposition
Areas: skills/bill-feature-task/, runtime-kotlin/runtime-domain/, tests/
- Added a planning-stage decomposition mode for oversized `bill-feature-task` runs: Step 3 now returns either `mode: "implement"` or terminal `mode: "decompose"` with ordered subtask specs and acceptance criteria. reusable
- Decomposition writes resumable `.feature-specs/.../spec_subtask_<n>_<slug>.md` handoff specs, presents dependency order, and closes before implementation as intentional planning-stage scope governance.
- Updated the Kotlin workflow definition so the primary runtime resume/continue text treats the plan artifact as either an implementation plan or a terminal decomposition package. reusable
- Known limit: full runtime-kotlin `check` still fails on an unrelated `runtime-application` detekt ReturnCount issue in `TelemetryService.autoSync`; affected `runtime-domain` checks pass.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-26] canonical-skill-md-shape
Areas: skills/, platform-packs/, scripts/validate_agent_configs.py, skill_bill/, tests/, orchestration/shell-content-contract/
- Locked SKILL.md to a single canonical shape across every skill: frontmatter + `## Descriptor` + `## Execution` + `## Ceremony` only, with a body banlist (fenced code, tables, `## Step N:` headings, embedded templates, install gates, telemetry instructions, routing rules, run-context placeholders). All substantive prose moves to content.md. reusable
- Reverses the 2026-04-21 feature-task/verify-shell-pilot direction (step prose lived in SKILL.md): SKILL.md is now scaffold-only and content.md owns step prose with inline `Step id: \`X\`` bindings cross-checked against `skill_bill/constants.py`. reusable
- New `validate_skill_md_shape` (loud-fail via `InvalidSkillMdShapeError`); existing validators retargeted from SKILL.md to content.md (`FEATURE_IMPLEMENT_SHELL_REQUIRED_MARKERS`, `validate_feature_implement_shell_contract`, `validate_feature_verify_shell_contract`, `validate_workflow_driven_skills`). `PROJECT_OVERRIDES_HEADING` enforcement dropped; `.agents/skill-overrides.md` rule inherits via shell-ceremony.md. reusable
- Family taxonomy locked at 4 (`code-review`, `quality-check`, `workflow`, `advisor`) with no path-based exceptions; `HORIZONTAL_SKILL_FAMILIES` lookup added in `skill_bill/upgrade.py` so `skill-bill upgrade` cannot silently corrupt migrated SKILL.md files. reusable
- Known limit: `bill-editorial-assignment-desk` (lives on unmerged SKILL-30 branch) is out of scope; canonical shape applies there as a SKILL-30 follow-up. 9 deferred Minor validator/test cleanups (F-002 through F-010) tracked in workflow review_result.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-04-25] editorial-workflows-readian
Areas: skills/bill-editorial-assignment-desk/, runtime-kotlin/runtime-mcp/, scripts/, tests/, README.md
- Added the first non-coding governed workflow as `bill-editorial-assignment-desk`: a skill-only Readian-backed editorial assignment desk with stable step ids/artifacts, candidate-board pause, and story-pack boundary. reusable
- Established editorial contracts for ranking, source verification, social signal, ethics/risk, and selected story packs without introducing platform packs or durable editorial workflow state yet. reusable
- Aligned Readian MCP boundary tools with the real client fetch modes (`readian_get_spotlight`, `readian_get_articles_for_topic_query`) plus recursive secret redaction so auth/session material stays below MCP and unauthenticated calls return `auth_required`. reusable
- Validator and tests now pin editorial workflow markers, source/ranking contract markers, Readian auth-required behavior, token redaction, and README catalog presence.
Feature flag: N/A
Acceptance criteria: 15/15 implemented

## [2026-04-23] kotlin-runtime-port phase 4
Areas: runtime-kotlin/, docs/migrations/SKILL-27-kotlin-runtime-port.md, .feature-specs/SKILL-27-surface-integration/
- Replaced the marker-only `runtime-kotlin` CLI and MCP surfaces with real adapters for the review/learnings/stats/telemetry slice while keeping command/tool names, payload fields, and orchestrated review semantics aligned with the Python oracle. reusable
- Split the CLI port into command-support files (`CliReviewCommands`, `CliLearningCommands`, `CliTelemetryCommands`, output/helpers) so later phases can extend the surfaced command tree without rebuilding one monolith. reusable
- Added Kotlin-side telemetry config mutation helpers plus parity tests for representative CLI outputs, MCP payloads, remote-stats proxy traffic, and alternate `userHome` config resolution. reusable
- Known limit: production `skill-bill` / `skill-bill-mcp` entrypoints, workflow runtime, loader/scaffolder, install behavior, and launcher/cutover wiring remain Python-owned after this phase.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-22] kotlin-runtime-port phase 1
Areas: runtime-kotlin/, docs/migrations/SKILL-27-kotlin-runtime-port.md, agent/history.md
- Added a standalone JVM-only `runtime-kotlin/` Gradle module with local wrapper scripts, JDK 17 toolchain setup, version-catalog dependency management, a local `build-logic/` included build, and a package scaffold for future CLI, MCP, DB, telemetry, workflow, scaffold, and install ports. reusable
- Added shared Kotlin contract/error primitives plus initial smoke tests so later subsystem ports can reuse one local foundation instead of inventing per-area scaffolding. reusable
- Wired module-local quality gates from day one: 2-space Kotlin formatting via `.editorconfig`, `spotless` for formatting, and `detekt` for static analysis, all validated through the wrapper-based Gradle path. reusable
- Recorded the Phase 1 carryover in `docs/migrations/SKILL-27-kotlin-runtime-port.md`, keeping Python as the active runtime source of truth and pointing the next session at Phase 2 persistence work.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-04-21] feature-verify-shell-pilot
Areas: skills/bill-feature-verify/, scripts/, tests/, README.md, docs/getting-started-for-teams.md
- Split `bill-feature-verify` into a workflow shell in `SKILL.md` and an author-owned `content.md`, keeping workflow-state, continuation, stable artifact names, and telemetry ownership in the shell while moving phase-by-phase verify guidance into the sibling content file. reusable
- Added a dedicated validator hook for `bill-feature-verify` so missing `content.md`, missing shell workflow markers, or a missing execution pointer fail loudly without widening the generic workflow-shell rules yet. reusable
- Extended contract coverage and docs so fixture repos, acceptance/rejection tests, README, and team docs now treat `bill-feature-verify` like `bill-feature-task`: shell-owned contract in `SKILL.md`, authored behavior in `content.md`. reusable
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-04-21] feature-task-shell-pilot
Areas: skills/bill-feature-task/, scripts/, tests/, README.md, orchestration/workflow-contract/
- Split `bill-feature-task` into a workflow shell in `SKILL.md` and an author-owned `content.md`, keeping workflow-state, continuation, stable artifact names, and telemetry ownership in the shell while moving phase-by-phase execution guidance into the sibling content file. reusable
- Keep `commit_push_result` as a reserved shell-owned artifact name until runtime persistence actually exists; document the name in the shell/reference contract but do not widen workflow storage just to satisfy the docs split. reusable
- Add a dedicated validator hook for `bill-feature-task` so missing `content.md`, missing shell workflow markers, or a missing execution pointer fail loudly even though this workflow pilot does not use the generic governed wrapper contract. reusable
- Cover the split with both routing-contract assertions and validator fixture failures so future prompt edits cannot quietly collapse the shell back into a single authored file. reusable
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-04-21] feature-task-workflow-runtime
Areas: skill_bill/, skills/bill-feature-task/, orchestration/workflow-contract/, tests/
- Added a durable `bill-feature-task` workflow runtime with persisted workflow ids, step state, attempt counts, named artifacts, and a dedicated `feature_implement_workflows` store behind CLI and MCP surfaces for open/get/update/list/latest/resume/continue. reusable
- Wired `bill-feature-task` itself to treat workflow state as authoritative for phase handoff and continuation, so interrupted runs can resume from persisted `assessment`, `plan`, `implementation_summary`, `review_result`, `audit_report`, and adjacent artifacts instead of reconstructing state from chat history. reusable
- Standardized continuation as a governed contract: resume/continue now return step-specific directives, required/missing artifact checks, recovered session context, and a continuation entry prompt for re-entering the skill at `plan`, `implement`, `review`, `audit`, `validate`, or terminal cleanup. reusable
- Added deterministic agent-resume tests plus opt-in subprocess E2E coverage across CLI and MCP, including `--latest`, latest-order parity, telemetry-linked sessions, blocked/missing-artifact cases, abandoned/completed terminal states, and review/audit loop recovery. reusable
- Known limit: continuation is now operational and testable, but the highest-fidelity coverage still uses a governed harness rather than a live model in default CI.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-21] workflow-contract-pilot
Areas: orchestration/, README.md, docs/, agent/
- Added a governed `workflow-contract` playbook for top-level orchestrators so long-running parent commands now have one source of truth for step ids, artifact handoff, retry/resume rules, and parent-owned telemetry. reusable
- Drew a hard boundary between reusable skills and workflows: leaf skills stay standalone, routed, and portable; only top-level orchestrators such as `bill-feature-task` should adopt workflow state. reusable
- Wired the new contract into README and roadmap language so the repo now describes workflows as a governance layer on top of existing skills instead of as implicit prompt choreography.
Feature flag: N/A
Acceptance criteria: 3/3 implemented

## [2026-04-20] skill-authoring-terminal-loop
Areas: skill_bill/, README.md, tests/
- Added a terminal-first authoring loop on top of the existing split-wrapper scaffold: `skill-bill new`, guided `skill-bill edit`, `skill-bill list`, scoped `skill-bill validate`, and `skill-bill render`/`upgrade` now form the concrete single-skill workflow instead of forcing repo navigation. reusable
- The guided editor treats `content.md` as the only editable surface, preserves authored H2 order, supports replace / append / clear / skip actions per section, and regenerates only the targeted wrapper before re-running validation. reusable
- Added completion and drift inspection for content-managed skills plus targeted wrapper regeneration/validation helpers so maintainers can audit one skill at a time without running the entire repo workflow. reusable
- Maintainer-only bulk workflows remain separate: the migration script is still the path for legacy one-shot conversion, while end users stay on the single-skill terminal loop.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-04-19] collapse-skill-md-to-shared-ceremony
Areas: orchestration/shell-content-contract/, skill_bill/, scripts/, skills/, platform-packs/, docs/, tests/
- Collapsed the governed shell contract to `Descriptor` / `Execution` / `Ceremony`, with `SKILL.md` reduced to a thin wrapper and authored execution moved into sibling `content.md`. reusable
- Added shared `shell-ceremony.md`, descriptor drift validation, manifest `area_metadata`, and loud-fail checks for missing ceremony/content sidecars so governed skills now share one ceremony source of truth. reusable
- Updated the scaffolder, manifest editor, sidecar registry, repo docs, and in-repo pack fixtures to generate and validate the new shape, including platform-specific pre-shell feature stubs receiving the same shell-ceremony and telemetry sidecars as the root feature skills. reusable
- Controlled behavior stayed stable: README/catalog validation, `agnix --strict`, `validate_agent_configs.py`, and the full unit suite all pass after the reshape.
Feature flag: N/A
Acceptance criteria: 20/20 implemented

## [2026-04-18] flatten-canonical-skills-out-of-skills-base
Areas: skills/, scripts/, skill_bill/, README.md, AGENTS.md, docs/, tests/
- Moved canonical user-facing skills out of `skills/base/` and into `skills/<bill-skill>/`, leaving `skills/<platform>/...` only for legacy pre-shell platform overrides. reusable
- Updated installer and validator topology rules so root-level `skills/bill-*` directories are treated as the canonical/base set, while `skills/base/...` now counts as a legacy-invalid layout instead of a second supported home. reusable
- Retargeted moved skill sidecars, changed the scaffolder `horizontal` destination to `skills/<name>/`, and refreshed docs/tests so path assertions, fixture repos, and shell-contract references all match the flattened layout. reusable
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-18] move-governed-addons-into-platform-packs
Areas: platform-packs/kmp/addons, scripts/, skill_bill/, README.md, AGENTS.md, tests/
- Moved governed KMP add-ons into `platform-packs/kmp/addons/` so pack-owned runtime content now lives entirely under the owning platform pack. reusable
- Rewired KMP review and feature-task sidecars to the new add-on targets, and updated `scripts/skill_repo_contracts.py`, the validator, and the scaffolder so `platform-packs/<platform>/addons/` is the canonical add-on topology while the old `skills/<platform>/addons/` layout now fails validation. reusable
- Updated AGENTS/README/scaffold docs and contract tests to describe add-ons as pack-owned assets, and added fixture coverage so future topology changes must keep pack manifests, sidecars, scaffold output, and validator expectations aligned. reusable
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-18] restore-kotlin-backend-review-depth
Areas: platform-packs/kotlin, README.md, tests/
- Restored backend review depth inside the built-in `kotlin` pack instead of reviving a separate `backend-kotlin` pack: added `api-contracts`, `persistence`, and `reliability` specialist skills under `platform-packs/kotlin/code-review/`. reusable
- Updated `platform-packs/kotlin/platform.yaml` and `bill-kotlin-code-review` so backend/server signals stay on the Kotlin route but now select the backend specialists explicitly, preserving the simpler two-pack repo story while bringing server review depth back. reusable
- Expanded README and contract tests to pin the Kotlin pack at 9 code-review skills plus `bill-kotlin-quality-check`, and to assert the backend-specialist routing text so future pack simplifications do not silently drop server review coverage again. reusable
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-18] narrow-built-in-packs-to-kotlin-and-kmp
Areas: README.md, AGENTS.md, CLAUDE.md, install.sh, platform-packs/{kotlin,kmp}, removed platform-pack roots, scripts/, skill_bill/, skills/base/bill-quality-check/, tests/, docs/
- Reframed the repo as the governed skill-management system plus two built-in reference packs only: `kotlin` and `kmp`. Removed the other shipped pack roots, narrowed installer/catalog/help surfaces to the remaining built-ins, and kept `platform-packs/` as the pack home. reusable
- Folded backend Kotlin coverage back into the Kotlin baseline instead of preserving a separate built-in backend pack: Kotlin now owns backend/server routing notes, KMP always uses the Kotlin baseline, and the quality-check fallback is now only `kmp` -> `kotlin`. reusable
- Tightened shipped-surface validation to match the new product story: `scripts/skill_repo_contracts.py` only treats built-in Kotlin/KMP review skills as shipped portable-review inventory, while `scripts/validate_agent_configs.py` still discovers external scaffolded packages from live `skills/` and `platform-packs/` layouts so non-built-in stacks remain authorable. reusable
- Kept `CLAUDE.md` as a symlink to `AGENTS.md`; trimmed the shared instruction text just enough for `npx --yes agnix --strict .` to return zero warnings instead of breaking the link relationship. reusable
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-04-17] boundary-history-value-rubric
Areas: skills/base/bill-feature-task/, tests/
- Anchored the `boundary_history_value` telemetry field with a five-value rubric (`none` | `irrelevant` | `low` | `medium` | `high`) nested under step 3 of the pre-planning briefing's Instructions list in `reference.md`. Added a citation guardrail: `medium`/`high` ratings MUST cite a specific past entry in `boundary_history_digest`, otherwise downgrade to `low`. Enum, DB schema, validation, MCP signature, and platform-pack manifests all unchanged — the field is pass-through so this is a prompt-level fix, not a contract change. reusable
- Fixed `SKILL.md:171` `none` overload: clarified it means "no history existed at pre-read time" and documented that `boundary_history_written=true` paired with `value=none` is a legal combination (fresh boundary where `bill-boundary-history` creates the first entry post-completion). Full rubric stays in `reference.md`; SKILL.md keeps a one-line gloss per value.
- Motivated by a 180-day PostHog distribution on `skillbill_feature_implement_finished` (553 runs: 89% `medium`, 1.1% `high`, 0.4% `none`, 0.2% `low`, 9.4% unreported) — classic central-tendency bias from an unanchored 5-point scale. Expect post-merge distribution to shift; split the time series at merge date when analyzing.
- Added a round-trip test in `FeatureImplementEnabledTest` that loops every `BOUNDARY_HISTORY_VALUES` entry through `feature_implement_started` + `feature_implement_finished` and asserts persistence in both the `feature_implement_sessions` row and the emitted `skillbill_feature_implement_finished` outbox payload, pinning the enum tuple shape. reusable
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-04-17] quality-check-shell-pilot
Areas: orchestration/shell-content-contract/, skill_bill/, skills/base/bill-quality-check/, platform-packs/{agent-config,go,kotlin}/quality-check/, platform-packs/{agent-config,go,kotlin}/platform.yaml, scripts/, install.sh, uninstall.sh, tests/, README.md, docs/getting-started-for-teams.md, CLAUDE.md
- Moved `bill-quality-check` onto the shell+content contract additively: contract version stays `1.0` and gains an optional manifest key `declared_quality_check_file` pointing at `platform-packs/<slug>/quality-check/<name>/SKILL.md`. Packs without the key stay compliant. reusable
- Added `skill_bill.shell_content_contract.REQUIRED_QUALITY_CHECK_SECTIONS` (Description, Execution Steps, Fix Strategy, Execution Mode Reporting, Telemetry Ceremony Hooks) and `load_quality_check_content(pack) -> Path`, which raises `MissingContentFileError` (key unset or file absent) or `MissingRequiredSectionError`. No silent fallback. reusable
- Relocated the agent-config, go, and kotlin quality-check SKILL.md files via `git mv` to `platform-packs/<platform>/quality-check/bill-<platform>-quality-check/` with sibling `stack-routing.md` + `telemetry-contract.md` symlinks. `kmp` and `backend-kotlin` omit the key; the shell hard-codes a `kmp`/`backend-kotlin` → `kotlin` fallback so their behavior is unchanged.
- `skills/base/bill-quality-check/SKILL.md` is now a manifest-driven shell mirroring the `bill-code-review` shell: dominant-stack routing, resolution via `load_quality_check_content`, loud-fail with both exceptions surfaced verbatim. The prose `Delegation Rules` stack list is gone.
- Scaffolder: `PRE_SHELL_FAMILIES` drops `quality-check` (only `feature-task`, `feature-verify` remain); `SHELLED_FAMILIES`/`FAMILY_REGISTRY` promote it with template `platform-packs/{platform}/quality-check/{name}/SKILL.md`. New `skill_bill.scaffold_manifest.set_declared_quality_check_file` (idempotent, preserves key order and comments) joins the atomic flow; manifest or symlink failure rolls back the whole scaffold.
- `validate_platform_packs()` calls `load_quality_check_content` for packs declaring the key, with no enumerated platform slugs. `RUNTIME_SUPPORTING_FILES` gains entries for the three relocated skills so both sidecar symlinks are enforced. reusable
- Pattern: pure relocations that keep the skill name need no `RENAMED_SKILL_PAIRS` entry (installer walks `skills/` and `platform-packs/`, uninstaller removes by name); install.sh/uninstall.sh carry a comment documenting this.
- Tests: four new `tests/fixtures/shell_content_contract/` fixtures and five cases in `tests/test_shell_content_contract.py` (acceptance, both rejections, `None`); `tests/test_scaffold.py` adds `test_shelled_quality_check_family` and a manifest-write-failure rollback test.
- Docs now scope shell+content to `bill-code-review` and `bill-quality-check`; `bill-feature-task` and `bill-feature-verify` remain pre-shell.
Feature flag: N/A
Acceptance criteria: 19/19 implemented

## [2026-04-17] new-skill-scaffolder
Areas: skill_bill/, skills/base/bill-skill-scaffold/, orchestration/shell-content-contract/, install.sh, tests/, docs/, AGENTS.md, README.md
- Turned the SKILL-14 shell+content contract into a one-shot authoring flow. `skill_bill/scaffold.py` exposes deterministic `scaffold(payload) -> ScaffoldResult` for four kinds (horizontal, platform-override-piloted, code-review-area, add-on): edits `platform.yaml` atomically (regex text patching keeps key order and comments), wires sidecar symlinks from `RUNTIME_SUPPORTING_FILES`, runs the validator, and auto-installs into detected agents. reusable
- Loud-fail exception catalog in `skill_bill/scaffold_exceptions.py` (`ScaffoldPayloadVersionMismatchError`, `InvalidScaffoldPayloadError`, `SkillAlreadyExistsError`, `ScaffoldValidatorError`, `ScaffoldRollbackError`, `UnknownSkillKindError`, `UnknownPreShellFamilyError`, `MissingPlatformPackError`, `MissingSupportingFileTargetError`); no silent fallbacks. Payload versioned `scaffold_payload_version: "1.0"`, documented in `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`. reusable
- Atomic rollback: `_ScaffoldTransaction` records every mutation and `_rollback` unwinds install → symlinks → manifests → files → empty dirs, leaving the tree byte-identical. Tests cover validator, manifest, symlink (patched and real `Path.symlink_to`), and missing-supporting-target failures.
- Pre-shell families (`constants.py::PRE_SHELL_FAMILIES` + `scaffold.py::FAMILY_REGISTRY`) keep `quality-check`, `feature-task`, `feature-verify` on the `skills/<platform>/bill-<platform>-<capability>/` layout with a "will move when piloted" note. Adding one requires updating both in the same change. reusable
- Scaffolder-owned sections (`## Execution Mode Reporting`, `## Telemetry Ceremony Hooks`, and `## Project Overrides` for `skills/`-tree kinds) render from templates in `skill_bill/scaffold_template.py`, byte-identical within a family. Author-owned sections (`## Description`, `## Specialist Scope`, `## Inputs`, `## Outputs Contract`) ship as stubs.
- Shared install primitive `skill_bill/install.py` (`detect_agents`, `install_skill`, `uninstall_skill`, `InstallTransaction`); scaffolder and `install.sh` delegate via `python3 -m skill_bill install agent-path|detect-agents|link-skill`, with an inline shell fallback for bootstrap. Detects Claude Code, Copilot, Codex (with `~/.agents/skills` fallback), and OpenCode. reusable
- CLI `skill-bill new-skill` (`--payload`, `--interactive` no-LLM fallback, `--dry-run`) maps `ScaffoldError` subclasses to exit codes 2-6. MCP tool `new_skill_scaffold` uses the orchestrated-flag pattern; events `skillbill_new_skill_scaffold_started/_finished`. `bill-skill-scaffold/SKILL.md` is an LLM wrapper: paste → preview → yes/edit/redo → `skill-bill new-skill --payload <tempfile>`.
- Tests 217 → 236: `tests/test_scaffold.py` covers the four happy paths, payload and version rejection, rollback modes, idempotency, agent detection layouts, template byte-identity, and exit codes.
Feature flag: N/A
Acceptance criteria: 19/19 implemented

## [2026-04-16] code-review-shell-pilot
Areas: skills/base/bill-code-review/, orchestration/shell-content-contract/, orchestration/stack-routing/, platform-packs/, skills/<platform>/ (code-review relocations), skill_bill/, scripts/, install.sh, uninstall.sh, tests/, README.md, AGENTS.md, docs/
- Split `bill-code-review` into a governed shell (`skills/base/bill-code-review/`) that owns ceremony, output structure, severity scales, telemetry, and contract enforcement, and user-owned platform packs under `platform-packs/<slug>/` that own reviewer reasoning. The pilot intentionally narrows the split to `bill-code-review` only; `bill-quality-check`, `bill-feature-task`, `bill-feature-verify` remain on the pre-shell model for follow-ups. reusable
- Introduced the versioned shell+content contract at `orchestration/shell-content-contract/PLAYBOOK.md` (current version `1.0`) with a canonical sibling-sidecar link into the shell. Every pack declares `contract_version`, `routing_signals`, `declared_code_review_areas`, `declared_files` in `platform.yaml`; missing or mismatched artifacts raise specific named exceptions (`MissingManifestError`, `InvalidManifestSchemaError`, `ContractVersionMismatchError`, `MissingContentFileError`, `MissingRequiredSectionError`, `PyYAMLMissingError`) — no silent fallback. reusable
- Built `skill_bill/shell_content_contract.py` as the runtime authority: strict loader, discovery walk (`discover_platform_packs`), line-by-line H2 scan that skips fenced code blocks, lazy `_import_yaml` with a friendly missing-dep error. The loader is the single source of truth consumed by the shell, the validator, and the tests. reusable
- Rewrote `orchestration/stack-routing/PLAYBOOK.md` and `scripts/validate_agent_configs.py` to be fully manifest-driven: no platform slugs survive in routing playbooks or validator constants; `ALLOWED_PACKAGES` is computed from on-disk `skills/*` ∪ `platform-packs/*`. Tests assert AC9 continuously by enumerating live slugs and rejecting any that appear as section headings in the routing playbook. reusable
- Relocated 32 platform code-review SKILL.md files from `skills/<platform>/bill-<platform>-code-review*/` to `platform-packs/<platform>/code-review/` via `git mv` (history preserved via ≥69% rename similarity). Installer + uninstaller now walk both `skills/` and `platform-packs/`; base skills always install; platform packs are selectable.
- Test suite grew from 210 → 217 passing. Fixture-based accept/reject pattern covers 11 platform-pack shapes (valid + 10 rejection modes including extra-area, heading-in-fence, wrong-type governs_addons, unapproved area). Tag `v0.x-pre-shell-split` on `main` preserves the pre-relocation state permanently.
Feature flag: N/A
Acceptance criteria: 14/14 implemented

## [2026-04-16] sunset-legacy-feature-task
Areas: skills/base/, scripts/, orchestration/, tests/, install.sh, README.md
- Sunset the orchestrator-centric `bill-feature-task` and promoted the subagent-based `bill-feature-task-agentic` to become the canonical `bill-feature-task`. The subagent architecture (pre-planning, planning, implementation, audit, quality-check, and PR-description phases each in their own subagent) is now the default and only implementation. reusable
- Added `bill-feature-task-agentic:bill-feature-task` migration rule in `install.sh` so existing installs automatically clean up the old `-agentic` symlink on next install.
- Stripped all "experimental" and "-agentic" language from SKILL.md and reference.md. Updated validator, contracts, orchestration telemetry playbook, README catalog (49→48 skills), and routing-contract tests.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-04-15] addon-system
Areas: AGENTS.md, scripts/, orchestration/, skills/base/, platform-packs/kmp/, tests/, install.sh, README.md
- Added a governed stack-owned add-on layer under `skills/<platform>/addons/` so framework/runtime depth stays behind dominant-stack routing instead of becoming new top-level packages or slash commands. reusable
- Established the reusable pattern: runtime skills consume add-ons through sibling supporting-file symlinks, while orchestration snapshots and validator rules define naming, ownership, reporting, and test expectations. reusable
- Piloted the model in `kmp` with `android-compose` implementation/review indexes plus topic files for edge-to-edge, navigation/results, and adaptive layouts, with recipe-style source pointers back to the official Android skills. reusable
- Base feature-task and KMP review workflows now scan add-on indexes first and open only the linked topic files whose cues match the work, reducing token use while keeping Android-specific depth available. reusable
- Validator and routing-contract coverage now lock flat add-on paths under `skills/<stack>/addons/`, reject top-level/package confusion, and preserve future names such as area-scoped add-ons.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-04-13] telemetry-contract-sidecar
Areas: orchestration/telemetry-contract/, scripts/, skills/base/*, skills/<platform>/*, docs/, CLAUDE.md, README.md, tests/
- Extracted shared telemetry-contract text (orchestrated flag semantics, child_steps aggregation, Telemetry Ownership, Triage Ownership, Routers never emit, graceful degradation) from 12 telemeterable skill files into one canonical source: `orchestration/telemetry-contract/PLAYBOOK.md`.
- Each telemeterable skill now carries a `telemetry-contract.md` symlink (the 4th shared sidecar type, following the stack-routing/review-orchestrator/review-delegation convention). All 12 symlinks resolve to the same PLAYBOOK.md. reusable
- Validator updated: `validate_no_inline_telemetry_contract_drift` scans SKILL.md + reference.md for INLINE_TELEMETRY_CONTRACT_MARKERS and rejects re-inlined contract text. `validate_runtime_supporting_files` enforces sidecar presence/symlink/reference for all 12 skills via the RUNTIME_SUPPORTING_FILES registry. reusable
- PORTABLE_REVIEW_TELEMETRY_REQUIREMENTS and REVIEW_ORCHESTRATOR_TELEMETRY_REQUIREMENTS now check the telemetry-contract playbook (not SKILL.md or review-orchestrator playbook) for their required strings.
- Three new e2e tests: accept-with-sidecar, reject-without-sidecar, reject-with-inline-drift. Routing contract tests updated to verify symlink resolution.
- Skill-specific telemetry fields (feature_size, acceptance_criteria_count, review_session_id, routed_skill, etc.) stay in the respective SKILL.md files; only shared contract text moved.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-04-13] feature-task-agentic
Areas: skills/base/bill-feature-task-agentic/, scripts/validate_agent_configs.py, README.md
- Added experimental `bill-feature-task-agentic` peer of `bill-feature-task`. Same end-to-end workflow, but pre-planning, planning, implementation, completeness audit, quality check, and PR description each run inside a dedicated `Agent` subagent to keep the orchestrator context small. Code review stays in the orchestrator because `bill-code-review` already spawns specialist subagents.
- Reusable pattern: per-phase subagent briefing templates + strict `RESULT:` JSON return contracts (see reference.md). Orchestrator keeps only the structured returns in context. reusable
- Subagent runs are sequential in the same worktree (no parallelism, no worktree isolation). Quality-check and PR-description subagents are responsible for calling their MCP tools with `orchestrated=true` themselves and returning the `telemetry_payload` up to the parent.
- Registered the new skill in validator's `ORCHESTRATOR_SKILLS` so `validate_orchestrator_passthrough` enforces the `orchestrated=true` instruction for it too.
- Classic `bill-feature-task` stays as the default; agentic variant is opt-in for users willing to trade inline visibility for a smaller orchestrator context window.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-04-13] skill-telemetry-orchestration-contract
Areas: skill_bill/, skills/base/*, docs/review-telemetry.md, scripts/validate_agent_configs.py, tests/
- Introduced an `orchestrated` flag on every telemeterable MCP tool so nested skills return a `telemetry_payload` to the parent instead of emitting their own events. Reusable pattern: standalone mode persists+emits, orchestrated mode no-ops and returns a structured payload with a `skill` field.
- Added 5 new events (`skillbill_quality_check_started/_finished`, `skillbill_feature_verify_started/_finished`, `skillbill_pr_description_generated`) behind new domain modules `skill_bill/quality_check.py`, `skill_bill/feature_verify.py`, `skill_bill/pr_description.py`. Each domain module mirrors the `feature_implement.py` pattern (generate-id / validate / save / build_payload / emit). reusable
- Extended `skillbill_feature_implement_finished` with a `child_steps` list aggregated from child tool returns, stored in a new `child_steps_json` column. One user-initiated workflow now produces exactly one telemetry event.
- Retrofitted `import_review` / `triage_findings` with the same `orchestrated` flag via a new `orchestrated_run` column on `review_runs`. `update_review_finished_telemetry_state` now returns the built payload so orchestrated callers can embed it; standalone callers still emit `skillbill_review_finished` to the outbox as before.
- Added a validator rule (`validate_orchestrator_passthrough`) that every orchestrator skill's `SKILL.md`/`reference.md` must contain the literal `orchestrated=true` pass-through instruction. Pattern: silently skip when the orchestrator skill directory is absent so fixture repos for unrelated tests keep passing. reusable
- Documented the contract in `docs/review-telemetry.md` under "Session correlation" with a full event catalog and schema tables for the new events.
Feature flag: N/A
Acceptance criteria: 14/14 implemented

## [2026-04-11] opencode-agent-support
Areas: install.sh, uninstall.sh, README, skills/base/bill-skill-scaffold, tests
- Added OpenCode as a first-class installer target with skills installed into its global skills directory and included in supported-agent docs and skill-sync guidance.
- Registered Skill Bill in the OpenCode global config using the `mcp.skill-bill` local-command shape instead of the existing `mcpServers`/TOML patterns used by other agents.
- Added JSONC-aware OpenCode config handling (comments and trailing commas) so MCP registration/removal stays compatible with real user configs. reusable
- Extended installer and uninstaller regression coverage to lock the OpenCode path and MCP contract in place.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-04-05] mcp-server
Areas: skill_bill/, skills/base/bill-code-review, install.sh, pyproject.toml, tests/
- Added MCP server (FastMCP/stdio) exposing 5 tools: import_review, triage_findings, resolve_learnings, review_stats, doctor
- Tools wrap existing skill_bill.* module functions directly — zero logic duplication (reusable)
- bill-code-review SKILL.md Auto-Import/Auto-Triage now prefer MCP tools with CLI fallback
- .mcp.json in repo root for Claude Code auto-discovery; install.sh confirms registration
- pyproject.toml adds `mcp` as first external dependency
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-04-04] runtime-package-migration
Areas: skill_bill/, scripts/, skills/base/bill-code-review, install.sh, .github/workflows/, tests/
- Migrated monolithic scripts/review_metrics.py into skill_bill/ Python package with 10 domain modules (constants, db, config, review, triage, learnings, stats, sync, output, cli)
- CLI entrypoint `skill-bill` replaces direct `python3 scripts/review_metrics.py` invocations everywhere
- bill-code-review Auto-Import now calls `skill-bill import-review` instead of resolving script paths (reusable)
- install.sh telemetry setup uses `python3 -m skill_bill` for enable/disable
- pyproject.toml with zero external dependencies; CI installs via `pip install -e .`
- All behavior preserved exactly; pure structural migration
Feature flag: N/A
Acceptance criteria: 13/13 implemented

## [2026-04-02] review-acceptance-metrics
Areas: repo-root governance, orchestration/review-orchestrator, orchestration/review-delegation, skills/base/bill-code-review, stack review skills, scripts, tests, README
- Added a local-first review telemetry contract with `review_run_id` output and machine-readable `finding_id` risk-register lines for code-review flows.
- Added `scripts/review_metrics.py` as a reusable SQLite helper for importing review outputs, recording explicit accepted/dismissed/fix_requested events, and reporting stats.
- Added governance coverage so review contracts now enforce review-run id generation and delegated review-run id reuse across routed reviews.
- Documented the local telemetry workflow and default database location in README.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-04-03] review-acceptance-metrics phase 2
Areas: scripts/review_metrics.py, README, tests
- Added a number-based triage workflow so users can respond with `1 fix` or `2 skip - intentional` instead of raw finding ids.
- Added a separate local learnings layer with list/show/edit/disable/delete management commands so reusable review preferences stay user-reviewable and removable.
- Kept learnings separate from raw feedback event history so preferences can be changed or wiped without losing the telemetry baseline.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-04-03] review-learnings-application
Areas: scripts/review_metrics.py, orchestration/review-orchestrator, orchestration/review-delegation, skills/base/bill-code-review, stack review skills, README, tests
- Added scope-aware learnings resolution so active learnings can be resolved for `global`, `repo`, and `skill` review contexts with deterministic precedence. reusable
- Updated shared review contracts so routed and delegated reviews treat learnings as explicit context, pass them through delegation, and surface `Applied learnings` in the summary instead of hiding the behavior.
- Added validator and regression coverage so future review-skill edits cannot drop the auditable learnings contract silently.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-03] review-telemetry-remote-sync
Areas: scripts/review_metrics.py, install.sh, README, tests
- Added a local telemetry outbox plus optional remote batch sync so SQLite stays canonical while cross-install product analytics can be reported later.
- Added default-on installer telemetry preference handling and helper commands to inspect status, enable or disable sync, and flush pending events manually.
- Kept the remote payload privacy-scoped by excluding repo identity and raw review text while still reporting skill, feedback, and applied-learning metadata.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-03] review-telemetry-proxy-sync
Areas: scripts/review_metrics.py, install.sh, docs/cloudflare-telemetry-proxy, README, tests
- Added proxy-aware telemetry transport so installs can keep the same local outbox flow while sending batches to a configured relay.
- Added a Cloudflare Worker example that accepts Skill Bill telemetry batches, validates them lightly, and forwards them to the example backend with the credential stored server-side.
- Kept the telemetry privacy boundary: no repo identity leaves the client. Learning content is included in the `skillbill_review_finished` event.
Feature flag: N/A
Acceptance criteria: 6/6 implemented
