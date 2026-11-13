## [2026-11-13] Require sona at the desktop runtime process boundary
Context: SONA-5 retires skill-bill as a notice-only command after caller conversion. That notice exits zero without executing work.
Decision: DetachedWorkRunLauncher resolves only sona. A missing sona retains the existing launch failure even when skill-bill is discoverable.
Reason: Falling back to the legacy command could report a successful process exit without starting runtime work. The replacement executable must own the existing arguments, database binding and process lifecycle.


## [2026-11-12] Admit OpenCode review isolation per launch
Context: OpenCode 1.15.3 can inherit configuration that private cwd or pure mode alone does not exclude.
Decision: Require a private scope, selected API credential and verified worker. Refuse unsupported versions, managed policy and uninspectable sources; constrain the process environment and permissions to lane evidence tools.
Reason: Inherited environment and worker permission composition can reopen authority after configuration. Managed policy belongs to its external owner and must not be bypassed.
Revisit when: Another version, host permission model or authentication method demonstrates source exclusion and effective tool filtering.
## [2026-11-12] Builder launch refusal is a required member, not a defaulted hook
Context: Devin must refuse a separate effort, or missing runtime MCP context, before any command is built or spawned.
Decision: AgentRunCommandBuilder.launchRefusal has no default body. Every builder implements it, and the Claude, Codex, Cursor and Junie builders return null explicitly.
Reason: PortNullObjectAbsenceArchitectureTest rejects an interface member whose default body returns null. Explicit overrides also keep each builder's pre-spawn refusal policy visible.

## [2026-11-12] Exact-model ACP providers never resolve or backfill the reported model
Context: A Devin model UID such as swe-2-high already includes the effort. Fuzzy resolution, or backfilling from the set_model acknowledgement, could silently change or misreport the model.
Decision: A provider flagged exactModels accepts only an exactly advertised ID and refuses a separate effort before launch. Reported identity stays null unless the provider reports a model. Absent metadata keeps the CLI assignment with a model_metadata_absent diagnostic.
Reason: Requested, effective and provider-reported identities stay separate, so an invalid assignment fails visibly instead of being downgraded.

## [2026-11-12] Classify ACP rejections by error code and pending operation
Context: auth_required, model rejection and session-context rejection each need different recovery guidance.
Decision: acpRejection reads the JSON-RPC code (-32000 means auth) and which operation is pending. It truncates provider detail to 500 characters and never parses the message text.
Reason: Provider wording is unstable, while the code and the operation are protocol facts. An MCP or protocol setup failure is never reported as an authentication failure.
## [2026-11-10] Codex shell unwrap recognizes only single-quoted -c and -lc wrappers
Context: Codex command approvals sometimes wrap the operator command in bash, zsh or sh.
Decision: Unwrap when the outer form matches optional /bin or /usr/bin, shell name, -c or -lc, and a single-quoted inner segment with no embedded quote; otherwise return null when the first token is a shell.
Reason: Exposes the intended inner command for pattern rules while failing closed on double quotes, extra flags and script-path forms the spec does not attest.

## [2026-11-12] Retain private review files until process exit is confirmed
Context: Probe or ACP launch failure and cancellation can leave an owned process running.
Decision: Delete the private scope only after confirmed exit or when no process started. Otherwise retain restricted files, report recovery evidence and fail settlement.
Reason: Deleting material before confirmed exit or reporting clean completion would conceal incomplete cleanup. Preserve the initiating failure or cancellation.

## [2026-11-05] Pass temp-root inputs through the host port
Context: Runtime temp resolution needs the configured JVM directory, environment and user home, while ambient property reads are governed at the host boundary.
Decision: JdkHostPlatformPort passes those inputs to resolveRuntimeTempRoot. If creating state scratch fails, the resolver logs a warning and returns the configured path, or /tmp when it is blank.
Reason: Explicit inputs make the branches testable without changing process state or adding an ambient-read baseline. A read-only state directory should not stop a command that can still use the configured path.
Revisit when: A shared temp path other than /tmp must be treated as shared.

## [2026-11-05] Keep state path helpers in host.jvm
Context: Runtime temp resolution needs the state path helpers, while the host package already depends on host.jvm.
Decision: Move resolveTelemetryStateDir and expandAndNormalizeTelemetryPath to host.jvm without forwarding functions in host.
Reason: The move avoids a host to host.jvm package cycle. A forwarder would keep two names for the same function.

# Boundary decisions

## [2026-11-03] Cursor review harvest keeps a present verdict and never synthesizes one
Context: Cursor concatenates progress onto last-turn present_findings. The review-register predicate accepted only exact NO_FINDINGS or finding rows, so a lanes plus NO_FINDINGS plus canonical-verdict payload fell through and harvest collapsed to NO_FINDINGS. Admission then rejected the missing unique verdict (SKILL-475).
Decision: Widen the last-turn review-register allow-list in CursorAgentRunDecoding and keep a present canonical verdict when harvesting mixed result strings. Match verdict tokens locally from FeatureTaskRuntimeVerdict wire values. Split a glued verdict only when it follows NO_FINDINGS. Do not import admission or census.
Reason: Admission already requires a unique canonical verdict and is outside this file ownership. Assessment harvest is a separate grammar (SKILL-465). Splitting any glued verdict line would take that phrase out of a finding description.

## [2026-11-02] Cursor harvest keeps goal-assessment grammar, not only the review register
Context: Goal assessment agents answer with `Finding: none` and `Scenario IS-NNN:` lines. Cursor concatenates progress turns onto that last line. Harvest only treated `NO_FINDINGS` and `[F-NNN]` as a register, so `GoalAssessmentLineParser` saw one line starting with "I'll assess" and recorded `malformed` / `assessment_unavailable` (SKILL-465, wftr-20261102-153141-jv9f).
Decision: `pickCursorHarvest` keeps a last assistant turn that is an assessment register, and glued `Finding:` / `Scenario` / `Regression` / `Verify` / `Test value:` lines are split out of a concatenated `result` the same way review findings are.
Reason: Assessment and review share the Cursor transport but not the register language. Last-turn review harvest already exists; assessment needs the same last-turn win or a glued empty report is lost.

## [2026-10-23] Legacy state notice stamped in the store record, not the checkout
Context: SKILL-441 moves project state into a project store, so a checkout's old `.skill-bill` content is no longer read. Users need one notice about it without the runtime touching their project.
Decision: After binding a store, the resolver warns once and records a notice timestamp as an optional field of the store's own record. It never modifies or deletes anything under the checkout's legacy directory.
Reason: The store is runtime-owned and written atomically, so the stamp survives across processes while the user's tree stays untouched. The stamp is a second record write after bind because bind has many write paths. The field is optional and the contract version stays 0.1.
Alternatives considered: A marker file inside the checkout, rejected because it writes into user projects.
## [2026-10-24] QA resource teardown is the innermost lifecycle owner
Context: SKILL-420 QA runs start processes and compose projects that use files inside the checkout.
Decision: The owned-resource closer is the innermost AutoCloseable in the QA lifecycle, so it closes before the checkout, lease and workspace owners.
Reason: Processes and compose projects must stop before the directories they use are removed. The execute exception stays primary; only expected teardown outcomes are caught, never interrupts.

## [2026-10-24] QA unreadable-record warnings come only from the reconcile pass
Context: Release and teardown paths also meet undecodable owned-resource records.
Decision: Only reconcile (reclaimAbandoned at run start) emits the "record unreadable" diagnostic; other paths skip such records silently.
Reason: One warning per run avoids repeated noise. Unreadable records are never deleted, so the operator keeps the evidence.

## [2026-10-24] A reused pid is never signalled
Context: QA process records hold a pid, and pids are reused after the original process exits.
Decision: Process teardown requires a live pid whose start instant equals the recorded one, and refuses the owner pid, the current process and its ancestors.
Reason: A pid alone could kill an unrelated process. A mismatched start instant means the original process is gone, so the record counts as released without any signal.

## [2026-10-24] Only an identity-naming Docker "not found" line counts as removed
Context: Docker removal can fail for many reasons, and a missing binary, timeout or generic error must not drop the record.
Decision: Treat a resource as already removed only when Docker output is a not-found line naming the identity. Everything else is Retained with a redacted, truncated first output line, and the record stays.
Reason: Deleting a record on an ambiguous failure would orphan a live container, network or volume. Reasons never echo the identity.

## [2026-10-24] Foreign owned-resource record decides registration before validation
Context: SKILL-420 register must stop one invocation from attaching a resource another invocation recorded, including a dead run's.
Decision: register checks for an existing record first and returns Conflict naming the owner, even if this invocation could not have created that name. Identity validation runs only when no record exists.
Reason: Validating first could hide a foreign record behind a Rejected result and let a dead run's resource be adopted. Conflict-first keeps ownership literal. Conflict is stable whether the owner is live or dead.
Revisit when: nested invocation ids can share a Docker name prefix with unrecorded resources.

## [2026-10-16] Acceptance evidence references have no character limit
Context: SKILL-428 stopped while saving a focused source audit because the runtime copies audit prose into criterion, closure, and transition evidence references. The schema rejected each reference above 512 characters.
Decision: At the operator's request, remove character limits from evidence references in acceptance audit, repair state, handoff, and adoption schemas. Keep required nonempty values, coherence checks, and aggregate UTF-8 capacity checks. Keep contract version 0.1 because this accepts additional values without changing wire shape or invalidating retained state.
Reason: Audit prose is retained evidence, and a reference field's character cap must not prevent its persistence or later handoff. The focused-audit regression validates and reloads long evidence unchanged. Auxiliary contract tests use long references while retaining their malformed-state rejection cases.

## [2026-10-14] Linux worker identity uses kernel start ticks
Context: The desktop JVM read a live planning runner's start timestamp one second earlier than the CLI had recorded. Exact comparison of those timestamps projected the active run as paused with `runner_interrupted`.
Decision: On Linux, process birth tokens use field 22 of `/proc/<pid>/stat`, prefixed with `linux-start-ticks:`. Host and boot identity remain separate checks. Other platforms retain JDK start timestamps. Duplicate-launch admission requests the inspected owner's start time through the supervisor port instead of interpreting its identity token as a timestamp.
Reason: Kernel start ticks identify a process within a boot without depending on a JVM's wall-clock conversion. A timestamp tolerance could accept a reused PID. An older Linux timestamp that matches remains verifiable; a mismatched legacy timestamp returns an observable unsupported result and cannot authorize termination. Process death still returns not running. No durable record is rewritten during inspection.

## [2026-10-10] Scripted git pins pager off and Git's default rename limit
Context: `worktreeActivity` numstat on a 5_000-file dirty tree returned OK with zero diffstat on the self-hosted macmini runner while `status --porcelain` counted every file. `runCatchingDiffStat` mapped a failed `git diff --numstat` onto empty stats. That runner inherits the operator's global git config, so an unlimited `diff.renameLimit` can make rename detection exceed the 30s git timeout.
Decision: Invoke git with `--no-pager` and `-c diff.renameLimit=1000` (Git's documented default) at `gitArgv`. If unstaged or staged numstat fails, `worktreeActivity` returns ERROR with that error and does not emit a zero diffstat.
Reason: Observability must not treat a timed-out diff as a clean tree. Capping rename detection at the default keeps a handful of real renames coalesced and stops a host gitconfig from turning a large in-place edit into an unbounded comparison.

## [2026-10-08] An unresolved gate JVM is reported from the gate's own failure
Context: The runtime-run gate raised GateJvmUnresolvedException before launch whenever no Java 21+ resolved. Gates for npm, go, cargo, PHP and Python never run Java, so every non-JVM pack was blocked on a JDK it does not use. The runner cannot know in advance whether a pack's gate command needs Java.
Decision: Launch the gate with JAVA_HOME absent and the runtime image pruned. Raise GateJvmUnresolvedException only when the gate exits non-zero, parses no findings, and its output matches a missing-or-too-old-Java marker (wrapper, javac, Gradle toolchain and class-version messages).
Reason: The gate's own output is the only evidence that it needed Java. Classifying that failure as an environment defect keeps it out of the repair loop, as GateJvmStartupFailureException already does for a JVM that cannot start. Marker matching is best-effort, so a gate failure no marker matches becomes an ordinary unparseable_gate_failure finding whose message notes the unresolved Java and names SKILL_BILL_JAVA_HOME.

## [2026-10-05] SKILL-401: retry only expected heartbeat renewal failures
Context: Feature-task and goal heartbeat callbacks renew leases through database transactions. Broad IAE/ISE recovery also intercepted defects.
Decision: Report and reschedule IOException and SkillBillRuntimeException with DatabaseFailureCode.ACCESS or BUSY. Rethrow unrelated codes and defects; retain fencing-loss outcomes and existing expiry escalation.
Reason: Expected persistence failures must not stop lease renewal. Recovering defects as transient failures would hide broken invariants. Existing coded database failures already identify the recoverable boundary without a new renewal outcome or transaction owner.

## [2026-10-05] SKILL-401: retain the shared-evidence diagnostic cause prefix
Context: Stored shared-evidence index rejection emitted a degraded cause beginning with "IllegalArgumentException: ". SKILL-401 removes the constructor-defect catch.
Decision: Validate stored model fields before construction and emit the same ordered reason with the literal prefix. Keep the existing seam, re-derive action and expected-value text.
Reason: The prefix is part of emitted diagnostic bytes. Replacing exception-based control flow must preserve that output even though rejection now arrives as a value.

## [2026-10-14] Validate and settle an existing store before desktop connection
Context: Installation migrates the default runtime store, but desktop selection can reach an older restored or separately selected store. A cached current user_version can also hide a missing migration ledger entry.
Decision: The existing schema port checks the ledger, user_version, and installed Room identity through a read-only connection. It rejects newer or unrecognized stores. A complete supported ledger and current user_version allow schema readiness to repair a missing or stale Room identity without replacing stored rows. When supported migrations are pending, it establishes schema readiness directly rather than trusting the routine-write cache. The desktop consumes this same port through the pinned composition API; installation remains its other consumer.
Reason: An explicit upgrade must settle the observed pending ledger before a read-only consumer connects. The ledger and user_version establish that the runtime schema is current, so a stale desktop read marker can be repaired safely. Routine reads keep their schema checks and never perform this upgrade themselves.

## [2026-11-06] Defer install migration while a worker lease is live
Context: An install applied migrations 103 and 104 while a goal worker started on the previous runtime was still running. The worker then refused the newer schema on its next read and recorded the plan invocation as failed.
Decision: Install apply first asks the schema port whether migrations are pending. If they are, it lists unexpired standalone phase, goal runner execution, and feature-task worker leases through a plain read-only connection and inspects each recorded process. Any lease whose process is not confirmed dead defers the migration with a DATABASE_MIGRATION_DEFERRED warning. The lease query checks table columns first, so it reads stores older than the installing runtime.
Reason: A running JVM keeps the classes it started with, so migrating under it moves the schema ahead of that worker's code. Process inspection, not lease expiry alone, separates live workers from stale rows, because standalone phase leases last a day. The routine-write migration and the desktop connection upgrade are unchanged.

## [2026-11-06] Pin agent Gradle runs to the machine's Gradle user home
Context: Codex validate agents set `GRADLE_USER_HOME="$TMPDIR/gradle"` inline on their `./gradlew` commands. That skipped the machine's global Gradle properties, so its heap and worker limits did not apply, and each run started private daemons with cold caches. A goal run peaked at 33G and systemd-oomd killed it.
Decision: Agent launches add `-Dgradle.user.home=<machine home>` to GRADLE_OPTS. The machine home is the runtime's GRADLE_USER_HOME, or `$HOME/.gradle` when that is unset. The runtime sets no Gradle values of its own. An operator GRADLE_OPTS that already sets `gradle.user.home` is left alone, and a home containing a quote or backslash is not pinned. The validation gate runs the pack argv itself and is not changed.
Reason: Gradle resolves `gradle.user.home` ahead of the GRADLE_USER_HOME variable in both the wrapper and the build, so an inline variable cannot move a build off the machine's settings. A command-line `--gradle-user-home` or a replaced GRADLE_OPTS still can; the agent prompts forbid both.
Revisit when: A supported pack needs agents to run Gradle with a different user home.

## [2026-11-07] Migrate before activating an installation and rebuild cached work
Context: A workflow contract mismatch removed a saved goal from the board while the cache stayed marked complete. Installation also treated a failed or deferred database migration as a warning.
Decision: The staged runtime runs the existing database preparation before activation. Migration failure or a live worker blocks installation and retains the installed runtime. Migration 108 promotes readable workflow envelopes from 0.1 through 0.3 to 0.4 without changing their saved payloads. Unsupported versions and malformed JSON remain unchanged. Migration invalidates the board cache, and board projection retains incompatible workflows as blocked records with a typed problem.
Reason: Workflow payloads, completed steps and history are durable evidence. The board is derived data and must rebuild after an upgrade. The strict workflow reader still validates each migrated snapshot before use.

## [2026-11-08] Order monitor CI cycles in Kotlin and store their own check type
Context: `monitor_ci_cycles` rows store `started_at` as `Instant.toString()` text, whose fraction digits vary, so SQL text ordering is wrong. The cycle record's checks first reused `PullRequestCheck`, which made `idestatus.model` depend on `goalrunner.runner.model` and closed a package cycle in runtime-ports.
Decision: `MonitorCiCycleStore` reads one workflow's rows and orders the parsed instants in Kotlin. `MonitorCiCycleRecord` holds `MonitorCiCycleCheck` with its own `MonitorCiCheckBucket`, and the engine maps `PullRequestCheck` into it when it records an observation.
Reason: One goal has a handful of monitor cycles, so loading them all is cheap. Move ordering into SQL if a workflow ever holds hundreds of cycles. The separate check type keeps runtime-ports acyclic without moving `PullRequestCheck`.
