## [2026-11-08] Overlay builds after the main work read
Context: The injected session-backed build repository owns its database reads, while board projections are persisted separately from build rows.
Decision: WorkBoardItemProjection owns the build overlay and shared mapper. IdeStatusService applies it after the main database read, using its injected clock. Failures omit build data with a warning.
Reason: Separate overlay reads keep builds fresh without rewriting board rows and preserve the main result on build-store failure. Projection ownership avoids another IdeStatusService dependency; up to two indexed reads per item is the accepted cost.

## [2026-11-08] Require work-item liveness for running build presentation
Context: A runtime crash can leave a build row with no end timestamp, so row openness alone cannot prove a build still runs.
Decision: Present an open row as running only while its work item is live. Unknown outcomes and open rows on finished items become finished without inventing an end timestamp or elapsed time.
Reason: Existing work-item liveness supplies execution evidence. An unfinished durable row must not leave the desktop displaying a build as active indefinitely.

## [2026-11-07] Show a Cursor grok launch on the activity model list
Context: Activity keeps a phase model only when the id belongs to the attempt's agent. Claude matches `claude*`, Codex matches `gpt*`, and every other agent was dropped. A Cursor audit attempt records `grok-4.7-high-fast`, and the execution matrix records `cursor-grok-*`.
Decision: A Cursor attempt keeps a model that starts with `grok` or `cursor-`. A `gpt*` or `claude*` id on that attempt stays hidden.
Reason: Those prefixes are the Cursor model ids the runtime stores. A Codex or Claude id on a Cursor attempt is a borrowed model, which the activity list already refuses for the other agents.

## [2026-11-06] Derive standalone plan settlement from phase records
Context: Planning seeds subtask spec files before units finish. Goal planning also requires a details hash for its progress calculation.
Decision: Standalone detail treats a completed unit with a nonblank output artifact as saved and filters seeded spec paths by those units. Goal planning retains its settlement rule.
Reason: File existence does not prove a unit settled. The goal helper's extra hash requirement would hide standalone units that meet the saved contract.
## [2026-11-06] Project Verify details in the runtime
Context: Work detail needs saved Verify receipts, QA results and session staleness, including for older runs that lack artifacts.
Decision: Build a read-only, Verify-only projection from the stored snapshot and session telemetry. Keep raw findings with parsed fields and leave missing data null or empty.
Reason: Verify artifact access belongs to runtime-engine, where VerifyWorkflow is internal. Existing snapshot and session data avoid a second source of state and keep older records readable.

## [2026-11-06] Store the Verify summary separately from the verdict
Context: Verify detail needs a description of the work as well as the verdict and findings.
Decision: Store a nonblank SUMMARY section as an optional verdict receipt field.
Reason: The summary describes the change rather than the verdict. A separate optional field keeps it out of findings and allows older receipts to remain readable.

## [2026-11-04] Bind operator-supplied task identity without allocation
Context: Goal and plan starts could mint a new key from a prefix or bind a key mentioned elsewhere in requirements, even when the operator had not supplied that identity.
Decision: Bind only an admitted first token, a complete typed key, or a tracker key extracted from a URL. Ask for identity when none is available. Allow explicit typed reuse only for completed or abandoned terminal runs, while refusing used URLs and in-flight or resumable runs.
Reason: The supplied identity must identify the work the operator intends to start. A URL already tied to a spec and a run that can still resume must not silently become a new launch.

## [2026-10-31] Name the unblock step in the incident's next action
Context: An incident that matched no classification rule showed "No known workaround." even when the runtime knew the command that unblocks the goal, or when the block looked like a skill-bill defect.
Decision: The current incident's next action names a typed recovery command when one exists. The command comes from blocked_recovery_command on the blocked goal parent, or from a parent whose quality record is historical_ambiguity, which gets goal fresh-quality. Otherwise an unreported skill-bill defect, or an unresolved incident with no workaround, points to the product repository's new-issue page with the incident token. Runtime eligibility still wins over these suggestions.
Reason: The runtime already computes these commands and wrote them only into free text. Reading the typed value from live state keeps a resolved block from suggesting a stale command, and it never scrapes commands out of reasons that can contain agent output.

## [2026-10-30] Project the executing agent on board rows
Context: The desktop board needs an agent on each row, but resolving it depends on goal, standalone run and runtime phase state.
Decision: Resolve the display agent in the runtime projector and rebuild the persisted board model through its existing completeness path. Launches continue to use RunOptions.agentId.
Reason: SQL cannot reproduce the projector's resolution rules. Keeping launch selection separate prevents a stale board value from choosing an agent.

## [2026-10-23] Keep incident and recovery decisions in the runtime
Context: SKILL-435 requires terminal and desktop to present the same saved incident account for goals and standalone phases.
Decision: Build progress, next action and recovery eligibility once in the runtime work projection. CLI and desktop render it; desktop recovery uses runtime preview and control services.
Reason: Independent presentation-side policy could disagree about recoverability or the next action. The shared projection preserves one account without adding a desktop writable database or recovery coordinator.

## [2026-10-23] Separate the live incident from retained incident history
Context: Work detail must keep earlier incidents after resume while recovery still targets the current accepted block.
Decision: Select the current incident through its accepted workflow or invocation reference, and retain up to 10 other incidents newest first. Move resumed incidents to history.
Reason: A recent historical incident does not establish the live recovery target. Keeping history separate preserves the saved account after resume without treating an old block as current.

## [2026-10-17] Diagnose skipped board rows as incompatible_record
Context: Board consumers had only missing or invalid repository identity and missing completion time, so a skipped malformed work-list row had no coordinated diagnostic.
Decision: Work-board contract 1.2 adds incompatible_record. Kotlin WorkBoardDiagnosticReason, schema const, and skipIncompatibleBoardItem emit it; ide-status stays 0.3.
Reason: Incompatible-record output needs a versioned enum, schema, and pin together. Keeping 0.3 avoids a second lifecycle formula for a parse skip.

## [2026-10-11] Keep board and detail envelopes separate from ide-status
Context: SKILL-418 needs machine-wide lists and detail panels while existing consumers use ide-status 0.3.
Decision: Put the unchanged snapshot inside new 1.0 board/detail wrappers; keep completion metadata and detail extras on those wrappers.
Reason: The spec requires existing work list/status behavior and 0.3 payloads to remain compatible. Reusing status projection avoids a second lifecycle formula.

## [2026-10-11] Retain finished work by completion evidence
Context: Board windows must describe when work finished, even when heartbeats or status freshness continue changing.
Decision: Apply inclusive windows to completion evidence after lifecycle projection. Include unfinished work regardless of age; label terminal-goal state-entry fallback and diagnose missing completion evidence.
Reason: Freshness timestamps would extend retention without a new completion. Projecting first keeps a live goal monitor visible even when its child has finished.
Alternatives considered: Updated-at or heartbeat retention would misrepresent the selected completion window.

## [2026-10-11] Correlate machine-wide work by stored repository identity
Context: Different repositories can share issue keys, and board has no checkout or branch filter.
Decision: Decode only stored absolute repository identities, group candidates before goal-child exclusion, and diagnose omitted missing or invalid identities.
Reason: The spec requires repository-local issue correlation and forbids manufacturing a root from the process checkout, filesystem, or home directory. Global issue-key grouping could hide another repository's work.

## [2026-10-11] Resolve detail independently of board retention
Context: An old standalone invocation remains addressable after its board window expires, and an issue key can have several historical goal runs.
Decision: Resolve exact workflow and invocation ids, merge only associated execution records, and attach manifest extras only when the parent workflow matches.
Reason: Searching retained board output would lose old invocations. Matching a manifest only by issue key could attach another run's title and subtasks.

## [2026-10-11] Keep terminal goal evidence and require live monitor ownership
Context: The desktop board exposed historical completed goals as idle and stopped goals as fresh running monitors.
Decision: Preserve terminal goal rows and completion controls. Project a running monitor only with verified live ownership and a monitor step. Apply standalone lease expiry to monitors and retain the durable update timestamp.
Reason: Completed subtasks describe implementation progress. They do not prove that a CI monitor exists. Older completed rows remain valid completion evidence even without the newer control marker.
Validation: Legacy completion, stopped finalization, expired standalone monitor and process ownership regression tests cover the observable states. The change removes fabricated freshness and CI failure activity. Architecture review A1 through A12 and G1 through G7 is clean. Production dependencies and guards are unchanged.


## [2026-10-11] Keep board projection free of unused validation evidence
Context: Each goal snapshot loaded validation records for every completed child even though the status snapshot does not expose them. Current-subtask start time also triggered another global work-list query.
Decision: Snapshot projection opts out of completed-subtask validation evidence and reads the current child's start timestamp directly. Detail and explicit goal status retain validation evidence by default.
Reason: Board refresh must avoid expanding all historical phase records. The change removes unused reads while preserving detailed inspection. Full runtime checks pass without widening architecture guards, baselines or exceptions, under A1 through A12 and G1 through G7.

## [2026-10-14] Identify keyed work by issue key and workflow, not the registration branch
Context: A goal can start from any branch, for example to stack on another feature branch, and the runtime later moves to the goal's own branch. Launch refused a goal with BRANCH_CHANGED when the checkout differed from the branch recorded at execution registration. IDE status dropped the goal's execution identity and hid the goal when the branch did not name its issue key or match that recorded branch.
Decision: Goals and feature-task workflows keep their execution identity on any checkout. IDE status no longer filters goals by branch, and filters feature-task workflows only by an issue key in the branch name. Launch checks the recorded branch only for standalone phases, and a goal launch prompt no longer names the registration branch. Verify runs and standalone phases keep branch scoping. The recorded branch stays display data.
Reason: The operator identifies a run by its goal key and parent workflow. The goal runtime chooses and checks out its own branches. Standalone phases run on the checked-out working tree, and pr and monitor push it, so the branch is part of what they act on.

## [2026-10-14] Relaunch desktop runs with the agent home recorded at registration
Context: The tray starts under launchd with only PATH, HOME and SHELL. A harness it launched read the default agent home instead of the operator's CLAUDE_CONFIG_DIR and failed authentication. The operator sets that variable in whatever starts their sessions, so a login shell would not supply it.
Decision: Phase and workflow registration record the present, non-blank AgentHomeEnvironmentKeys (CLAUDE_CONFIG_DIR, CODEX_HOME) of the launching process as agent_environment_json (migration 57). Run again and Resume pass the record of the target phase row, or of the latest execution of the target workflow (the goal parent for goals), and DetachedWorkRunLauncher applies only allowlisted keys over the tray environment.
Reason: The process that registered the run already chose the agent home that worked. The latest parent execution reflects the most recent launch of a goal, including one started from the tray with the recorded value. The allowlist keeps PATH, HOME and credentials out of the database. Rows without a record keep the tray environment.
