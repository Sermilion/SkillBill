package skillbill.engine.featuretask.slot.qualitygate

import skillbill.engine.featuretask.model.phase.ValidationCommandLaunchTransport
import skillbill.engine.featuretask.model.phase.ValidationFindingSetProjection

private const val VALIDATE_PHASE_FORBIDDEN_EXTRAS: String =
  "Do not run `skill-bill validate`, `npx agnix`, `scripts/validate_agent_configs`, or any other " +
    "repo-root checklist. Those commands are not this phase. "

private const val VALIDATE_TRIAGE_FORBIDDEN_PACK_GATE: String =
  "Do not run `skill-bill phase validation`, the pack validation_gate collect_all_full_gate_command, " +
    "cache_bypassing_collect_all_full_gate_command, or any other pack-declared full-suite argv " +
    "during this triage turn. "

private const val MACHINE_BUILD_SETTINGS_DIRECTIVE: String =
  "Run build tools with the machine's own settings and shared caches. Do not point a build tool at a " +
    "private home or cache: for Gradle, never set GRADLE_USER_HOME, GRADLE_OPTS, or JAVA_TOOL_OPTIONS, " +
    "and never pass --gradle-user-home or -g. "

private const val BUILD_PHASE_FORBIDDEN_EXTRAS: String =
  "Do not run `skill-bill validate`, `npx agnix`, `scripts/validate_agent_configs`, " +
    "`skill-bill phase validation`, `./gradlew check`, `check " + "--" + "continue`, or the pack " +
    "collect_all_full_gate_command. Those are not this phase. "

internal fun savedValidationAgentDirective(
  transport: ValidationCommandLaunchTransport?,
  packValidationCommand: String?,
  triage: Boolean,
): Pair<String, Boolean> =
  when (transport) {
    is ValidationCommandLaunchTransport.Commands -> {
      val directive =
        if (triage) {
          validateGateTriagePhaseTask(transport.rendered)
        } else {
          runtimeOwnedValidateAgentPhaseTask(transport.rendered)
        }
      directive to false
    }
    is ValidationCommandLaunchTransport.KnownEmpty -> {
      val directive =
        if (triage) {
          validateGateTriageKnownEmptyPhaseTask()
        } else {
          runtimeOwnedValidateAgentKnownEmptyPhaseTask()
        }
      directive to false
    }
    null -> {
      val directive =
        if (triage) {
          validateGateTriagePhaseTask(packValidationCommand)
        } else {
          runtimeOwnedValidateAgentPhaseTask(packValidationCommand)
        }
      directive to (packValidationCommand != null)
    }
  }

internal fun runtimeOwnedValidateAgentKnownEmptyPhaseTask(): String =
  "You are the validation agent for this step — do not spawn delegated subagents. " +
    "The admitted validation command list is explicitly empty after completed discovery. " +
    VALIDATE_PHASE_FORBIDDEN_EXTRAS +
    MACHINE_BUILD_SETTINGS_DIRECTIVE +
    "Print a warning that no validation commands were found, then settle completed " +
    "and allow the workflow to advance. " +
    "Do not invent commands or report checks as passed when none ran. " +
    "Settle blocked only for a concrete external obstacle."

internal fun validateGateTriageKnownEmptyPhaseTask(): String =
  "You are triaging an unparseable validation gate failure blob before the first repair turn — do not spawn " +
    "delegated subagents. The admitted validation command list is explicitly empty after completed discovery; " +
    "there is no saved command boundary to triage beyond confirming the known-empty outcome. " +
    "$VALIDATE_PHASE_FORBIDDEN_EXTRAS$VALIDATE_TRIAGE_FORBIDDEN_PACK_GATE" +
    "Reply with a short prose repair plan only if the gate blob still implies actionable failures; " +
    "otherwise return prose that the validation boundary is empty."

internal fun runtimeOwnedValidateAgentPhaseTask(packValidationCommand: String?): String {
  val rediscoveryPolicy =
    "The runtime already resolved missing configuration; do not rediscover checks from scripts, CI, or packs."
  val gateLine =
    when {
      packValidationCommand.isNullOrBlank() ->
        "Run only the admitted saved validation command list and its project-relative working directories. " +
          rediscoveryPolicy
      packValidationCommand.contains(" ; ") ->
        "Run only these admitted saved validation commands (with their directories) in order: " +
          "`$packValidationCommand`. $rediscoveryPolicy"
      else ->
        "Run only this admitted saved validation command and its directory: `$packValidationCommand`. " +
          rediscoveryPolicy
    }
  return "You are the validation agent for this step — do not spawn delegated subagents. $gateLine " +
    VALIDATE_PHASE_FORBIDDEN_EXTRAS +
    MACHINE_BUILD_SETTINGS_DIRECTIVE +
    "Never reuse another worktree's mutable state; the machine's shared tool caches are not worktree state. " +
    "Do not install packages automatically, stop a shared daemon, or change unrelated jobs. " +
    "For each repair candidate, rerun every saved runnable validation command before publication and " +
    "do not count a previous candidate's pass as current evidence. " +
    "When preflight or command output confirms that a required tool, SDK, or host platform is unavailable, " +
    "skip only the saved commands that depend on it and continue all independent saved commands. Report each skip " +
    "as a warning naming the exact command or target, missing prerequisite, and confirming evidence. " +
    "Never count a skipped check as passed. If a saved aggregate stops solely at unavailable tooling, " +
    "report the aggregate coverage it did not reach rather than inventing a smaller command set. " +
    "Do not edit project configuration, disable targets, or add test skips to work around missing tooling. " +
    "Source, dependency, test, analysis, formatting, fixture, and configuration failures still require repair. " +
    "When the admitted list is explicitly empty, print a warning that no validation commands were found, " +
    "then settle completed and allow the workflow to advance. " +
    "Do not invent commands or report checks as passed when none ran. " +
    "Put every temporary copy, file, or directory this session creates under ${'$'}TMPDIR. " +
    "Do not recursively invoke `skill-bill phase validation`. " +
    "Keep repairing in this same session until every runnable saved check passes. " +
    "Settle completed when every runnable saved check passes, with warnings for unavailable tooling coverage. " +
    "If no saved checks can run because their tooling is unavailable, complete with warnings and " +
    "state that no checks ran. " +
    "Settle blocked only for a concrete external obstacle other than confirmed unavailable tooling. " +
    "The runtime does not rerun the checks itself. Never silence findings with annotations, " +
    "baselines, disabled rules, weakened configuration, or skipped tests; fix root causes instead."
}

internal fun validateGateTriagePhaseTask(packValidationCommand: String? = null): String {
  val savedCommandsDirective =
    if (packValidationCommand.isNullOrBlank()) {
      "The admitted saved validation command list is the complete ordinary validation boundary for this launch."
    } else {
      "The admitted saved validation commands are `$packValidationCommand` — run that list alone in order."
    }
  return "You are triaging an unparseable validation gate failure blob before the first repair turn — do not spawn " +
    "delegated subagents. Read the gate stdout blob and repository files as needed to understand failures; " +
    "prefer read-only inspection. $savedCommandsDirective " +
    "$VALIDATE_PHASE_FORBIDDEN_EXTRAS$VALIDATE_TRIAGE_FORBIDDEN_PACK_GATE" +
    "Do not mutate the tree unless strictly needed to understand failures. Reply with a short prose " +
    "repair plan naming where each failure lives and how you would fix it. Return prose guidance only; " +
    "do not fix code or report validation results or gate evidence."
}

internal fun runtimeOwnedBuildPhaseTask(packBuildCommand: String?): String {
  val gateLine =
    when {
      packBuildCommand.isNullOrBlank() ->
        "Run only the admitted saved build command(s) frozen at admission and read that command output."
      packBuildCommand.contains(" ; ") ->
        "Run only these admitted saved build commands (with their directories) and read their output: " +
          "`$packBuildCommand`."
      else ->
        "Run only this admitted saved build command and read that command's output: `$packBuildCommand`."
    }
  return "You are the only build agent for this step — do not spawn delegated subagents. The runtime may " +
    "give you up to three repair turns against the remaining findings; each turn is another session of " +
    "this same agent. $gateLine $BUILD_PHASE_FORBIDDEN_EXTRAS$MACHINE_BUILD_SETTINGS_DIRECTIVE" +
    "This phase is compile/buildability proof only: no suite tests, no full check, no substitute " +
    "agent-run gate. Address every open finding in the same turn. Do not rerun the admitted build commands " +
    "after each individual fix; targeted compile tasks are allowed while repairing when they are " +
    "part of the same admitted list. When the set looks clean, you may run one saved command once to " +
    "sanity-check. Never silence findings with @Suppress, @file:Suppress, baselines, disabled rules, " +
    "weakened configuration, or skipped tests — fix root causes instead. After you stop, the runtime " +
    "re-runs the full admitted build command list and mints the receipt; report nothing but prose."
}

internal fun buildGateTriagePhaseTask(packBuildCommand: String?): String {
  val gateLine =
    when {
      packBuildCommand.isNullOrBlank() ->
        "The admitted saved build command list provides build proof for this run."
      packBuildCommand.contains(" ; ") ->
        "The admitted saved build commands are `$packBuildCommand` — do not run them during triage."
      else ->
        "The admitted saved build command is `$packBuildCommand` — do not run it during triage."
    }
  return "You are triaging an unparseable build gate failure blob before the first repair turn — do not spawn " +
    "delegated subagents. Read the gate stdout blob and repository files as needed; prefer read-only " +
    "inspection. $gateLine $BUILD_PHASE_FORBIDDEN_EXTRAS" +
    "Do not mutate the tree unless strictly needed to understand failures. Reply with a short prose " +
    "repair plan naming where each failure lives and how you would fix it. Return prose guidance only; " +
    "do not fix code or report build receipts or gate evidence."
}

internal fun gateRepairNoOutputSchemaDirective(
  stepName: String,
  triage: Boolean = false,
): String {
  if (triage) {
    return """
      ## Gate triage — prose only
      This launch triages an unparseable gate blob before the first repair turn for the runtime-owned `$stepName` gate.
      Report no receipt, status object, or gate evidence. Do not spawn delegated subagents. Read the blob and
      cited paths. When you can recommend a repair shape, say so in plain prose. A missing or short answer is
      fine; repair still runs without it.
      """.trimIndent()
  }
  return """
    ## Gate repair — prose only
    This launch is a repair turn for the runtime-owned `$stepName` gate. Report no receipt, status object,
    or gate evidence. Do not spawn delegated subagents. Work in this single agent session in ordinary prose.

    The runtime already ran the pack command and parsed the failures listed in this briefing. It will
    re-run that command after you stop, and it may give you up to three repair turns against whatever
    remains.

    Before editing, do brief reasoned planning in prose for each finding (or for a shared root cause
    that covers several). Scale the plan to the finding:
    - Small / obvious: a few lines of due diligence, then fix.
    - Complex: a real short plan — blast radius, surrounding callers/contracts you checked, whether
      the change can introduce new bugs, and how you will keep the fix local.

    No defined plan schema. Do the thinking, then edit. After you have attempted a fix for every open
    finding, you may run targeted proof commands relevant to those findings (the tool or task named in
    the finding). Stop when done; the runtime re-runs the pack gate.
    Never silence findings with @Suppress, @file:Suppress, baselines, disabled rules, weakened
    configuration, or skipped tests — fix the root cause instead.
    """.trimIndent()
}

internal fun buildGateFindingsDirective(
  findings: ValidationFindingSetProjection?,
  triagePlan: String?,
  gateLabel: String = "build",
  commandLabel: String? = "build",
): String {
  if (findings == null) return ""
  val commandGuidance =
    commandLabel?.let {
      "Run only the pack-declared $it command when you need console detail. "
    }.orEmpty()
  val lines =
    buildList {
      add("## Runtime $gateLabel gate findings")
      add(
        "A prior gate run parsed these items. They are the full open set for this repair turn — fix every one in " +
          "this session (shared root causes may collapse several into one change). " +
          commandGuidance + "Do not run `skill-bill validate`, " +
          "`skill-bill phase validation`, or the pack collect_all_full_gate_command. Do not spawn delegated " +
          "subagents.",
      )
      findings.findings.forEachIndexed { index, finding ->
        add(
          "${index + 1}. module=${finding.module} id=${finding.ruleOrTestId} " +
            "failure_subject=${finding.failureSubject.wireValue} " +
            "(unknown means attribution is not established) " +
            "location=${finding.location ?: "<unknown>"} message=${finding.message}",
        )
      }
      if (!triagePlan.isNullOrBlank()) {
        add("## Triage working notes")
        add(triagePlan)
      }
    }
  return lines.joinToString("\n")
}

internal const val VALIDATE_VALUE_CONTENT: String =
  "Say in prose which required project checks you ran and how each ended.\n" +
    "Report each check skipped for unavailable tooling as a warning with its command or target, missing " +
    "prerequisite, and confirming evidence. Never report skipped coverage as passed.\n" +
    "Complete when every runnable required check passes, even if unavailable tooling prevents other checks. " +
    "Fix every other check failure in this session; missing tooling alone never blocks validation.\n" +
    "Gate runs and their evidence are runtime-measured.\n" +
    "Never introduce suppressions, baselines, disabled rules, or skipped tests to silence findings."

internal const val BUILD_VALUE_CONTENT: String =
  "Run only the admitted saved build command list. Do not run collect_all_full_gate_command, check " +
    "--" + "continue,\n" +
    "skill-bill validate, or skill-bill phase validation. Gate runs and their evidence are runtime-measured."
