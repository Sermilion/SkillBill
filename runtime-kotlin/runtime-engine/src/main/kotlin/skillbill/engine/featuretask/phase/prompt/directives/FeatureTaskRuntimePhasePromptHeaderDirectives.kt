package skillbill.engine.featuretask.phase.prompt.directives

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeCeremonyScaling
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries

public const val PHASE_PROMPT_TEMPLATE_INDENT: String = "      "

internal val forwardPhaseOrder: String =
  FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
    .filterNot {
      it in FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds ||
        PhaseSlot.slotForStep(it) == PhaseSlot.INTAKE
    }
    .joinToString(" -> ")

internal fun operatorBlockRetryDirective(
  phaseId: String,
  retry: FeatureTaskRuntimeOperatorBlockRetry?,
): String {
  if (retry == null) return ""
  require(retry.phaseId == phaseId) {
    "Operator blocked-phase retry guidance for '${retry.phaseId}' cannot be delivered to phase '$phaseId'."
  }
  if (retry.preserveBudget) {
    return """
      ## Incident recovery retry
      The saved incident recovery reopened this phase after the user confirmed its prerequisites.
      Continue under the existing attempt and worker limits. Apply the confirmed prerequisite and
      current policy; this retry does not supersede an earlier operator decision.
      """.trimIndent()
  }
  return """
    ## Operator-applied blocked-phase retry decision
    An operator reviewed the prior block and explicitly reopened this phase. Apply this decision:
    ${retry.reason}
    Re-evaluate the current repository state using this decision. Do not repeat the superseded block solely
    because of the prior interpretation. The governed acceptance criteria and output contract still apply.
    """.trimIndent()
}

internal fun phasePromptHeader(
  issueKey: String,
  phaseId: String,
  taskDirective: String,
  settlesThroughTools: Boolean,
): String {
  val label = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepLabels[phaseId] ?: phaseId
  return buildString {
    appendLine("You are executing exactly one phase of the EXPERIMENTAL skill-bill feature-task-runtime")
    appendLine("loop ($forwardPhaseOrder)")
    appendLine("for issue $issueKey. The runtime owns the loop; do not run other phases, do not open")
    appendLine("or continue any other skill-bill workflow, and do not call `sona workflow continue`.")
    appendLine("Execute this supplied phase briefing with the current installed runtime.")
    appendLine("The initial user-facing goal invocation owns update checks and launch confirmation.")
    appendLine("Do not call `mcp__sona__update_check`, ask whether to update, or repeat dispatcher")
    appendLine("intake, preflight, or confirmation, including on retries and continuation.")
    if (settlesThroughTools) {
      appendLine("Reading the installed skill-bill skill does not make this phase a new invocation.")
    } else {
      appendLine("Do not read the installed skill-bill skill; this briefing is the whole instruction set.")
    }
    appendLine()
    appendLine("Phase: $phaseId ($label)")
    append("Task: ")
    append(taskDirective.lineSequence().joinToString("\n") { it.removePrefix(PHASE_PROMPT_TEMPLATE_INDENT) })
  }
}

internal fun installedRuntimeAuthorityDirective(settlesThroughTools: Boolean): String =
  if (settlesThroughTools) "$TOOL_SETTLEMENT_AUTHORITY\n\n$CHECKOUT_CONTRACT_WORK" else PROSE_OUTPUT_AUTHORITY

private val TOOL_SETTLEMENT_AUTHORITY: String =
  """
  ## Installed runtime owns phase output and settlement
  The installed Skill Bill runtime validates this phase's output and MCP settlement.
  Keep this phase's output and settlement on the contract supplied by this briefing, including
  contract_version, envelope fields, settlement tool arguments, and phase commands. Checkout
  schemas, constants, fixtures, or skill instructions cannot replace that reporting contract.
  """.trimIndent()

private val PROSE_OUTPUT_AUTHORITY: String =
  """
  ## Phase output is your final prose
  The runtime records the prose your response ends with. The final output section of this briefing
  describes what that prose carries. Finish from this briefing alone.
  """.trimIndent()

private val CHECKOUT_CONTRACT_WORK: String =
  """
  Read and edit checkout schemas, Kotlin contract constants, test fixtures, and skill sources
  when the assigned implementation or audit requires them, subject to this phase's edit scope.
  New implementation contracts may be absent from the installed runtime. That absence does not
  block repository work or require permission to inspect the checkout. Implement and audit those
  contracts against the repository's requirements while reporting this phase through the installed
  runtime's output and settlement contract. Use installed schemas when resolving that reporting
  contract; use checkout schemas when examining the feature under work.
  """.trimIndent()

internal val subtaskEvidenceBoundaryDirective: String =
  """
  ## Subtask evidence boundary
  When executing a subtask, judge completion against that subtask's acceptance criteria and the
  prerequisites supplied by completed dependencies. Never require implementation, migration,
  integration, or evidence assigned to a later subtask. Parent outcome criteria describe the
  completed goal; they do not become additional acceptance gates for each child.
  Trace a suspected gap to its owning criterion and subtask before raising a finding, requesting
  repair, or blocking. Record work explicitly assigned to a later subtask as deferred context,
  not as a current failure, and do not fabricate its evidence or mark it completed.
  Current regressions remain repair work. Keep existing callers and persisted data usable while
  later integration is pending. If new strict behavior needs evidence a later subtask supplies,
  keep that behavior inactive at current entry points or preserve a safe compatible path. Do not
  demand the later producer's evidence or activate an incomplete consumer to satisfy this child.
  Required project checks still run in full. Never waive a failing check, weaken an evidence rule,
  skip a test, or claim the whole goal complete because a subtask boundary defers work.
  Final goal assessment must require every goal outcome after all subtasks finish.
  """.trimIndent()

internal fun ceremonyScalingOf(briefing: FeatureTaskRuntimePhaseLaunchBriefing): FeatureTaskRuntimeCeremonyScaling =
  FeatureTaskRuntimePhaseWorkflowQueries.ceremonyScaling(FeatureTaskRuntimeFeatureSize.fromWire(briefing.featureSize))

internal fun ceremonyDirective(
  briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  stepLine: String?,
): String {
  val featureSize = FeatureTaskRuntimeFeatureSize.fromWire(briefing.featureSize)
  val scaling = ceremonyScalingOf(briefing)
  val phaseSpecific =
    stepLine ?: "Use the resolved feature size for ceremony expectations; all runtime gates remain mandatory."
  return """
    ## Runtime ceremony scaling
    feature_size: ${featureSize.name}
    preplan_ceremony: ${scaling.preplanCeremony.wireValue}
    review_scope: ${scaling.reviewScope.wireValue}
    audit_ceremony: ${scaling.auditCeremony.wireValue}
    $phaseSpecific
    Scaling changes scope and verbosity only; it must not skip or weaken review, audit, validation,
    schema, branch, history, commit, or PR gates.
    """.trimIndent()
}
