package skillbill.engine.featuretask.slot.qualitygate

import skillbill.engine.featuretask.model.phase.ValidationFindingSetProjection

private const val VALIDATE_PHASE_FORBIDDEN_EXTRAS: String =
  "Do not run `skill-bill validate`, `npx agnix`, `scripts/validate_agent_configs`, or any other " +
    "repo-root checklist. Those commands are not this phase. "

private const val VALIDATE_TRIAGE_FORBIDDEN_PACK_GATE: String =
  "Do not run `skill-bill phase validation`, the pack validation_gate collect_all_full_gate_command, " +
    "cache_bypassing_collect_all_full_gate_command, or any other pack-declared full-suite argv " +
    "during this triage turn. "

private const val BUILD_PHASE_FORBIDDEN_EXTRAS: String =
  "Do not run `skill-bill validate`, `npx agnix`, `scripts/validate_agent_configs`, " +
    "`skill-bill phase validation`, `./gradlew check`, `check " + "--" + "continue`, or the pack " +
    "collect_all_full_gate_command. Those are not this phase. "

internal fun runtimeOwnedValidateAgentPhaseTask(): String {
  return "Discover the validation checks required by this project from its repository instructions, " +
    "build and test configuration, scripts, and CI workflows. Use the project's commands and environment. " +
    "Run the full project validation, including required tests, static analysis, formatting checks, and " +
    "repository checks. Compilation alone is insufficient. Project commands remain allowed when a pack " +
    "also declares them. Do not recursively invoke `skill-bill phase validation`. " +
    "Keep repairing in this same session until every required project check passes. Do not spawn delegated " +
    "subagents. Settle completed only when every required check passes, with the checks run as the value. " +
    "Test failures, static-analysis findings, formatting failures, and outdated fixtures are repair work. " +
    "Do not stop after reducing the failure count or return a partial progress report. Keep fixing and " +
    "rerunning the required checks in this session until they all pass. " +
    "Settle blocked only for a concrete external obstacle you cannot resolve, and describe the obstacle, " +
    "the required operator action, and any remaining failures in the value. Wall-clock timeout still stops " +
    "the subtask. The runtime does not rerun the checks itself. Never silence findings with annotations, " +
    "baselines, disabled rules, weakened configuration, or skipped tests; fix root causes instead."
}

internal fun validateGateTriagePhaseTask(): String =
  "You are triaging an unparseable validation gate failure blob before the first repair turn — do not spawn " +
    "delegated subagents. Read the gate stdout blob and repository files as needed to understand failures; " +
    "prefer read-only inspection. $VALIDATE_PHASE_FORBIDDEN_EXTRAS$VALIDATE_TRIAGE_FORBIDDEN_PACK_GATE" +
    "Do not mutate the tree unless strictly needed to understand failures. Emit a recommended " +
    "validation_repair_plan as prose inside produced_outputs.value (JSON string) with suggested fields per " +
    "item: item_id, module, rule_or_task, location, failure_summary, fix_intent. Extra keys are allowed. " +
    "Return prose guidance only; do not fix code or emit validation_result, gate_run_count, or gate evidence."

internal fun runtimeOwnedBuildPhaseTask(packBuildCommand: String?): String {
  val gateLine =
    if (packBuildCommand.isNullOrBlank()) {
      "Run only the pack-declared validation_gate build_command and read that command's output."
    } else {
      "Run only this pack-declared build command and read that command's output: `$packBuildCommand`."
    }
  return "You are the only build agent for this step — do not spawn delegated subagents. The runtime may " +
    "give you up to three repair turns against the remaining findings; each turn is another session of " +
    "this same agent. $gateLine $BUILD_PHASE_FORBIDDEN_EXTRAS" +
    "This phase is compile/buildability proof only: no suite tests, no full check, no substitute " +
    "agent-run gate. Address every open finding in the same turn. Do not rerun the pack build command " +
    "after each individual fix; targeted compile tasks are allowed while repairing when they are " +
    "part of that same pack gate. When the set looks clean, you may run that same build command once to " +
    "sanity-check. Never silence findings with @Suppress, @file:Suppress, baselines, disabled rules, " +
    "weakened configuration, or skipped tests — fix root causes instead. After you stop, the runtime " +
    "re-runs the pack build command and mints the receipt — do not emit build_receipt, gate_run_count, " +
    "or any phase-output JSON."
}

internal fun runtimeOwnedPackValidationPhaseTask(packCommand: String?): String {
  val gateLine =
    if (packCommand.isNullOrBlank()) {
      "The runtime runs the dominant pack's collect_all_full_gate_command and reads its output."
    } else {
      "The runtime runs the dominant pack's collect_all_full_gate_command: `$packCommand`."
    }
  return "Repair every finding from the runtime-owned full validation gate in this session. $gateLine " +
    "Do not run another project-wide validation command and do not emit a validation receipt or gate evidence. " +
    "Do not spawn delegated subagents. After repair, the runtime runs the cache-bypassing full validation command " +
    "once to verify the repository. Never silence findings with suppressions, baselines, disabled rules, " +
      "or skipped tests."
}

internal fun packValidationGateTriagePhaseTask(packCommand: String?): String =
  "Triage the unparseable runtime-owned validation gate failure before repair. Read the captured output and " +
    "repository files as needed, but do not run the pack gate or mutate files. The dominant pack's discovery " +
    "command is ${packCommand?.let { "`$it`" } ?: "collect_all_full_gate_command"}. Emit a concise " +
    "validation_repair_plan in produced_outputs.value. Do not emit validation evidence or spawn subagents."

internal fun buildGateTriagePhaseTask(packBuildCommand: String?): String {
  val gateLine =
    if (packBuildCommand.isNullOrBlank()) {
      "The dominant pack declares validation_gate.build_command for build proof."
    } else {
      "The pack build command is `$packBuildCommand` — do not run it during triage."
    }
  return "You are triaging an unparseable build gate failure blob before the first repair turn — do not spawn " +
    "delegated subagents. Read the gate stdout blob and repository files as needed; prefer read-only " +
    "inspection. $gateLine $BUILD_PHASE_FORBIDDEN_EXTRAS" +
    "Do not mutate the tree unless strictly needed to understand failures. Emit a recommended " +
    "validation_repair_plan as prose inside produced_outputs.value (JSON string) with suggested fields per " +
    "item: item_id, module, rule_or_task, location, failure_summary, fix_intent. Extra keys are allowed. " +
    "Return prose guidance only; do not fix code or emit build_receipt, gate_run_count, or gate evidence."
}

internal fun gateRepairNoOutputSchemaDirective(stepName: String, triage: Boolean = false): String {
  if (triage) {
    return """
      ## Gate triage — optional capture surface, no phase-output schema
      This launch triages an unparseable gate blob before the first repair turn for the runtime-owned `$stepName` gate.
      Do not emit a Required final output JSON object, build_receipt, validation_receipt, gate_run_count, or any other
      phase receipt or gate evidence. Do not spawn delegated subagents. Read the blob and cited paths.
      When you can recommend a repair shape, you may emit produced_outputs.value (a JSON string) carrying
      validation_repair_plan prose with suggested fields per item: item_id, module, rule_or_task, location,
      failure_summary, fix_intent. Malformed or missing capture is fine; repair still runs without it.
    """.trimIndent()
  }
  return """
    ## Gate repair — prose only, no phase-output schema
    This launch is a repair turn for the runtime-owned `$stepName` gate. Do not emit a Required final
    output JSON object, build_receipt, validation_receipt, gate_run_count, or any other phase envelope.
    Do not spawn delegated subagents. Work in this single agent session in ordinary prose.

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
  val commandGuidance = commandLabel?.let {
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
  "Settle completed only when every required project check passed, with a non-blank value of checks run.\n" +
    "Fix every check failure in this session before returning a final result.\n" +
    "Do not return a partial progress report. " +
    "Settle blocked only for an external obstacle requiring operator action.\n" +
    "Do not emit validation_evidence, validation_result, gate_run_count, or gate_runs.\n" +
    "Never introduce suppressions, baselines, disabled rules, or skipped tests to silence findings."

internal const val BUILD_VALUE_CONTENT: String =
  "Run only the pack build_command. Do not run collect_all_full_gate_command, check " + "--" + "continue,\n" +
    "skill-bill validate, or skill-bill phase validation. gate_run_count and gate_runs are runtime-measured."
