# SKILL-411: Standalone phase progress in VS Code and IntelliJ

## Outcome and authority

Both IDE plugins show standalone Skill Bill phase execution with the same truthful lifecycle, timing, and liveness conventions used for workflow execution. The most recently started execution owns the latest-run display, including after it finishes. Standalone execution remains distinct from a full goal workflow.

This is a planning-only deliverable. No feature implementation, build, test execution, installation, review, commit, push, or PR is authorized by this planning phase. The supplied preplan digest is the sole repository evidence used to prepare this bundle. The acceptance criteria below describe the eventual repository end state for implement and audit; they do not claim that it already exists.

## Scope

Cover standalone plan, review, validation, PR, and monitor, including durable plan state and currently in-memory phase execution. Extend the canonical IDE-status snapshot and its runtime producer, introduce purpose-built cross-process standalone status persistence, and update the VS Code and IntelliJ decoders, caches, refresh coordinators, presentation, details, and accessibility. Preserve the existing CLI polling transport.

An invocation has two identities: its requested standalone phase and its current executing phase/step. PR and monitor may execute several internal steps; neither the first completed step nor the existence of a workflow id makes the invocation a completed workflow. Runtime progress is limited to events actually emitted.

## Acceptance Criteria

1. The canonical IDE-status schema, Kotlin contract pin, serializers, parity fixtures, and both plugin decoders define the same versioned execution-scope, invocation identity, authoritative run-order, snapshot revision, and optional bounded current-activity contract. Workflow and standalone-phase snapshots are unambiguous, including durable standalone plan with an associated workflow id; unsupported or malformed contracts have explicit diagnostic outcomes.
2. A purpose-built SQLite standalone-phase status repository and migration, exposed through the established persistence ports, retain canonical repository/branch correlation, invocation identity, lifecycle, timing, lease, and terminal result independently of WorkItem and telemetry rows. Atomic updates and reconciliation cannot publish success before authoritative settlement, regress a terminal record, or classify lost execution as done.
3. Runtime launch and lifecycle/event wiring covers durable standalone plan and in-memory review, validation, PR, and monitor, retaining existing phase semantics. Snapshots carry the requested phase, current step, established timing/liveness anchors, and only available runtime activity. Invocation completion follows the complete operation result; a completed review may still report findings and a monitor may validly report nothing to monitor.
4. Runtime candidate selection uses a common persisted per-repository execution sequence across workflow and standalone sources, after established eligibility/correlation rules. A newer completed phase supersedes an older active workflow, a newer workflow supersedes an older phase, timestamp ties do not change ordering, and a durable plan appears once. Retention and lease reconciliation preserve the most recent eligible terminal result and truthfully expose interrupted/stale state.
5. VS Code domain, cache, refresh, UI mapping, status bar, tooltip, details, and accessibility visibly distinguish standalone phase from workflow, expose phase/status/available activity and normal timing, freeze terminal timing, and never label failure, blockage, interruption, or stale fallback as successful completion. Presentation respects the existing 48-character status-bar budget.
6. IntelliJ provides the same observable distinctions and latest-run behavior through its native domain, cache, coordinator, UI state, widget, popup details, and accessibility, preserving EDT/process isolation and established active-duration calculations.
7. Both plugin coordinators and persisted caches carry run identity/order/revision through initial loading, polling, reconnect, and transport fallback. Older runs or older revisions cannot replace a newer observed run; cache/failure fallback is marked stale. A newly opened plugin can recover retained terminal result and frozen timing from runtime status, and absent optional activity produces no invented progress.
8. Focused deterministic regression coverage exists for shared wire compatibility, persistence/migration and interruption, cross-source ordering, phase lifecycle publication, and each plugin's initial-load/reconnect/presentation boundaries. Each test obligation names a realistic wrong behavior and asserts observable outcomes; existing governed parity and real regression coverage remain intact.

## Design decisions

Use a small SQLite status record with lease and timing anchors, separate from workflow and telemetry storage. Allocate a monotonic per-repository execution sequence transactionally from a shared run-order registry for both sources. Use a stable store identity to identify ordering domains, decimal-string sequences/revisions to avoid JavaScript integer precision loss, and existing timestamps for timing rather than ordering. Activity uses bounded allowlisted event labels; liveness retains last_agent_activity semantics.

Keep the most recent eligible record according to existing settled IDE-status retention. Cleanup must not remove a selected terminal record while it remains within that window. Lease expiry projects paused/stale with runner_interrupted, never done; authoritative failure may subsequently reconcile to failed. Explicitly completed invocations retain their terminal result and frozen timing. See the executable subtask for transaction, compatibility, and UI details.

## Decomposition and dependency notes

One executable subtask implements the coherent runtime-to-plugin contract. This is a decomposed bundle with one executable child, not an instruction to create a subtask per layer. The digest's runtime-before-consumers dependency is implementation order within the commit. No component here ships independently: separating the version change from its consumers would create an incompatible intermediate result. Breadth alone does not justify additional full ceremony cycles.

The subtask is self-contained so later workers need not obtain this parent or the entire artifact map. All necessary decisions and assumptions are repeated in its governed spec. No tracker lookup, new tracker issue, marketplace dependency, or new network dependency is required. Resolved spec source is local.

## Assumptions to confirm during implementation

- The digest omits the repository default branch. The manifest uses main as an explicit unverified assumption. The owning runtime/implementation must confirm the actual default before using it as a branch base and correct the manifest through its governed path if necessary; planning does not inspect git.
- The manifest's feat/SKILL-411 branch is the contract-required intended feature branch, not permission to switch. Until base/SKILL-380-phase-slot-strategies is merged, the supplied active-fix-branch/worktree instruction governs actual implementation. Parent runtime owns branch setup and must reconcile these metadata assumptions before implementation; this plan changes no branch.
- Exact existing retention duration, heartbeat cadence, wire naming conventions, migration registration, workflow launch/resume hooks, cache persistence version, and per-project validation argv are absent from the digest. Implement confirms those owners and reuses their policies; it must not introduce guessed timeout constants or silently weaken a contract. The choices below specify behavior without assuming undiscovered APIs.
- The next IDE status contract is planned as 0.3 from the digest's 0.2. Implement confirms that version remains available, keeps all producers/consumers aligned, and follows the repository's contract migration and typed failure requirements.

## Non-goals

Do not convert standalone execution into a goal workflow, introduce new workflow kinds, change phase side effects or return semantics, build a second plugin transport, use telemetry as live status storage, invent percentage/check/tool progress, or require a dedicated new window. Do not change full workflow execution semantics beyond the lifecycle identity and ordering integration needed for the latest-run surface. Do not drop real regression coverage or relax architecture guards. Planned file paths are navigation anchors, not a ban on necessary wiring, formatting, test setup, or validation repairs.

## Validation Strategy

Planning verification is an in-memory check of this bundle's required sections, criterion lists, dependency order, exact manifest fields, and scope; installed runtime settlement owns schema/phase acceptance. No commands that build, compile, test, or execute a full check suite run in plan, implement, or audit. tests_executed: [].

Later build alone owns the manifest-declared pack build_command. Later validate owns focused runtime/contract/storage/CLI/plugin test execution and all required project gates, discovered from repository instructions, manifests, scripts, build configuration, and CI. Only validate may execute the pack collect_all_full_gate_command, ./gradlew check, check --continue, or equivalent full repository checks. The worker must not launch another standalone workflow to perform these steps. Source/install changes, if actually required, follow the owning later phase and repository install contract. Review/audit inspect the actual committed tree and criterion evidence under their runtime contracts; history, commits, PRs, and monitoring remain with their owners.

## Next Path

Executable spec: .feature-specs/SKILL-411-standalone-phase-progress-in-vs-code-and-intellij-plugins/spec_subtask_1_standalone_phase_status.md.

The future continuation command is `skill-bill goal SKILL-411`. It is documented only; this standalone planning invocation stops after settlement and does not execute it.
