package skillbill.engine.work.incident

import skillbill.contracts.workflow.goal.GoalQualityExecutionPayloadKeys
import skillbill.engine.goalrunner.persist.blockincident.GoalBlockArtifactKeys
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentResponsibleCategory
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportStatus

const val SKILL_BILL_NEW_ISSUE_URL: String = "https://github.com/Sermilion/SkillBill/issues/new"

internal data class WorkIncidentRemedyFacts(
  val incidentToken: String,
  val unblockCommand: String?,
  val responsibleCategory: BlockIncidentResponsibleCategory?,
  val noWorkaroundKnown: Boolean,
  val reportStatus: ProductReportStatus,
)

internal fun workIncidentRemedy(facts: WorkIncidentRemedyFacts): String? {
  facts.unblockCommand?.let { return "Run: $it" }
  if (facts.reportStatus in REPORTED_STATUSES) return null
  val fileable =
    facts.responsibleCategory == BlockIncidentResponsibleCategory.SKILL_BILL_DEFECT ||
      (facts.noWorkaroundKnown && facts.responsibleCategory == BlockIncidentResponsibleCategory.UNRESOLVED)
  return if (fileable) {
    "If this is a skill-bill bug, file an issue at $SKILL_BILL_NEW_ISSUE_URL " +
      "and include incident ${facts.incidentToken}."
  } else {
    null
  }
}

internal object WorkIncidentUnblockCommands {
  fun resolve(
    unit: UnitOfWork,
    incident: BlockIncident,
  ): String? {
    val goalWorkflowId = incident.occurrence.parentWorkflowId ?: incident.occurrence.workflowId ?: return null
    val goal = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, goalWorkflowId) ?: return null
    if (goal.workflowStatus != WorkflowStatus.BLOCKED) return null
    return goal.recordedRecoveryCommand() ?: freshQualityCommand(goal)
  }

  private fun WorkflowStateSnapshot.recordedRecoveryCommand(): String? =
    (artifacts[GoalBlockArtifactKeys.BLOCKED_RECOVERY_COMMAND] as? String)?.takeIf(String::isNotBlank)

  private fun freshQualityCommand(goal: WorkflowStateSnapshot): String? =
    freshQualityCommand(goal.workflowStatus, goal.goalQualityReadiness()) { goal.decompositionRuntime()?.issueKey }

  fun freshQualityCommand(
    status: WorkflowStatus,
    readiness: GoalQualityReadiness?,
    issueKey: () -> String?,
  ): String? {
    if (status != WorkflowStatus.BLOCKED || readiness != GoalQualityReadiness.HISTORICAL_AMBIGUITY) return null
    return issueKey()?.let { "skill-bill goal fresh-quality $it --reason \"<why>\"" }
  }

  private fun WorkflowStateSnapshot.goalQualityReadiness(): GoalQualityReadiness? {
    val record = DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.value(artifacts) as? Map<*, *> ?: return null
    val wire = record[GoalQualityExecutionPayloadKeys.READINESS] as? String ?: return null
    return GoalQualityReadiness.entries.firstOrNull { it.wireValue == wire }
  }
}

private val REPORTED_STATUSES =
  setOf(ProductReportStatus.DELIVERED, ProductReportStatus.LINKED_EXISTING)
