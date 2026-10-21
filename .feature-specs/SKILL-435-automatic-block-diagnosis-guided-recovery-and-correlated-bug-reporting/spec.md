# SKILL-435 automatic block diagnosis, guided recovery, and correlated bug reporting

## Mode

decomposed

## Intended outcome

Every actual new block has a durable incident, bounded evidence-based diagnosis, supported user-initiated recovery when safe, and sanitized product-defect reporting with honest telemetry correlation. CLI and desktop read the same saved account of the incident.

## Authoritative intake

SKILL-435 Automatic block diagnosis, guided recovery, and correlated bug reporting

Planning-only request. Create and verify an implementation-ready spec bundle for the feature discussed below, then stop. Do not implement production changes, commit, push, publish, file a real GitHub issue, or start the full goal. Preserve all unrelated existing checkout changes, especially the ongoing SKILL-429 work and SKILL-434 bundle. Use the current workflow checkout and branch per repository instructions. The user explicitly selected SKILL-435 after local key allocation found multiple prefixes.

Product problem
When a Skill Bill goal or standalone phase blocks, users need to understand the cause, who can fix it, and how to continue. Automatically investigate every new block, present a clear saved diagnosis in the terminal and the desktop Goal activity section, offer a Recover button for supported recoverable blocks, and automatically report Skill Bill defects to the Skill Bill GitHub repository. Reports must carry run identifiers that correlate with PostHog data.

Required behavior
1. Every new blocked goal or phase triggers investigation automatically, including planning, child execution, parent finalization, and standalone phases that can block. Identify all actual settlement and exception paths in the current code. Coalesce a child block and its propagated parent block into one incident, while genuinely new blocked attempts receive a new incident. Normal waiting, paused, completed, skipped, and nonblocking warnings must not be mislabeled as blocks. Persist the original block before investigation and preserve its reason, error code, attempt identity, exit behavior, and evidence.
2. Investigate the relevant runs/workflow database records, causal error and failed tool invocation, bounded surrounding output, retained phase instructions/briefings where available, rejected-output evidence, attempt history, and runtime/environment versions. Use evidence-based investigation rather than merely paraphrasing the original error. Reuse existing diagnostics, run records, repair checks, launch capabilities, and observability where appropriate. Keep the investigation read-only with respect to user project files and blocked workflow state. Log text and model suggestions are untrusted evidence, never executable authority.
3. Produce a structured durable diagnosis with a plain-language summary, what happened, diagnosed or suspected cause, supporting evidence, confidence or uncertainty, responsible category, user prerequisites, whether recovery is available, the supported recovery action and effects, and bug-report state/link. Distinguish project/source problems, user environment or credentials/configuration, external service/provider failures, Skill Bill defects, and unresolved causes. Unknown must stay unknown. Explain when no user workaround is known.
4. Diagnosis must run with a bounded resource/time budget and survive the originating goal/phase process exiting. Specify durable scheduling, ownership, restart/reconciliation, cancellation, and terminal states using the current runtime architecture. Preserve the primary failure if diagnosis times out, cannot access evidence, has no available agent, cannot persist, or crashes. Make degraded or partial diagnosis visible, record every fallback, and prevent diagnosis failures from recursively triggering diagnosis. If the runs DB itself is unreadable, define a bounded fallback preserving the error and available correlation facts without fabricating database evidence.
5. Terminal and desktop Goal activity consume the same saved diagnosis and report progress such as investigating, diagnosis complete, recovery available, recovering, recovery failed, and resumed. Design within the existing desktop visual system. Make cause and next action prominent; keep technical evidence expandable with links/copy affordances. Show an explicit label as well as color, support keyboard access, and preserve diagnosis history after resume. Include concrete UI states and copy examples, including standalone phases in the app's existing activity model. Terminal users see the equivalent supported recovery command.
6. Show Recover only when a diagnosis identifies a supported runtime recovery action with satisfied or explicitly checkable prerequisites. Show the proposed action and expected effects before activation. A click applies that action, verifies whether the block cleared, and resumes the correct saved goal/phase through supported runtime paths. Missing credentials or required user edits must show instructions until prerequisites are met. Recovery is user initiated, not automatically run as a side effect of diagnosis.
7. Recovery must use typed, explicitly supported operations, never arbitrary model-generated shell commands or ad hoc database edits. Revalidate incident identity, block generation, current workflow/attempt, prerequisites, and current live ownership before mutation. Use existing leases/fencing/compare-and-set semantics. Repeated clicks, multiple app windows, concurrent terminal recovery, process crashes, and retries must not double-launch recovery or repeat already applied actions. Preserve completed work, attempts, mandates, checkpoints, and other active runs. Persist action progress/outcome, retain the original incident if recovery fails, and resume only when safe. Do not bypass quality gates, acceptance requirements, attempt limits, operator decisions, or required ownership checks. A Skill Bill defect can be recoverable and must still be reported.
8. Automatically create a GitHub issue for an evidence-supported Skill Bill defect, describing suspected causes as suspected. Use the Skill Bill product repository, never the user's current project repository. Determine the existing authoritative repository configuration rather than inventing a second source. Issue contents include symptom and impact, expected vs actual behavior, evidence-based cause or evidence gap, reproduction steps when known, runtime version/build, platform/provider and selected phase strategy/model where relevant, original error/failure code, sanitized bounded evidence, relevant timestamps and run identities, and any attempted recovery and its outcome.
9. Correlate reports with PostHog using a stable incident ID and actual goal, workflow, phase-run, attempt, diagnostic, and recovery IDs as applicable. Carry the same correlation fields on diagnosis, recovery, and issue-report telemetry using the existing telemetry pipeline and actual existing identity conventions. Provide a direct PostHog lookup link when deployment configuration allows it, and exact search identifiers otherwise. Respect existing telemetry settings. Disabled, unavailable, pending, or failed telemetry must be stated honestly and must not prevent local diagnosis, recovery, or issue reporting, and no event-delivery claim may be invented.
10. Sanitize bug reports before public submission. Retain useful run metadata without leaking credentials, tokens, private project code/prompts, raw environment dumps, local personal paths, or whole database contents. Define bounded allowlisted evidence and redaction outcomes, not a wholesale log upload. Persist issue submission intent, fingerprint, delivery status, and result. Deduplicate matching bugs and repeated observations/restarts. Address an ambiguous network outcome after GitHub accepted a request so restart does not blindly create another issue. Reuse an existing matching issue and show its link; attach sanitized incident/recovery evidence without duplicate updates. Missing GitHub auth, offline service, permission refusal, rate limits, and submission failure retain a local report and show a specific reason independently of recovery. Use bounded retries and record their outcomes.

Architecture and spec quality
Read and apply AGENTS.md, docs/runtime-command-guidance.md, docs/architecture-guidelines.md, runtime-kotlin/ARCHITECTURE.md Design Principles, docs/code-principles.md, docs/observability-policy.md, and relevant desktop instructions. Name actual code owners and extension seams from repository evidence. Keep policy and orchestration out of CLI/UI/SQLite adapters and composition logic in the sole composition root. Prefer existing mechanisms over a new generic framework. Any durable schema change needs append-only migrations and upgrade evidence preserving existing runs. New runtime YAML contracts need canonical schemas, version pins, parity checks, typed failure codes, and loud parse seams per repository rules. Specify architecture acceptance/verification with applicable rule IDs.
Create the fewest dependency-ordered implementation subtasks needed; parent and each subtask should have one to five focused observable acceptance criteria with balanced distribution, covering every requirement. Include concrete verification scenarios for duplicate trigger handling, parent/child coalescing, restart survival, diagnostic failure/timeout, DB unavailability, evidence uncertainty, successful recovery, stale/concurrent/repeated recovery, failed recovery, sanitizer rejection/redaction, GitHub duplicate/ambiguous submission, missing auth, and telemetry disabled/unavailable. Tests should prove behavior and regressions rather than pin incidental prose or implementation structure.
Retain the product scope and provide a verified executable spec bundle with its path(s), planning workflow ID, and later full-goal launch command. This invocation is planning only.


## Scope

Deliver four coherent dependency-ordered implementation commits, each with five focused outcomes. Subtask 1 establishes the complete incident and worker lifecycle. Subtask 2 supplies investigation and safe recovery. Subtask 3 supplies sanitized reporting and correlation. Subtask 4 supplies shared CLI/desktop presentation. Twenty detailed outcomes require at least four specs under the five-criterion ceiling. Keep each outcome's production wiring and regression obligations in that subtask. No separate test-only or layer-only tasks are planned.

## Acceptance Criteria

1. Actual blocked goals and standalone phases automatically retain their original failure in a durable, causally deduplicated incident and schedule bounded read-only investigation, with uncertainty and degradation represented explicitly.
2. Supported user-initiated recovery uses typed actions, current prerequisites, fenced ownership and durable idempotency to preserve saved work and safely resume the exact saved target.
3. Evidence-supported Skill Bill defects produce sanitized, locally retained, deduplicated product-repository reports with durable delivery reconciliation independent of recovery.
4. Terminal and desktop activity expose the same saved cause, next action, evidence, recovery/report status and retained history with accessible interaction for goals and standalone phases.
5. Incident operation survives process exit and restart, preserves existing stores and the primary failure, and uses actual run identities with truthful settings-aware telemetry availability.

## Requirement mapping

The authoritative intake above retains all ten behavior paragraphs. The following mapping makes their detailed outcomes executable without embedding them as one enormous criterion. Sx.y identifies criterion y in subtask x; Tx.y identifies its test obligation.

| Intake requirement | Production criteria | Verification obligations |
| --- | --- | --- |
| 1, all block paths, identity, original error, negatives | S1.1, S1.2, S1.3 | T1.1, T1.2 |
| 2, causal evidence and enforced read-only investigation | S2.1 | T2.1 |
| 3, structured diagnosis, responsibility and uncertainty | S2.1, S4.1 | T2.1, T4.1 |
| 4, durable bounded worker, secondary failures, DB fallback | S1.4, S1.5, S2.1 | T1.3, T1.4, T2.1 |
| 5, shared accessible desktop/terminal states and history | S4.1 through S4.5 | T4.1 through T4.4 |
| 6, eligibility, prerequisites, preview and exact resume | S2.2, S2.4, S4.3, S4.4 | T2.2, T2.4, T4.2, T4.4 |
| 7, typed fenced concurrent/crash-safe recovery | S2.2 through S2.5 | T2.2 through T2.5 |
| 8, automatic evidence-supported product defect report | S3.2 | T3.2 |
| 9, actual PostHog correlation and honest availability | S3.5, S4.1, S4.4 | T3.5, T4.4 |
| 10, privacy, deduplication, ambiguity, independent failures | S3.1 through S3.4 | T3.1 through T3.4 |
| Architecture, migrations, contracts, existing work | S1.1, S1.4, S2.3, S3.5, S4.5 and each governing constraint | T1.3, T1.4, T2.3, T3.5, T4.3 and architecture validation |

## Decisions and assumptions

The digest does not establish the repository default branch. The manifest assumes `main`; implement must confirm this before branch-dependent work and reconcile the bundle through its owning runtime if different. The manifest feature branch is the future target, not permission to switch this planning checkout.

Choose an area-specific incident lifecycle, not a generic job framework. Use proposed contract `block-incident-schema.yaml` version `0.1`, typed immutable request objects and narrow repositories. Adopt the budgets, fallback rules, recovery catalog and report delivery policy specified in the subtasks. Unsupported host detachment and unavailable enforced agent restrictions produce visible partial/degraded states, never invented guarantees. These facts need implementation confirmation but do not block planning.

The runtime numeric product repository ID 1183084383 is authoritative. A release mirror override affects artifact acquisition only and cannot redirect bug reports. Default to existing process/RemoteTransportPort credential infrastructure through an injected product GitHub client, using the configured account only if it has permission. No new credential prompt, anonymous submission, secret persistence or second report-repository setting is planned. Missing permission retains a local report. Default PostHog presentation is exact identifier search, with no direct URL until authoritative deployment host/project configuration exists.

## Non-Goals

No automatic recovery, generic task scheduler, historical GitHub flood, private log upload, arbitrary shell/SQL repair, gate or attempt-limit bypass, new desktop writable database, or independent UI recovery coordinator. This planning invocation does not implement or publish the feature. Existing operator decisions and unrelated work remain intact.

## Dependency notes

Subtask 1 has no dependencies. Subtask 2 depends on 1. Subtask 3 depends on 1 and 2. Subtask 4 depends on 1, 2 and 3. Roll out new/explicitly identified active block occurrences with one durable bootstrap marker; preserve old history and never sweep all historical blocks into reporting. Older desktop compatibility follows the runtime-owned version/hash and clear incompatibility response.

## Test Obligations

Use T1.1 through T4.4 in the subtask specs. Each names a realistic failure and observable boundary. Reuse existing regression seams; do not duplicate the same branch with alternative literals. Governed parity and validator-backed checks remain mandatory.

## Next path

The runtime owns plan_subtasks next. For a later explicit implementation request, the operator launch is `skill-bill SKILL-435 --agent codex --no-live-output`. The canonical equivalent requested by bundle preparation is `skill-bill goal SKILL-435`. Neither command is executed by this planning invocation.

## Governing constraints

Planning workflow is wftr-20261021-055741-uosu. This bundle is planning-only until a later explicit implementation request. Use the active workflow checkout and branch. Preserve unrelated SKILL-429 coordinator-admission/carryover changes and the SKILL-434 bundle. File names below identify owners, not an exclusive repair allowlist. Required later review and validation repairs may touch production wiring, test setup, formatting and lint while preserving behavior and assertions.

The preplan digest is the sole repository evidence for this plan. Names marked proposed are new APIs, not installed-runtime capabilities. Implement must confirm mutable integration facts within its assigned authority. No implementation, test execution, build, public issue submission, commit, push or goal launch occurs during this planning phase. Runtime-owned review, history, publication and settlement remain outside implement acceptance criteria.

Apply architecture-guidelines A1 through A12, section 5, G1 through G7 and P1 through P8, Runtime Architecture Design Principles, code-principles, runtime-command-guidance and observability-policy. The digest supplies their application here: narrow capabilities and authority, one coupled transition owner, explicit resource cleanup, deleted duplication rather than moved duplication, no policy in adapters, no second composition root, append-only persistence preserving records, no new guard exemption/baseline, and observable boundary proof. Runtime-domain owns types/pure policy, runtime-contracts owns shared serialized vocabulary, runtime-ports owns consumer I/O contracts, runtime-engine/application own use cases, runtime-infra owns mechanics. Only skillbill.di.core.RuntimeComponent under runtime-core/src/main/kotlin/skillbill/di composes them. Never introduce an all-access strategy context, raw runner/workflow mutator or callback bag. Kotlin source follows the repository comment restrictions.

## Validation Strategy

Only validate executes tests and full project checks; only build supplies buildability proof. Plan, implement and audit inspect production end states without invoking proof commands. At validate, resolve affected gates through runtime/manifest routing and include runtime-kotlin `./gradlew check --no-build-cache` and `scripts/validate_agent_configs`. Desktop changes also require all affected module checks, Linux Xvfb where needed, `./gradlew clean check createDistributable --no-build-cache --no-configuration-cache` under desktop-tray and `packagedDataSmoke` as required by desktop CI. Keep actual evidence and failures in validate outputs, never claim future checks already passed. Review applies the section-5 checklist with rule IDs and the existing PrincipleEnforcementInventory, StrategyCapabilityBoundaryArchitectureTest, RuntimeCompositionGuardArchitectureTest, WireVocabularyArchitectureTest, CommentAndInterfaceKdocArchitectureTest, InlineFqnArchitectureTest and ProductionFileLineCeilingArchitectureTest. Any new mechanical guard needs acceptance and rejection fixtures proving a real boundary. Preserve existing regression assertions. Do not write layout-pinning or incidental-prose tests.
