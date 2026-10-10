# Boundary decisions

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
