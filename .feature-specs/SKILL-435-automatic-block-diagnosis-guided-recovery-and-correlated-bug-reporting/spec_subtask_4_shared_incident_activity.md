# Subtask 4: shared terminal and accessible desktop incident activity

## Scope

Project saved incident/diagnosis/recovery/report history through runtime ideStatusService.detail and typed desktop domain state. Add a small dedicated diagnosis composable in feature/detail. Expose a supported incident status/preview/recover CLI backed by the same engine service before advertising it in terminal output.

## Acceptance Criteria

1. Runtime detail and terminal projections expose the same saved diagnosis, explicit lifecycle/availability labels, next action, bounded evidence references and report/correlation state for actual goal and standalone execution identities.
2. Desktop Goal/Phase activity displays cause and next action prominently in the existing visual system with expandable technical evidence, safe links/copy affordances and retained diagnosis history.
3. Keyboard-accessible recovery interaction previews typed action/effects and prerequisites before confirmation and presents persistent refused/in-progress/failed/resumed feedback from runtime authority.
4. Supported terminal status and recovery commands use the same typed preview/confirmation service and saved exact target while preserving original exit codes and truthful progress/report/telemetry output.
5. Committed incident changes coherently refresh applicable goal/phase detail and preserve history after resume, while stale cursors/store replacement fence actions without desktop polling or a second recovery owner.

## Actual presentation owners

Desktop-tray architecture keeps feature/detail commonMain typed state/action/effect dependent on core domain/UI/designsystem, never data/runtime. DetailContract owns DetailUiState/DetailLoad/ControlState/ControlKind/DetailAction and DetailViewModel processes actions. Add typed preview/confirm/refusal/results and persistent feedback to the existing single in-flight control. Runtime idempotency remains authoritative. DetailContent already uses DesignLabel, DesignButton, panel/border colors, SelectionContainer, expandable ReasonTooltip and copy-reason; use those components in a small diagnosis composable instead of expanding the large screen further.

Core domain WorkModels Detail/WorkItem/LatestActivity/ExecutionInstance/RunOptions receives typed saved history; it does not infer cause from prose. Extend WorkControlRepository with preview/recover capabilities, no shell parameter. Map through RuntimeWorkControlRepository, RuntimeWorkGateway ControlAction/ControlResult, JVM RuntimeWorkAdapter and RuntimeWorkMapper only. Preserve item.databaseGeneration, session.currentFileMatches, WorkRunTarget file/revision checks and stillCurrent semantics. RuntimeSnapshotReads.detail obtains runtime.ideStatusService.detail; add saved incident projection there and map it. No policy/process launch/recovery coordinator in desktop adapters.

Room stays read-only with runtime-owned migrations. RoomWorkDetailRepository observes existing section revisions and coherent detail retries, currently three attempts with one owner deadline scheduler. Add incident invalidation for goals/phases through appended runtime migration triggers. No polling timer, CLI-output parser or desktop migration. RuntimeComponent stays the only in-process wiring root. If a compatibility surface/hash changes, both runtime and desktop version checks must reject unsupported stores clearly.

## Concrete UI states and copy

| Saved state | Visible label and copy | Interaction |
| --- | --- | --- |
| Pending/investigating | Investigating. "Investigating this block. Your saved work is unchanged." | Copy incident/run IDs; no recovery without eligibility. |
| Partial or secondary failure | Diagnosis incomplete. "Investigation could not read the saved output. The original block is still saved." | Show exact unavailable source/retry state; never claim completion. |
| Environment prerequisite | Diagnosis complete. "GitHub access is unavailable. Sign in to the configured account, then check again." | Instructions/check-again; no actionable Recover until checks allow it. |
| Unresolved | Diagnosis complete. "The cause is still unknown. The saved evidence does not show why the provider stopped. No workaround is known." | Expand available evidence; no invented repair. |
| Eligible | Recovery available. "Recover will release the confirmed-dead worker lease and resume the saved audit attempt. Completed phases and checkpoints stay saved." | Recover opens action/effects preview and prerequisite check before confirm. |
| Admitted action | Recovering. "Recovery is in progress for this saved attempt." | In-flight feedback, repeated clicks reach same durable action. |
| Failure or stale refusal | Recovery failed, or Recovery refused. "This saved attempt changed. Refresh the diagnosis before trying again." | Keep original cause and failed-action history; focus returns to visible feedback/action. |
| Accepted successor | Resumed. "The saved audit attempt has resumed." | Keep diagnosis and recovery history after selection changes. |

Always show labels as text as well as color. Support keyboard focus/activation, accessible names, readable status, announced outcome and sensible focus restoration after refusal/refresh. Evidence expansion/copy works without overflow; validate safe external URLs and control-character sanitation. Public report state/link and telemetry availability are separate from recovery state. Standalone Phase activity is keyed by its actual invocation/execution with nullable goal/workflow coordinates, including early monitor refusals; Operation activity must not fabricate a goal when displaying a supported standalone context. Never label normal pauses or nonblocking QA as investigating.

## Terminal interface decision

Implement and document proposed `skill-bill incident status <incident-id>`, `skill-bill incident recover <incident-id> --preview` and `skill-bill incident recover <incident-id> --confirm <preview-token>`. Preview token binds action/version, incident revision/generation, exact target, prerequisites and expiry; noninteractive confirmation is not a shell string or unrestricted yes flag. Both desktop and CLI call the typed service, including revalidation. An expired/stale token refuses without mutation. Only print commands once their runtime parser and service exist. Originating process can exit; status reads the durable incident and report/recovery progress afterward.

Extend GoalRunPresenter.goalRunText/report mapping and goal/status; extend PhaseCommand.writePhaseResult/phaseProgressLine. Show saved summary, progress, prerequisite or typed action, bug link, exact correlation search identifiers and actual telemetry availability. Preserve review output, exit codes and safe terminal links/control characters. Engine already captures primary errors before PhaseCommand's selected exception catches. Pending investigation is reported honestly at exit; terminal need not wait for diagnosis. No output parsing/classification in presenter and no CLI policy duplication.

## Test Obligations

- T4.1 catches invisible/incorrect causes and history. Extend DetailViewModelTest and Compose DetailFailureBannerTest patterns for investigating, partial/unresolved, missing prerequisites, standalone identities and resumed history; assert visible semantics/state and evidence expansion/copy without pinning incidental prose or composable layout.
- T4.2 catches inaccessible or unsafe activation. Keyboard/Compose semantics cover preview effects, focus, announcement, confirm, repeated click and stale/refused/failed/success feedback. Assert the runtime result and preserved history, not only disabled button state; prerequisites prevent eligible action until checked.
- T4.3 catches stale detail and cross-store recovery. Data compatibility/external-writer tests prove committed incident updates refresh goals/phases, rollback publishes nothing, coherent cursor retry works, and replaced selected DB fences recovery. No timer-dependent sleeps; use existing revision/deadline mechanisms.
- T4.4 catches terminal/desktop divergence and unsafe commands. CLI boundary fixtures read the same saved projection, preserve original exits/review output, sanitize links/control characters, show correct status/report/telemetry availability and bind confirmation to current preview. Origin exit followed by status still finds the incident. Cover exact standalone monitor target and nonblocking lifecycle negatives.

## Non-Goals

No redesign of the desktop visual system, writable Room incident store, UI classifier, independent worker, output parser or generic command launcher. No automatic click-equivalent recovery and no misleading success from a spawn return.

## Dependency notes

Depends on subtasks 1, 2 and 3. Present their saved lifecycle through runtime projection and services. Required UI/data/CLI wiring and compatibility changes ship together here. Preserve standalone lease/revision result distinctions ACCEPTED, IDEMPOTENT, STALE_REVISION, STALE_LEASE, TERMINAL_REGRESSION and MISSING.

## Next path

After runtime-owned planning, stop this invocation with the verified bundle and workflow ID. A later explicitly authorized full goal uses `skill-bill SKILL-435 --agent codex --no-live-output`, equivalent to `skill-bill goal SKILL-435`.

## Governing constraints

Planning workflow is wftr-20261021-055741-uosu. This bundle is planning-only until a later explicit implementation request. Use the active workflow checkout and branch. Preserve unrelated SKILL-429 coordinator-admission/carryover changes and the SKILL-434 bundle. File names below identify owners, not an exclusive repair allowlist. Required later review and validation repairs may touch production wiring, test setup, formatting and lint while preserving behavior and assertions.

The preplan digest is the sole repository evidence for this plan. Names marked proposed are new APIs, not installed-runtime capabilities. Implement must confirm mutable integration facts within its assigned authority. No implementation, test execution, build, public issue submission, commit, push or goal launch occurs during this planning phase. Runtime-owned review, history, publication and settlement remain outside implement acceptance criteria.

Apply architecture-guidelines A1 through A12, section 5, G1 through G7 and P1 through P8, Runtime Architecture Design Principles, code-principles, runtime-command-guidance and observability-policy. The digest supplies their application here: narrow capabilities and authority, one coupled transition owner, explicit resource cleanup, deleted duplication rather than moved duplication, no policy in adapters, no second composition root, append-only persistence preserving records, no new guard exemption/baseline, and observable boundary proof. Runtime-domain owns types/pure policy, runtime-contracts owns shared serialized vocabulary, runtime-ports owns consumer I/O contracts, runtime-engine/application own use cases, runtime-infra owns mechanics. Only skillbill.di.core.RuntimeComponent under runtime-core/src/main/kotlin/skillbill/di composes them. Never introduce an all-access strategy context, raw runner/workflow mutator or callback bag. Kotlin source follows the repository comment restrictions.

## Implementation Details

Implement these tasks in order after subtasks 1 through 3 supply the durable incident, diagnosis, recovery and reporting services. The plan uses only the upstream digest. Paths and symbols below are integration owners, not a restriction on necessary later repairs. Preserve every existing criterion and the validation ownership stated below. Do not run tests or build commands during this planning phase.

### 1. Project saved incident activity through the runtime detail boundary

Serves AC 1 and AC 5. Extend the runtime-owned projection returned by `ideStatusService.detail(WorkDetailRequest)` and `WorkDetailItem` with typed incident history, current diagnosis, evidence availability, recovery eligibility/progress, report state and actual telemetry availability. Reuse subtask 1 through 3 domain types or narrow projection types rather than serializing presenter prose. Preserve incident, diagnostic, recovery, goal, workflow, phase-run, attempt, invocation and execution identities with explicit absence. Do not substitute a goal ID for a standalone execution or create a child identity for a parent-only block.

Keep original occurrence and prior diagnoses/actions visible after successor ownership and resume. Project Investigating, Diagnosis incomplete, Diagnosis complete, Recovery available, Recovering, Recovery refused, Recovery failed and Resumed from saved facts. Unknown evidence and unavailable telemetry remain explicit. Accepted relay delivery must not become a claim of PostHog ingestion. Direct lookup URLs require authoritative deployment configuration; otherwise project exact search identifiers. Report delivery is independent of recovery, including a recovered defect whose report remains pending or failed.

Confirm during implement the detail projection's exact source files and the dependency APIs delivered by earlier subtasks. The digest names its public seam but not every owner file. Assume those services provide bounded history and typed availability; if a missing field prevents these outcomes, extend the owning runtime contract/projection rather than inferring it in CLI or desktop. Shared wire changes follow owning Keys objects, version pins, canonical schema/parity and typed parse failures already established by earlier subtasks. Do not add a second incident persistence format.

Test obligations are T4.4 projection parity and T4.3 coherent detail reads. The realistic bugs are losing a standalone incident because its workflow is null, hiding history after resume, or presenting pending relay data as delivered. Reuse boundary fixtures rather than adding one test per mapping field.

### 2. Carry typed activity and recovery controls through desktop boundaries

Serves AC 1, AC 3 and AC 5. Extend `desktop-tray/core/domain` WorkModels `Detail`, `LatestActivity` and `ExecutionInstance` as needed for saved history, and `Repositories.kt` WorkControlRepository with typed preview/recover requests and refusal/progress results. Keep feature/detail dependent on domain/UI/designsystem. Extend `RuntimeWorkControlRepository`, `RuntimeWorkGateway` ControlAction/ControlResult, JVM `RuntimeWorkAdapter` and `RuntimeWorkMapper.toDomain` to map the runtime projection and delegate to the shared recovery service. Retain `RuntimeSnapshotReads.detail` as the read path. Neither the gateway nor the mapper classifies causes, selects repairs or launches processes.

Carry database generation, file observation, target revision, incident generation and preview token through the typed action. Preserve `session.currentFileMatches`, `stillCurrent`, branch/PR scope and runtime ownership checks. Unknown live ownership fails closed in the engine. A desktop refresh or selected-store replacement invalidates local confirmation; the runtime still revalidates every request. Wire any additional narrow service provider only through `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di`, whose sole root is `skillbill.di.core.RuntimeComponent`.

Use T4.3 for selected-store replacement and stale-cursor outcomes and T4.2 for duplicate/stale confirmation. These catch recovery against a replaced database or old attempt even when a button was previously enabled. Reuse subtask 2's engine concurrency/crash proof; do not duplicate its action-ledger tests in adapters. Trivial field forwarding needs no separate test.

### 3. Render diagnosis and history in the existing activity panels

Serves AC 2 and AC 3. Add a dedicated diagnosis composable beside `desktop-tray/feature/detail/src/commonMain/kotlin/dev/skillbill/tray/feature/detail/DetailContent.kt` and connect it to Goal and Phase activity. Extend `DetailContract.kt` state/actions and `DetailViewModel.kt` processing while preserving the single in-flight control and persistent runtime feedback. Keep existing blocked-reason and acceptance-progress behavior intact. Use DesignLabel, DesignButton, existing panel/border colors, SelectionContainer and the established expandable/copy evidence treatment.

Implement the concrete state/copy table above as semantic behavior, without making exact wording a test contract. Put cause and next action before expandable technical details. Show evidence availability and bounded local references, copyable incident/run IDs, validated external links, separate public report state and telemetry state. Preserve invocation/execution-keyed history across selection updates. A normal pause, CI wait, successful/no-target monitor, skipped phase or nonblocking QA receipt gets no new block diagnosis UI. Historical diagnosis can remain visible without relabeling the current lifecycle.

Recover appears only for a supported typed action with met or explicitly checkable prerequisites. Missing credentials/user edits show instructions and a check-again path. Activation fetches a fresh preview containing exact target, action/effects, prerequisites and expiry, then requires confirmation. Confirmation delegates to the runtime service, never applies a model suggestion. Keep admitted, refused, failed and resumed feedback visible; a spawn result alone is not Resumed. Repeated clicks converge on the saved action. Provide keyboard access, accessible names, text status labels, outcome announcement and focus restoration after refusal or refresh.

Use T4.1 and T4.2 beside `DetailViewModelTest.kt`, `DetailFailureBannerTest.kt` and existing JVM Compose tests. Cover representative investigating/unresolved/prerequisite states, keyboard preview/confirmation, repeated activation, stale refusal, failure, accepted-successor history and standalone identity. Assert visible semantics and the resulting saved feedback/history. Include evidence expansion/copy without overflow, but avoid screenshot/layout or incidental-copy pinning. Engine tests own mutation authorization; UI tests prove that users can see and complete the supported interaction.

### 4. Add supported incident CLI controls and shared terminal presentation

Serves AC 1 and AC 4. Implement runtime parser/dispatch and documentation for `skill-bill incident status <incident-id>`, `skill-bill incident recover <incident-id> --preview` and `skill-bill incident recover <incident-id> --confirm <preview-token>` before presenters print them. Assume this proposed command family can delegate to the subtask 2 typed preview/recover service without entering another feature-task workflow; implement confirms parser ownership. Do not add generic command execution, an unrestricted confirmation flag or CLI repair policy. Bind the token to incident revision/generation, action/version, saved exact target, prerequisites and expiry. Stale or expired confirmation refuses without mutation.

Extend `GoalRunPresenter.goalRunText`, report mapping and goal/status presentation, plus `cli/phase/PhaseCommand.kt` `writePhaseResult` and `phaseProgressLine`, to query the same saved projection as desktop. Show pending investigation honestly without waiting for the worker. Print the read-only status command so users can inspect progress after the originating process exits. Show the supported recovery command only when the typed capability exists and eligibility permits it. Preserve original exit behavior, retained review output, control-character sanitation and safe links. Earlier subtasks retain primary errors before presentation catches them; this task must not replace their reason or disposition with diagnosis text.

Use T4.4 boundary fixtures for desktop/terminal agreement, status after origin exit, preview-bound confirmation, stale refusal, original exit/review output, unsafe links/control characters, honest report/telemetry availability and exact standalone monitor target. Include nonblocking lifecycle negatives without recreating the full trigger matrix owned by subtask 1. The concrete bugs are advertising an unsupported command, resuming a different invocation, or changing a failure exit because diagnosis succeeded.

### 5. Complete coherent refresh and compatibility integration

Serves AC 5 and supports AC 1 through AC 3. Connect incident updates to the existing runtime-owned goals/phases revision signals used by `RoomWorkDetailRepository`. Preserve its coherent read retries and one deadline scheduler per detail owner. Check the append-only migration and invalidation wiring delivered by subtask 1; complete missing integration through a new migration rather than changing historical migration 52. Recheck the next free migration number because the digest's version 65 may have advanced. Runtime owns writes and migrations; Room remains read-only.

Prefer runtime detail projection over adding Room incident entities. If compatibility changes are necessary, update `DesktopReadSchema`, runtime-owned compatibility version/hash and desktop checks together so incompatible stores produce a clear response. Do not introduce polling, a desktop worker, a second recovery coordinator or CLI-output parsing. Preserve lease/revision distinctions ACCEPTED, IDEMPOTENT, STALE_REVISION, STALE_LEASE, TERMINAL_REGRESSION and MISSING in typed results.

Use T4.3 external-writer and compatibility tests to prove committed updates refresh both goal and standalone phase details, rollback emits no refresh, coherent reads avoid mixed cursors and replaced stores fence confirmation. Use existing revision/deadline test control rather than sleeps. Extend runtime SQLite upgrade coverage only for a migration this task actually adds; retain old runs and incident history. The realistic bugs are stale Recover eligibility after a committed change or an upgraded database missing triggers that fresh databases have.

### 6. Prepare focused verification for the owning later phases

Serves AC 1 through AC 5. Implement the focused obligations above without executing proof commands in this phase. Validation later runs the existing Validation Strategy, including affected runtime/desktop checks, UI checks under the required host setup, compatibility/external-writer coverage and distribution smoke paths. Do not claim bundle-wide verification from this single-file plan.

Review and audit later apply A1 through A12, section 5, G1 through G7 and P1 through P8 to the resulting boundaries. Verify that presentation has no cause inference or repair policy, adapters only map/delegate, composition stays in RuntimeComponent, history and store fencing remain intact, coupled mutations retain one engine owner, and no baseline/exemption weakens enforcement. Reuse existing architecture guards; add acceptance/rejection fixtures only for a new mechanically enforceable boundary. Confirm no private prompts, raw tool output or local personal paths enter public links/report presentation. Preserve SKILL-429 carryover/coordinator work and the SKILL-434 bundle throughout.

Host-specific worker survival, enforced read-only diagnosis, GitHub credentials and authoritative PostHog deployment configuration remain implementation confirmations owned by earlier subtasks. This presentation task assumes no successful diagnosis, permission, delivery or direct lookup configuration without saved evidence. It displays their bounded pending/degraded/unavailable results and never treats those unknowns as authority to recover or submit evidence. Public issue creation, full-goal launch, history, commit and publication remain outside this planning invocation.

## Validation Strategy

Only validate executes tests and full project checks; only build supplies buildability proof. Plan, implement and audit inspect production end states without invoking proof commands. At validate, resolve affected gates through runtime/manifest routing and include runtime-kotlin `./gradlew check --no-build-cache` and `scripts/validate_agent_configs`. Desktop changes also require all affected module checks, Linux Xvfb where needed, `./gradlew clean check createDistributable --no-build-cache --no-configuration-cache` under desktop-tray and `packagedDataSmoke` as required by desktop CI. Keep actual evidence and failures in validate outputs, never claim future checks already passed. Review applies the section-5 checklist with rule IDs and the existing PrincipleEnforcementInventory, StrategyCapabilityBoundaryArchitectureTest, RuntimeCompositionGuardArchitectureTest, WireVocabularyArchitectureTest, CommentAndInterfaceKdocArchitectureTest, InlineFqnArchitectureTest and ProductionFileLineCeilingArchitectureTest. Any new mechanical guard needs acceptance and rejection fixtures proving a real boundary. Preserve existing regression assertions. Do not write layout-pinning or incidental-prose tests.
