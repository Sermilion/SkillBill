## [2026-10-09] SKILL-413 subtask 1 code-quality lane and universal composition
Areas: orchestration/review-orchestrator, orchestration/contracts, runtime-kotlin/runtime-domain/review/plan, runtime-kotlin/runtime-domain/scaffold/policy, runtime-kotlin/runtime-infra/skills, platform-packs/generic, platform-packs/kotlin
- Added the generic code-quality specialist with language-derived idioms, cohesive state and structure guidance, project-convention precedence, concrete rewrites, Minor-only severity, a five-finding cap and no auto-fixing.
- Composition appends selected universal fallback lanes after routed lanes and reconciles shared fallback ownership across roots. Existing failure lanes retain their ordering, ownership and provenance. reusable
- Universal areas share one domain vocabulary with manifest loading and structure validation, while approved-area pack coverage and scaffolding remain separate. reusable
- Shared review contracts and structure validation recognize the quality-only severity closer. Authoring renders expose the composed lane, including Kotlin and KMP render expectations.
- Shipped optional Kotlin idioms guidance with an exact-filename baseline staging exception. Other authored companions still require explicit content links.
- Limits: the horizontal no-pack review path is unchanged; launches do not automatically supply sidecar bodies. Installed agents need parent-owned synchronization. Merger enforcement, report partitioning and telemetry belong to subtask 2.
Feature flag: N/A
Acceptance criteria: 14/14 implemented

## [2026-08-03] SKILL-159 subtask 3 governed content, docs, and packs for the restructured review modes
Areas: orchestration/review-orchestrator, orchestration/review-delegation, orchestration/skill-classes, orchestration/telemetry-contract, skills/bill-code-review, platform-packs (generic, go, ios, kotlin, php, python, rust, typescript), docs, runtime-kotlin/runtime-infra-fs
- Every installable governed surface now describes exactly three review modes: `delegated` as the default specialist fan-out, `inline` as a single-prompt review over the child-owned delta, and `auto` as first-pass-delegated with follow-up-inline.
- Removed external-process delegated-review vocabulary (provider CLIs as review workers, capability matrices, canaries, promotion gates, worker processes, waves) from skills, packs, skill-class YAMLs, orchestration playbooks, and docs; the specialist contract now backs `delegated`.
- Reusable: a banned-vocabulary sweep over installable governed content is the cheap gate that keeps mode vocabulary from regressing after a subsystem removal; pair it with the existing governed-content pinning tests rather than replacing them.
- Reusable: when a subsystem is removed but its design record is kept, a short removal preface on the historical decision doc (`docs/delegated-review/decision.md`) stops old vocabulary being read as current, so the bodies need no rewrite.
- `ReviewSkillStructureValidator` and scaffold content starters emit the new mode set, so newly scaffolded packs cannot reintroduce the retired wording; the nine dead SKILL-145 `spec_followup_*.md` planning artifacts are deleted with no live-path references.
- Known limit: `./install.sh` was not run in this phase, so the installed-catalog check for the new staging hash (AC-007) is still outstanding; run it before relying on local agent installs.
Feature flag: N/A
Acceptance criteria: 6/7 implemented

## [2026-07-12] SKILL-114 cross-pack conformance and release gate
Areas: orchestration/review-orchestrator, orchestration/stack-routing, runtime-kotlin/runtime-infra-fs, platform-packs
- Removed the temporary substance baseline and suppression paths; visible malformed pack roots now fail loudly, and all eight maintained packs pass the normal substance audit with ten effective specialist areas and directly routed quality checks.
- Reusable: boundary-aware routing combines anchored path and literal signals, ownership evidence, and signal specificity; mixed dominant evidence fails explicitly, while KMP dominates Kotlin evidence and selects its own checker without fallback.
- Final shared five-word sequence rates: Go 7.40%, iOS 2.36%, KMP 1.64%, Kotlin 5.18%, PHP 6.45%, Python 5.23%, Rust 7.24%, TypeScript 6.30% (35% maximum).
- Final maximum corresponding-rubric similarities: Go 27.76%, iOS 1.52%, KMP 2.03%, Kotlin 13.29%, PHP 27.76%, Python 13.29%, Rust 29.42%, TypeScript 29.42% (65% maximum).
- Manual review found substantive platform mechanisms and observable evidence rather than synonym substitution, API-name stuffing, arbitrary fragmentation, or generic platform-API placeholders.
- Repository validation, Gradle checks, strict Agnix, native-agent validation, snapshots, and generated-output hygiene passed; live install synchronization remains blocked by the active runtime workflow-store guard.
Feature flag: N/A
Acceptance criteria: 9/10 implemented

## [2026-07-11] SKILL-114 subtask 1 platform-pack substance standard and audit gate
Areas: orchestration/review-orchestrator, orchestration/shell-content-contract, runtime-kotlin/runtime-infra-fs
- Added a versioned, deterministic maintained-pack substance standard and audit for effective coverage, enforceable rules, failure-mode clusters, concrete evidence, placeholders, quality-check depth, and duplication thresholds. reusable
- `PlatformPackSubstanceAudit` is the single enforcement/reporting seam used by repository validation and focused fixtures; malformed manifests, invalid composition, exact thresholds, scaffold promotion, and shared-contract rendering are covered without changing third-party loader contracts. reusable
- Existing maintained-pack deficits are exact measured baseline acknowledgements owned by SKILL-114-2 through SKILL-114-9; stale or mismatched measurements fail, so no permanent exemption suppresses drift.
- Universal specialist rules are exact-parity protected against canonical PLAYBOOK sections, and scaffold starters require ten concrete rules plus three platform failure clusters while remaining unable to pass the maintained-pack gate unedited.
- Known limit: maintained packs still require the follow-on substance migrations; local install refresh remains deferred until the runtime-owned workflow completes.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-07-10] SKILL-112 subtask 1 review skill structure standard
Areas: orchestration/review-orchestrator, orchestration/shell-content-contract, runtime-kotlin/runtime-infra-fs
- Established one testable review-pack structure standard covering specialist and baseline skeletons, manifest routing/focus conventions, manifest-derived native-agent descriptions, quality checks, and authored sidecars. reusable
- Platform-pack scaffolding now completes bare/glob extension pairs and tie-breaker semantics, emits concrete specialist routing plus area-specific `Blocker or Major` consequence closers, and requires exact native-agent specialist coverage. reusable
- `ReviewSkillStructureConformanceTest` is the reusable enforcement seam for governed H2 structure, closed severity ratings, routing coverage, native-agent coverage, and authored-sidecar constraints; scaffold acceptance/parity tests lock generated shape.
- Known rollout limit: go, ios, kmp, kotlin, php, and python remain explicitly exempt until SKILL-112 subtasks 2-7 migrate them; subtask 8 removes the exemption mechanism.
- No contract-version change or generated artifact was introduced; full repository validation passed.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-06-06] SKILL-69 review-noise-tuning-and-validation-gate
Areas: orchestration/review-orchestrator, docs/review-telemetry.md
- Shared review orchestrator guidance now narrows `Minor` and `Medium`/`Low` confidence findings to explicit contract violations, user-visible bugs, regression risks, quality gate failures, or persisted learnings. reusable
- The same threshold is duplicated in the specialist contract so delegated reviewers inherit the low-value finding filter without weakening actionable evidence requirements. reusable
- Severity preservation rule: evidence-backed `Blocker`/`Major` findings and concrete correctness, security, persistence, lifecycle, testing, accessibility, and contract defects remain reportable.
- Review telemetry docs now spell out the issue category taxonomy: behavior correctness, data persistence, concurrency/lifecycle, UX/accessibility, testing/quality gate, security/privacy, docs/contract, and other.
Feature flag: N/A
Acceptance criteria: 5/5 implemented
