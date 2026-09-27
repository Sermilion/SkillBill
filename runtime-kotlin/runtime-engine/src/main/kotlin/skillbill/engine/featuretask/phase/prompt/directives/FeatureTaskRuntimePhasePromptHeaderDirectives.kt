package skillbill.engine.featuretask.phase.prompt.directives

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeCeremonyScaling
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.repair.task.FeatureTaskRuntimeOperatorBlockRetry
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
    appendLine()
    appendLine("Phase: $phaseId ($label)")
    append("Task: ")
    append(taskDirective.lineSequence().joinToString("\n") { it.removePrefix(PHASE_PROMPT_TEMPLATE_INDENT) })
  }
}

fun installedRuntimeAuthorityDirective(): String =
  """
  ## Installed runtime is the contract source
  This briefing was composed by the installed Skill Bill runtime that will validate your output.
  Use the contract_version, envelope fields, skills, packs, and commands it names.
  Do not read orchestration/contracts, Kotlin contract constants, test fixtures, or skills/ in
  this workspace to override them. When this repository is skill-bill, those files are the change
  under work, not the validator. If you must inspect a schema, inspect the installed runtime copy,
  never this checkout.
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
