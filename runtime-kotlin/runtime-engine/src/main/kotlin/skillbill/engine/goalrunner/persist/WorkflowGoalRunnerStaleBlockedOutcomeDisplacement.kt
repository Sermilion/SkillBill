package skillbill.engine.goalrunner.persist

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.goalrunner.execution.support.toGoalContinuationWireStatus
import skillbill.engine.goalrunner.execution.support.workflowFamilyFor
import skillbill.goalrunner.derivedTerminalOutcomeFor
import skillbill.goalrunner.goalContinuationOutcome
import skillbill.goalrunner.model.GoalContinuation
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.nonCompleteStoredOutcomeIsCorroborated
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.persistence.goalContinuation
import java.time.Clock

internal class WorkflowGoalRunnerStaleBlockedOutcomeDisplacement(
  private val engine: WorkflowEngine,
  private val clock: Clock,
) {
  fun displaceIfPresent(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ) {
    val context = loadDisplacementContext(workflowStates, workflowId, issueKey, subtaskId) ?: return
    if (shouldRetainBlockedOutcome(context)) return
    persistDisplacement(workflowStates, context)
  }

  private fun loadDisplacementContext(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ): DisplacementContext? {
    val family = workflowFamilyFor(workflowStates, workflowId) ?: return null
    val record = workflowStates.get(family, workflowId) ?: return null
    val artifacts = record.artifacts
    val continuation =
      DurableWorkflowArtifacts.fromMap(artifacts).goalContinuation()
        ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
    val stored =
      continuation
        ?.let { goalContinuationOutcome(artifacts, issueKey, subtaskId, it.suppressPr) }
        ?.takeIf { it.status == GoalRunnerTerminalStatus.BLOCKED }
    if (continuation == null || stored == null) return null
    return DisplacementContext(family, record, artifacts, continuation, stored, workflowId, issueKey, subtaskId)
  }

  private fun shouldRetainBlockedOutcome(context: DisplacementContext): Boolean {
    val derived = derivedTerminalOutcomeFor(context.record, context.artifacts, context.continuation) { null }
    return nonCompleteStoredOutcomeIsCorroborated(
      context.stored.copy(workflowId = context.workflowId),
      derived,
      context.record,
    )
  }

  private fun persistDisplacement(
    workflowStates: WorkflowStateRepository,
    context: DisplacementContext,
  ) {
    val displacementFamily = DurableWorkflowArtifactFamily.GOAL_CONTINUATION_OUTCOME_DISPLACEMENT
    val evidenceAlreadyPresent = displacementFamily.value(context.artifacts) != null
    val derived = derivedTerminalOutcomeFor(context.record, context.artifacts, context.continuation) { null }
    val updated =
      engine.updateRecord(
        context.family.definition,
        context.record,
        WorkflowUpdateInput(
          workflowStatus = context.record.workflowStatus,
          currentStepId = context.record.currentStepId,
          stepUpdates = null,
          artifactsPatch =
            WorkflowArtifactPatch.from(
              buildMap {
                if (!evidenceAlreadyPresent) {
                  putAll(
                    mapOf(
                      displacementFamily.entry(
                        linkedMapOf(
                          SharedPayloadKeys.WORKFLOW_ID to context.workflowId,
                          SharedPayloadKeys.ISSUE_KEY to context.issueKey,
                          SharedPayloadKeys.SUBTASK_ID to context.subtaskId,
                          "displaced_status" to "blocked",
                          "original_blocked_reason" to context.stored.blockedReason,
                          "failed_corroboration" to
                            linkedMapOf(
                              "derived_status" to derived?.status?.toGoalContinuationWireStatus(),
                              "derived_blocked_reason" to derived?.blockedReason,
                              "stored_blocked_reason" to context.stored.blockedReason,
                            ),
                          "displaced_at" to clock.instant().toString(),
                        ),
                      ),
                    ),
                  )
                }
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME.putInto(this, null)
              },
            ),
          sessionId = context.record.sessionId.orEmpty(),
        ),
      )
    workflowStates.save(context.family, updated)
  }

  private data class DisplacementContext(
    val family: WorkflowFamily,
    val record: WorkflowStateSnapshot,
    val artifacts: Map<String, Any?>,
    val continuation: GoalContinuation,
    val stored: GoalRunnerStoredOutcome,
    val workflowId: String,
    val issueKey: String,
    val subtaskId: Int,
  )
}
