package skillbill.engine.diagnostics.block

import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelatedField
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceReference
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFieldAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

/**
 * Correlation identities that a feature-task producer can actually read when it accepts a phase block. Values the
 * runtime does not hold stay null and carry the reason they are missing instead of a placeholder.
 */
internal class BlockIncidentFeatureTaskCorrelation private constructor(
  private val workflowId: String?,
  private val phaseId: String?,
  private val parentWorkflowId: String?,
  private val invocationId: String?,
  private val generation: Long?,
  private val unavailableFields: Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability>,
) {
  /** Builds the observation for an accepted phase block, which returns a failure rather than throwing or exiting. */
  fun acceptedPhaseBlock(
    attempt: Int,
    blockedReason: String?,
    failureDisposition: String?,
    failureCode: String?,
    evidenceReferences: List<BlockIncidentEvidenceReference> = emptyList(),
  ): BlockIncidentObservation {
    val recordedReason = blockedReason?.takeIf(String::isNotBlank)
    val reason = recordedReason
      ?: "Phase ${phaseId ?: "unknown"} attempt $attempt was accepted as blocked without a recorded reason " +
      "(failure_disposition=${failureDisposition ?: "none"})."
    val failureFacts = buildMap {
      put(BlockIncidentCorrelatedField.THROWABLE_TYPE, BlockIncidentFieldAvailability.NOT_APPLICABLE)
      put(BlockIncidentCorrelatedField.EXIT_CODE, BlockIncidentFieldAvailability.NOT_APPLICABLE)
      if (failureCode == null) {
        put(BlockIncidentCorrelatedField.FAILURE_CODE, BlockIncidentFieldAvailability.NOT_APPLICABLE)
      }
      if (recordedReason == null) put(BlockIncidentCorrelatedField.REASON, BlockIncidentFieldAvailability.NOT_CAPTURED)
    }
    return BlockIncidentObservation(
      workflowId = workflowId,
      parentWorkflowId = parentWorkflowId,
      phaseId = phaseId,
      invocationId = invocationId,
      attempt = attempt,
      reason = reason,
      generation = generation,
      origin = BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK,
      failureCode = failureCode,
      failureDisposition = failureDisposition,
      exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
      evidenceReferences = evidenceReferences,
      unavailableFields = unavailableFields + failureFacts,
    )
  }

  companion object {
    private const val MAX_IDENTITY_CHARS = 512
    private val PULL_REQUEST_PHASES = setOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX,
    )
    private val PULL_REQUEST_FIELDS = listOf(
      BlockIncidentCorrelatedField.MONITOR_SELECTOR, BlockIncidentCorrelatedField.PULL_REQUEST_ID,
      BlockIncidentCorrelatedField.PULL_REQUEST_URL, BlockIncidentCorrelatedField.HEAD_REPOSITORY,
      BlockIncidentCorrelatedField.HEAD_BRANCH, BlockIncidentCorrelatedField.WORKTREE_PATH,
    )

    /**
     * Reads the parent workflow from the goal-continuation artifact and the invocation and generation from the
     * active worker lease. Phase-run and execution identities are not modeled for feature-task phases.
     */
    fun resolve(
      states: WorkflowStateRepository,
      workflowId: String,
      phaseId: String,
      admitted: AdmittedFeatureTaskRuntimeExecution?,
      hasRepairEvidence: Boolean = false,
    ): BlockIncidentFeatureTaskCorrelation {
      val unavailable = mutableMapOf(
        BlockIncidentCorrelatedField.PHASE_RUN_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE,
        BlockIncidentCorrelatedField.EXECUTION_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE,
      )
      val goalArtifact = states.get(WorkflowFamily.TASK_RUNTIME, workflowId)?.artifacts?.goalContinuationArtifact()
      if (goalArtifact == null) {
        unavailable[BlockIncidentCorrelatedField.PARENT_WORKFLOW_ID] = BlockIncidentFieldAvailability.NOT_APPLICABLE
      }
      val ownership = (admitted?.workerOwnership ?: states.getFeatureTaskRuntimeWorkerOwnership(workflowId))
        ?.takeIf { it.workflowId == workflowId && it.leaseState == FeatureTaskRuntimeWorkerLeaseState.ACTIVE }
      val pullRequestAvailability =
        if (phaseId in PULL_REQUEST_PHASES) {
          BlockIncidentFieldAvailability.NOT_CAPTURED
        } else {
          BlockIncidentFieldAvailability.NOT_APPLICABLE
        }
      PULL_REQUEST_FIELDS.forEach { field -> unavailable[field] = pullRequestAvailability }
      // Repair evidence records digests and a source location, not the repair turn or output generation.
      val repairAvailability =
        if (hasRepairEvidence) {
          BlockIncidentFieldAvailability.NOT_CAPTURED
        } else {
          BlockIncidentFieldAvailability.NOT_APPLICABLE
        }
      unavailable[BlockIncidentCorrelatedField.REPAIR_TURN] = repairAvailability
      unavailable[BlockIncidentCorrelatedField.OUTPUT_GENERATION] = repairAvailability
      return BlockIncidentFeatureTaskCorrelation(
        workflowId = workflowId.boundedIdentity(),
        phaseId = phaseId.boundedIdentity(),
        parentWorkflowId = goalArtifact?.parentWorkflowId?.boundedIdentity(),
        invocationId = ownership?.ownerToken?.boundedIdentity(),
        generation = ownership?.generation?.takeIf { it > 0 },
        unavailableFields = unavailable,
      )
    }

    /** An identity the contract cannot hold is recorded as not captured rather than shortened. */
    private fun String.boundedIdentity(): String? = takeIf { it.isNotBlank() && it.length <= MAX_IDENTITY_CHARS }
  }
}
