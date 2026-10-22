package skillbill.engine.goalrunner.persist

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.diagnostics.block.BlockIncidentDisplacementKeys
import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.engine.diagnostics.block.boundTo
import skillbill.engine.goalrunner.execution.support.toGoalContinuationWireStatus
import skillbill.engine.workflow.support.workflowFamilyFor
import skillbill.goalrunner.derivedTerminalOutcomeFor
import skillbill.goalrunner.goalContinuationOutcome
import skillbill.goalrunner.model.GoalContinuation
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
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
    return DisplacementContext(
      family,
      record,
      artifacts,
      continuation,
      stored,
      workflowId,
      issueKey,
      subtaskId,
      stored.acceptedBlockReference?.boundTo(workflowId, stored.lastResumableStep)
        ?: BlockIncidentIdentity.acceptedFor(artifacts, record.steps, stored.lastResumableStep, workflowId),
    )
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
    val existingEvidence = displacementFamily.value(context.artifacts) as? Map<*, *>
    val currentReference = context.acceptedBlockReference?.toDisplacementWire()
    val additionalReferences =
      (existingEvidence?.get(BlockIncidentDisplacementKeys.ADDITIONAL_ACCEPTED_BLOCK_REFERENCES) as? List<*>)
        .orEmpty()
        .filterIsInstance<Map<*, *>>()
    val displacementEvidence =
      when {
        existingEvidence == null ->
          linkedMapOf(
            SharedPayloadKeys.WORKFLOW_ID to context.workflowId,
            SharedPayloadKeys.ISSUE_KEY to context.issueKey,
            SharedPayloadKeys.SUBTASK_ID to context.subtaskId,
            "displaced_status" to "blocked",
            "original_blocked_reason" to context.stored.blockedReason,
            "failed_corroboration" to
              linkedMapOf(
                "derived_status" to
                  derivedTerminalOutcomeFor(context.record, context.artifacts, context.continuation) { null }
                    ?.status?.toGoalContinuationWireStatus(),
                "derived_blocked_reason" to
                  derivedTerminalOutcomeFor(context.record, context.artifacts, context.continuation) { null }
                    ?.blockedReason,
                "stored_blocked_reason" to context.stored.blockedReason,
              ),
            "displaced_at" to clock.instant().toString(),
            BlockIncidentDisplacementKeys.ACCEPTED_BLOCK_REFERENCE to currentReference,
          )
        currentReference == null -> existingEvidence.entries.associate { it.key.toString() to it.value }
        else ->
          existingEvidence.entries.associate { it.key.toString() to it.value } +
            (
              BlockIncidentDisplacementKeys.ADDITIONAL_ACCEPTED_BLOCK_REFERENCES to
                (additionalReferences + currentReference).distinctBy(::displacementIdentityKey)
            )
      }
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
                putAll(mapOf(displacementFamily.entry(displacementEvidence)))
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
    val acceptedBlockReference: GoalRunnerAcceptedBlockReference?,
  )

  private fun GoalRunnerAcceptedBlockReference.toDisplacementWire(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.WORKFLOW_ID to sourceWorkflowId,
      SharedPayloadKeys.STEP_ID to blockedStepId,
      SharedPayloadKeys.ATTEMPT to attempt,
      BlockIncidentDisplacementKeys.EXECUTION_GENERATION to executionGeneration,
      BlockIncidentDisplacementKeys.ALIAS_KIND to aliasKind,
      BlockIncidentDisplacementKeys.ALIAS_VALUE to aliasValue,
      BlockIncidentDisplacementKeys.INCIDENT_TOKEN to incidentToken,
      BlockIncidentDisplacementKeys.CHILD_INCIDENT_TOKEN to childIncidentToken,
      BlockIncidentDisplacementKeys.SOURCE_ALIAS_KIND to sourceAliasKind,
      BlockIncidentDisplacementKeys.SOURCE_ALIAS_VALUE to sourceAliasValue,
    )

  private fun displacementIdentityKey(reference: Map<*, *>): List<Any?> =
    listOf(
      reference[SharedPayloadKeys.WORKFLOW_ID],
      reference[SharedPayloadKeys.STEP_ID],
      reference[SharedPayloadKeys.ATTEMPT],
      reference[BlockIncidentDisplacementKeys.EXECUTION_GENERATION],
      reference[BlockIncidentDisplacementKeys.ALIAS_KIND],
      reference[BlockIncidentDisplacementKeys.ALIAS_VALUE],
      reference[BlockIncidentDisplacementKeys.INCIDENT_TOKEN],
    )
}
