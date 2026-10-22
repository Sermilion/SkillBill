package skillbill.engine.goalrunner.execution.support

import skillbill.engine.goalrunner.model.GoalRunnerWedgeClass
import skillbill.engine.goalrunner.model.GoalRunnerWedgeFinding
import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.engine.goalrunner.repair.PASSED_CONTINUATION_OUTCOME
import skillbill.goalrunner.derivedTerminalOutcomeFor
import skillbill.goalrunner.goalContinuationOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.nonCompleteStoredOutcomeIsCorroborated
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.persistence.goalContinuation

internal data class GoalRunnerStaleBlockedOutcomeContext(
  val record: WorkflowStateSnapshot,
  val artifacts: Map<String, Any?>,
  val issueKey: String,
  val subtaskId: Int,
) {
  val acceptedBlockReference = run {
    val displaced = BlockIncidentIdentity.acceptedReferenceFromDisplacement(artifacts)
    val continuation = DurableWorkflowArtifacts.fromMap(artifacts).goalContinuation()
      ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
    val stored = continuation?.let { goalContinuationOutcome(artifacts, issueKey, subtaskId, it.suppressPr) }
      ?.takeIf { it.status == GoalRunnerTerminalStatus.BLOCKED }
    val identity = BlockIncidentIdentity.fromArtifacts(artifacts)
      ?.takeIf { current ->
        record.workflowStatus == WorkflowStatus.BLOCKED && current.stepId == stored?.lastResumableStep &&
          record.steps.firstOrNull { step -> step.stepId == current.stepId }?.attemptCount == current.attempt
      }
    displaced ?: identity?.acceptedReference(record.workflowId)
  }
}

internal fun diagnoseStaleBlockedOutcome(
  context: GoalRunnerStaleBlockedOutcomeContext,
  wedges: MutableList<GoalRunnerWedgeFinding>,
  passed: MutableList<String>,
) {
  val identity =
    DurableWorkflowArtifacts.fromMap(context.artifacts).goalContinuation()
      ?.takeIf { it.issueKey == context.issueKey && it.subtaskId == context.subtaskId }
  if (identity == null) {
    passed += PASSED_CONTINUATION_OUTCOME
    return
  }
  val stored =
    goalContinuationOutcome(context.artifacts, context.issueKey, context.subtaskId, identity.suppressPr)
      ?.takeIf { it.status == GoalRunnerTerminalStatus.BLOCKED }
  if (stored == null) {
    passed += PASSED_CONTINUATION_OUTCOME
    return
  }
  val derived = derivedTerminalOutcomeFor(context.record, context.artifacts, identity) { null }
  if (
    nonCompleteStoredOutcomeIsCorroborated(
      stored.copy(workflowId = context.record.workflowId),
      derived,
      context.record,
    )
  ) {
    passed += PASSED_CONTINUATION_OUTCOME
  } else {
    wedges +=
      GoalRunnerWedgeFinding(
        wedgeClass = GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME,
        field = GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME.durableField,
        currentValue = stored.blockedReason,
        acceptedBlockReference = context.acceptedBlockReference,
      )
  }
}
