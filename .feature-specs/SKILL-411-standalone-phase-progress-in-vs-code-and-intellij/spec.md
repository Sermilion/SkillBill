# SKILL-411: Standalone phase progress in VS Code and IntelliJ

## Outcome

Expose standalone Skill Bill plan, review, validation, PR, and monitor in both IDEs with truthful scope, lifecycle, timing, liveness, available activity, latest-run ordering, and retained terminal results. Preserve workflow behavior and the existing `work status --repo-root <canonicalRoot> --format json` transport.

## Scope

Deliver one coordinated schema/runtime/persistence/consumer change. The parent and child cover all eight upstream criteria. The executable child is `spec_subtask_1_standalone_phase_status.md`. One subtask is sufficient: the schema, persistence authority, lifecycle publication, and both decoders must ship together; separate layer commits would expose an incomplete contract. No breadth-based split or tests-only subtask is needed. Feature size is MEDIUM, with full preplan, branch-diff review, and full per-criterion audit.

## Acceptance Criteria

1. AC-001: The canonical IDE-status schema, Kotlin contract pin, serializers, parity fixtures, and both plugin decoders define the same versioned execution-scope, invocation identity, authoritative run-order, snapshot revision, and optional bounded current-activity contract. Scope-specific invariants distinguish workflows from standalone phases even when a durable standalone plan has a workflow id; unsupported versions and malformed payloads have explicit typed diagnostic mappings.
2. AC-002: A purpose-built SQLite standalone-phase status repository, additive migration, and established persistence ports retain canonical repository/branch correlation, invocation identity, lifecycle, timing, lease, and terminal result independently of WorkItem and telemetry rows. Transaction and reconciliation code prevents premature success, terminal regression, and classification of lost execution as done.
3. AC-003: Launch and lifecycle publication code covers durable standalone plan and in-memory review, validation, PR, and monitor without changing their operation semantics. Projections retain requested phase identity, current internal step, timing/liveness anchors, and only available activity. Terminal publication follows the complete authoritative operation result; review findings and a monitor result of nothing to monitor remain representable completed results.
4. AC-004: Workflow and standalone candidates share a persisted per-repository execution sequence and pass established eligibility/correlation gates before ordering. Selection code chooses a newer completed phase over an older active workflow and a newer workflow over an older phase regardless of timestamp ties, projects a durable plan once, preserves the latest eligible terminal result during retention, and maps expired execution leases to interrupted/stale state.
5. AC-005: VS Code domain, persisted cache, refresh, UI mapping, status bar, tooltip, details, and accessibility carry and visibly distinguish standalone phase scope, phase/status, available activity, and normal timing. Terminal timing freezes; failed, blocked, interrupted, and stale outcomes have non-success presentation; status-bar output remains bounded to 48 characters.
6. AC-006: IntelliJ domain, persisted cache, coordinator, UI state, native widget, popup details, and accessibility implement the same scope, latest-run, activity, lifecycle, and timing distinctions while retaining EDT/process isolation, coroutine cancellation, and established active-duration calculations.
7. AC-007: Both plugin coordinators and persisted caches preserve run identity, order, and revision across initial load, polling, reconnect, and fallback. Their acceptance logic rejects older runs/revisions and terminal regression, marks cache/transport fallback stale, handles store-generation replacement and correlation changes explicitly, and permits recovery of retained terminal timing without inventing optional activity or progress.
8. AC-008: Focused deterministic regression tests and governed parity fixtures cover wire compatibility and precision, migration/persistence/interruption, cross-source ordering, phase lifecycle publication, and each plugin's initial-load/reconnect/presentation boundaries. Every added test has a named realistic wrong behavior and observable outcome, and existing parity and real regression coverage remains intact.

## Non-Goals

Do not change phase effects, review verdict semantics, monitor's no-open-PR result, CLI transport, workflow routing, or build/validation ownership. Do not add a telemetry-derived status store, infer scope from workflow id, manufacture progress, expose raw agent content, add a shared plugin production module, introduce a tracker dependency, or redesign unrelated UI. Existing regressions and parity tests are not omission candidates.

## Dependency Notes

There is one executable subtask with no preceding dependency or external tracker dependency. Apply the child in this internal order: contract and ownership; persistence and root ordering; lifecycle and candidate projection; consumer ordering protocol; VS Code; IntelliJ; boundary fixtures and regression evidence. This is an implementation sequence within one coherent commit, not multiple ceremonies. Migrate before enabling the new producer. Runtime and both decoders adopt the coordinated wire version together; version mismatch degrades explicitly and old caches remain stale until corroborated.

## Constraints and Phase Authority

The supplied preplan digest is the sole repository knowledge for this bundle. Planning performs no source discovery, tracker lookup, branch operation, compilation, build, test, generator, installation, or repository check. The only plan writes are this parent, its distinct executable child, and the manifest inside the supplied bundle directory. Existing bundle contents are not planning inputs.

Implement and audit acceptance is inspectable in the repository tree: production rules, wiring, schema, fixtures, and test assertions. Commands and execution evidence belong to Validation Strategy, never acceptance criteria. Later repair owners may adjust production wiring, test setup or bodies, formatting, lint, and other necessary repository files to satisfy these criteria; the affected-path inventory is guidance, not a restriction that prevents repairs.

Before touching runtime implementation, the owning later phase must apply repository runtime-command guidance, architecture guidelines, runtime Design Principles, and code principles. Preserve A1/A2/A3/A4/A6/A7/A8/A10/A11/A12 and P1/P2/P3/P4: purpose-built ports, correct dependency direction, one owner of coupled transitions, composition-root construction, explicit resource lifetime, durable authority separated from projections, typed schema failures, and bounded test value. Later runtime review applies the architecture-guidelines section 5 checklist and cites rule IDs. Do not add raw-map/Any public boundaries, inline wire-key literals, duplicate enum wire tokens, compatibility coercion, or forbidden Kotlin comments. Reuse existing wire conversion seams and centralized owning keys.

The runtime owns phase advancement, commits, history, PRs, and branch/worktree reconciliation. Until merged, the mandated fix branch remains `base/SKILL-380-phase-slot-strategies`; this plan does not switch branches. The manifest's required `base_branch: main` is the digest's unverified default-branch assumption, not permission to bypass the active-fix branch rule. Its required feature-branch field is descriptive metadata; the parent runtime must reconcile the actual branch/worktree before implementation. No new workflow or standalone phase command is authorized by this bundle.


## Decisions and Implementation Assumptions

Use IDE-status contract 0.3, based on the digest's current 0.2 pin. Implement confirms no intervening pin before editing; if that assumption changed, choose the next coordinated compatible version across schema, runtime, fixtures, and both decoders without reverting someone else's contract. Use the next unused additive migration identifier, expected 50 after the digest's 49. Implement confirms the registry identifier; this is not a new planning discovery step.

Use a database-generation store identity and an independent durable per-canonical-repository root-execution order registry for both workflow and standalone runs. Use existing lease cadence/liveness policy and existing selection retention constants, not new duplicate policies. Locate workflow root launch/resume symbols during implement and register there, never during polling or nested phase events. Extend existing plugin workspace-state/PersistentStateComponent owners and sanitizers; exact cache API names are implementation confirmations. Treat missing legacy ordering metadata as stale. These choices settle the digest's bounded unknowns without assuming undocumented signatures.

## Validation Strategy

This plan executes no tests; tests_executed: []. Implement and audit inspect repository end states without running builds, tests, or generators. Only the build phase may execute the pack build_command, and this plan neither invokes nor substitutes for it. Validate alone discovers exact required argv from repository instructions, build configuration, scripts, CI, and the dominant pack; it executes the focused contract, SQLite, engine, CLI, VS Code, and IntelliJ coverage plus every required project/pack gate, including the collect_all_full_gate_command and Gradle check/check --continue when required. Compilation alone is insufficient. Validate records evidence and repairs defects under the same acceptance and architecture constraints. Avoid repeating equivalent suites without a new failure, change, or unresolved concern.

Preserve governed schema/parity and architecture guards without weakening baselines or exemptions. The owning review gate applies the unit-test-value discipline; this bundle does not launch a standalone operation to obtain that gate. Review, finding verification, history, commit/push, PR, and monitor are later runtime-owned phases. Installation or generation required by actual source/renderer/support-pointer changes belongs to the authorized later owner, never implement/audit command execution or an acceptance criterion here; no skill-source change is planned.


## Next Path

The parent runtime consumes `decomposition-manifest.yaml` and `spec_subtask_1_standalone_phase_status.md` and advances its own loop. The preparation handoff command is `skill-bill goal SKILL-411`; it is recorded for the runtime/operator and must not be executed by this phase worker.
