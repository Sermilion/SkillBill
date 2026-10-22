package skillbill.engine.diagnostics.block

import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelatedField
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceReference
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFieldAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal class BlockIncidentFeatureTaskCorrelation private constructor(
  private val workflowId: String?,
  private val phaseId: String?,
  private val parentWorkflowId: String?,
  private val invocationId: String?,
  private val generation: Long?,
  private val unavailableFields: Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability>,
) {
  fun acceptedPhaseBlock(
    attempt: Int,
    blockedReason: String?,
    failureDisposition: String?,
    failureCode: String?,
    evidenceReferences: List<BlockIncidentEvidenceReference> = emptyList(),
  ): BlockIncidentObservation {
    val recordedReason = blockedReason?.takeIf(String::isNotBlank)
    val reason =
      recordedReason
        ?: "Phase ${phaseId ?: "unknown"} attempt $attempt was accepted as blocked without a recorded reason " +
        "(failure_disposition=${failureDisposition ?: "none"})."
    val failureFacts =
      buildMap {
        put(BlockIncidentCorrelatedField.THROWABLE_TYPE, BlockIncidentFieldAvailability.NOT_APPLICABLE)
        put(BlockIncidentCorrelatedField.EXIT_CODE, BlockIncidentFieldAvailability.NOT_APPLICABLE)
        put(
          BlockIncidentCorrelatedField.FAILURE_CODE,
          if (failureCode == null) {
            BlockIncidentFieldAvailability.NOT_CAPTURED
          } else {
            BlockIncidentFieldAvailability.AVAILABLE
          },
        )
        if (recordedReason == null) {
          put(BlockIncidentCorrelatedField.REASON, BlockIncidentFieldAvailability.NOT_CAPTURED)
        }
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
    fun persistAcceptedIdentity(
      states: WorkflowStateRepository,
      observation: BlockIncidentObservation,
    ) {
      val workflowId = observation.workflowId
      val phaseId = observation.phaseId
      val attempt = observation.attempt
      if (workflowId == null || phaseId == null || attempt == null) return
      val record = states.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return
      val acceptedAlias = BlockIncidentAliases.fallback(observation)
      val existing = BlockIncidentIdentity.fromArtifacts(record.artifacts)
      if (existing?.matchesAcceptedPhase(phaseId, attempt, observation.generation, acceptedAlias) == true) return
      val alias = BlockIncidentAliases.fallback(observation)
      val identity =
        BlockIncidentIdentity(
          stepId = phaseId,
          attempt = attempt,
          generation = observation.generation,
          childWorkflowId = null,
          childIncidentToken = null,
          incidentToken = BlockIncidentAliases.token(alias),
          alias = alias,
          failureFacts =
            BlockIncidentFailureFacts(
              observation.failureCode,
              observation.failureDisposition?.let { disposition ->
                FeatureTaskRuntimeFailureDisposition.entries.firstOrNull { it.wireValue == disposition }
                  ?: throw invalidWorkflowStateSchemaError(
                    "Accepted block identity has an unsupported failure disposition.",
                  )
              },
            ),
        )
      states.save(
        WorkflowFamily.TASK_RUNTIME,
        record.copy(artifacts = DurableWorkflowArtifacts.fromMap(record.artifacts + identity.artifactEntry())),
      )
    }

    fun acceptedPhaseIncidentToken(
      states: WorkflowStateRepository,
      workflowId: String,
      phaseId: String,
    ): String? {
      val record = states.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return null
      val identity =
        BlockIncidentIdentity.fromArtifacts(record.artifacts)
          ?.takeIf { it.stepId == phaseId && it.childWorkflowId == null && it.attempt != null }
          ?: return null
      val phase = decodePhaseRecords(record.artifacts)[phaseId]
      return identity.incidentToken.takeIf {
        phase != null && phase.status == WorkflowStepStatus.BLOCKED && phase.attemptCount == identity.attempt
      }
    }

    private fun BlockIncidentIdentity.matchesAcceptedPhase(
      phaseId: String,
      acceptedAttempt: Int,
      acceptedGeneration: Long?,
      canonical: BlockIncidentAlias,
    ): Boolean =
      stepId == phaseId && attempt == acceptedAttempt && generation == acceptedGeneration && alias == canonical

    private const val MAX_IDENTITY_CHARS = 512
    internal val PULL_REQUEST_PHASES =
      setOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX,
      )
    internal val PULL_REQUEST_FIELDS =
      listOf(
        BlockIncidentCorrelatedField.MONITOR_SELECTOR,
        BlockIncidentCorrelatedField.PULL_REQUEST_ID,
        BlockIncidentCorrelatedField.PULL_REQUEST_URL,
        BlockIncidentCorrelatedField.HEAD_REPOSITORY,
        BlockIncidentCorrelatedField.HEAD_BRANCH,
        BlockIncidentCorrelatedField.WORKTREE_PATH,
      )

    fun resolve(
      states: WorkflowStateRepository,
      workflowId: String,
      phaseId: String,
      admitted: AdmittedFeatureTaskRuntimeExecution?,
      hasRepairEvidence: Boolean = false,
    ): BlockIncidentFeatureTaskCorrelation {
      val unavailable =
        mutableMapOf(
          BlockIncidentCorrelatedField.PHASE_RUN_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE,
          BlockIncidentCorrelatedField.EXECUTION_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE,
        )
      val goalArtifact = states.get(WorkflowFamily.TASK_RUNTIME, workflowId)?.artifacts?.goalContinuationArtifact()
      if (goalArtifact == null) {
        unavailable[BlockIncidentCorrelatedField.PARENT_WORKFLOW_ID] = BlockIncidentFieldAvailability.NOT_APPLICABLE
      }
      val ownership =
        admitted?.workerOwnership
          ?.takeIf { it.workflowId == workflowId && it.leaseState == FeatureTaskRuntimeWorkerLeaseState.ACTIVE }
          ?: states.getFeatureTaskRuntimeWorkerOwnership(workflowId)
            ?.takeIf { it.workflowId == workflowId && it.leaseState == FeatureTaskRuntimeWorkerLeaseState.ACTIVE }
      val pullRequestAvailability =
        if (phaseId in PULL_REQUEST_PHASES) {
          BlockIncidentFieldAvailability.NOT_CAPTURED
        } else {
          BlockIncidentFieldAvailability.NOT_APPLICABLE
        }
      PULL_REQUEST_FIELDS.forEach { field -> unavailable[field] = pullRequestAvailability }
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

    private fun String.boundedIdentity(): String? = takeIf { it.isNotBlank() && it.length <= MAX_IDENTITY_CHARS }
  }
}
