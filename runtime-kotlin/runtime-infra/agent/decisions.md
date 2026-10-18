# Boundary decisions

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
Decision: The existing schema port checks the ledger, user_version, and any installed Room identity through a read-only connection. It rejects newer or unrecognized stores. When supported migrations are pending, it establishes schema readiness directly rather than trusting the routine-write cache. The desktop consumes this same port through the pinned composition API; installation remains its other consumer.
Reason: An explicit upgrade must settle the observed pending ledger before a read-only consumer connects. Routine reads keep their schema checks and never perform this upgrade themselves.
