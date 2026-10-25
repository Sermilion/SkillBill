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
    "Before choosing a host or container, read the applicable repository instructions and CI/setup scripts. " +
    "Before an expensive validation command, check the required toolchain and tools, writable home, cache, " +
    "and temporary paths, and any required display support. Prefer a known working host or the established " +
    "isolated setup required by those instructions. Keep useful setup and discovery context in this invocation, " +
    "and reuse only caches and mutable state this invocation is allowed to own. Never reuse another worktree's " +
    "mutable state. Do not install packages automatically, stop a shared daemon, or change unrelated jobs. " +
    "Revisit setup when relevant scripts, configuration, toolchain, or workspace requirements change; " +
    "an earlier setup does not prove an uninspected change is safe. " +
    "For each repair candidate, run every required local check whose tooling is available before publication, " +
    "preserve clean and cache-bypass requirements, and do not count a previous candidate's pass as current evidence. " +
    "When preflight or command output confirms that a required tool, SDK, or host platform is unavailable, " +
    "skip only the checks that depend on it and continue all independent checks. This includes packaging " +
    "utilities missing on Linux and targets requiring macOS with Xcode on a Linux host. Report each skip " +
    "as a warning naming the exact command or target, missing prerequisite, and confirming evidence. " +
    "Never count a skipped check as passed. If an aggregate command stops solely at unavailable tooling, " +
    "run its remaining independent tasks separately. Skip artifact checks only when they require an " +
    "artifact that the unavailable tooling must produce, and report that dependency. Do not edit project " +
    "configuration, disable targets, or add test skips to work around missing tooling. Source, dependency, " +
    "test, analysis, formatting, fixture, and configuration failures still require repair; an unexplained " +
    "command failure is not evidence of unavailable tooling. " +
    "If you find no applicable validation commands after inspecting those sources, print a warning " +
    "that no validation commands were found, then settle completed and allow the workflow to advance. " +
    "Do not invent commands or report checks as passed when none ran. A failed discovery command or " +
    "unreadable project configuration is an obstacle, not evidence that no checks exist. " +
    "Run the full project validation when checks exist, including required tests, static analysis, " +
    "formatting checks, and " +
    "repository checks. Compilation alone is insufficient. " +
    "Validation owns running checks and fixing every failure, including build failures. Run the project's " +
    "build and packaging commands when checks need them, including the pack build_command. Prepare missing " +
    "or stale artifacts in this session. Rebuild after source repairs so the artifacts match the candidate " +
    "under validation, then rerun every runnable required check. Do not return to an earlier phase or ask " +
    "the operator to rebuild artifacts you can create here. The separate compile-only build gate does not restrict " +
    "builds required by validation. Preserve artifact identity and integrity checks; do not bypass them " +
    "or count a rebuild alone as validation success. " +
    "Do not recursively invoke `skill-bill phase validation`. " +
    "Keep repairing in this same session until every runnable required project check passes. Do not spawn delegated " +
    "subagents. Settle completed when every runnable required check passes, with warnings for unavailable " +
    "tooling coverage. Missing tooling alone never blocks validation. If no checks can run because their " +
    "tooling is unavailable, complete with warnings and state that no checks ran. Name the checks you ran " +
    "and how each ended. Build failures, test failures, static-analysis findings, formatting failures, " +
    "and outdated fixtures are " +
    "repair work in this session: fix a formatting failure with the project's formatter, then rerun the " +
    "required checks. No failure, formatting included, hands the work to another validation session. " +
    "Do not stop after reducing the failure count or return a partial progress report. Keep fixing and " +
    "rerunning the runnable required checks in this session until they all pass. " +
    "Settle blocked only for a concrete external obstacle other than confirmed unavailable tooling that " +
    "you cannot resolve, and describe the obstacle, " +
    "any setup retries, the required operator action, and any remaining failures in ordinary prose. " +
    "Use the existing blocked and recovery behavior. Wall-clock timeout still stops " +
    "the subtask. The runtime does not rerun the checks itself. Report each command and its result, " +
    "or the warning when discovery finds none. Never silence findings with annotations, " +
    "baselines, disabled rules, weakened configuration, or skipped tests; fix root causes instead. " +
    "Discover the applicable checks independently from your existing plan and current repository-scope inputs. " +
    "Authoring phases may have run or deferred scoped formatter and analysis commands, but their reports are " +
    "not delivered here in full and never clear a build or validation gate; deferred authoring checks are " +
    "still discovered and run here."
}

internal fun validateGateTriagePhaseTask(): String =
  "You are triaging an unparseable validation gate failure blob before the first repair turn — do not spawn " +
    "delegated subagents. Read the gate stdout blob and repository files as needed to understand failures; " +
    "prefer read-only inspection. $VALIDATE_PHASE_FORBIDDEN_EXTRAS$VALIDATE_TRIAGE_FORBIDDEN_PACK_GATE" +
    "Do not mutate the tree unless strictly needed to understand failures. Reply with a short prose " +
    "repair plan naming where each failure lives and how you would fix it. Return prose guidance only; " +
    "do not fix code or report validation results or gate evidence."

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
    "re-runs the pack build command and mints the receipt; report nothing but prose."
}

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
  "Run only the pack build_command. Do not run collect_all_full_gate_command, check " + "--" + "continue,\n" +
    "skill-bill validate, or skill-bill phase validation. Gate runs and their evidence are runtime-measured."
