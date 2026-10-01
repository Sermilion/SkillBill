package skillbill.engine.featuretask.phase.prompt.directives

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeCeremonyScaling
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries

const val PHASE_PROMPT_TEMPLATE_INDENT = "      "

val forwardPhaseOrder: String =
  FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
    .filterNot { it in FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds }
    .joinToString(" -> ")

fun operatorBlockRetryDirective(
  phaseId: String,
  retry: FeatureTaskRuntimeOperatorBlockRetry?,
): String {
  if (retry == null) return ""
  require(retry.phaseId == phaseId) {
    "Operator blocked-phase retry guidance for '${retry.phaseId}' cannot be delivered to phase '$phaseId'."
  }
  return """
    ## Operator-applied blocked-phase retry decision
    An operator reviewed the prior block and explicitly reopened this phase. Apply this decision:
    ${retry.reason}
    Re-evaluate the current repository state using this decision. Do not repeat the superseded block solely
    because of the prior interpretation. The governed acceptance criteria and output contract still apply.
    """.trimIndent()
}

fun phasePromptHeader(
  issueKey: String,
  phaseId: String,
  taskDirective: String,
): String {
  val label = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepLabels[phaseId] ?: phaseId
  return buildString {
    appendLine("You are executing exactly one phase of the EXPERIMENTAL skill-bill feature-task-runtime")
    appendLine("loop ($forwardPhaseOrder)")
    appendLine("for issue $issueKey. The runtime owns the loop; do not run other phases, do not open")
    appendLine("or continue any other skill-bill workflow, and do not call `skill-bill workflow continue`.")
    appendLine("Execute this supplied phase briefing with the current installed runtime.")
    appendLine("The initial user-facing goal invocation owns update checks and launch confirmation.")
    appendLine("Do not call `mcp__skill-bill__update_check`, ask whether to update, or repeat dispatcher")
    appendLine("intake, preflight, or confirmation, including on retries and continuation.")
    appendLine("Reading the installed skill-bill skill does not make this phase a new invocation.")
    appendLine()
    appendLine("Phase: $phaseId ($label)")
    append("Task: ")
    append(taskDirective.lineSequence().joinToString("\n") { it.removePrefix(PHASE_PROMPT_TEMPLATE_INDENT) })
  }
}

fun installedRuntimeAuthorityDirective(): String =
  """
  ## Installed runtime owns phase output and settlement
  The installed Skill Bill runtime validates this phase's output and MCP settlement.
  Keep this phase's output and settlement on the contract supplied by this briefing, including
  contract_version, envelope fields, settlement tool arguments, and phase commands. Checkout
  schemas, constants, fixtures, or skill instructions cannot replace that reporting contract.

  Read and edit checkout schemas, Kotlin contract constants, test fixtures, and skill sources
  when the assigned implementation or audit requires them, subject to this phase's edit scope.
  New implementation contracts may be absent from the installed runtime. That absence does not
  block repository work or require permission to inspect the checkout. Implement and audit those
  contracts against the repository's requirements while reporting this phase through the installed
  runtime's output and settlement contract. Use installed schemas when resolving that reporting
  contract; use checkout schemas when examining the feature under work.
  """.trimIndent()

fun ceremonyScalingOf(briefing: FeatureTaskRuntimePhaseLaunchBriefing): FeatureTaskRuntimeCeremonyScaling =
  FeatureTaskRuntimePhaseWorkflowQueries.ceremonyScaling(FeatureTaskRuntimeFeatureSize.fromWire(briefing.featureSize))

fun ceremonyDirective(
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
