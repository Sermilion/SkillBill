package skillbill.engine.featuretask.review.core

import skillbill.engine.featuretask.lifecycle.execution.requireCurrent
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.persist.WorkflowRowAdvance
import skillbill.engine.featuretask.persist.stepUpdatesFrom
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.core.reviewGenerationFrom
import skillbill.engine.featuretask.runloop.state.REVIEW_INVALIDATION_AGENT_ID
import skillbill.error.featuretask.FeatureTaskRuntimeRegenerationRefusal
import skillbill.error.featuretask.UnsafeFeatureTaskRuntimeRegenerationError
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.goalrunner.subtaskreview.reviewRunIdOf
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodeCheckpointIdentitiesFromArtifact
import skillbill.workflow.taskruntime.model.persistence.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot

class FeatureTaskRuntimeReviewGenerationRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
) {
  fun persistReviewGenerationInvalidation(
    workflowId: String,
    reviewStepId: String,
  ): Int? =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@transaction null
      val artifacts = record.artifacts
      val storedGeneration = reviewGenerationFrom(artifacts)
      val existingRecords = decodePhaseRecords(artifacts)
      val previousReview =
        existingRecords[reviewStepId]
          ?: return@transaction storedGeneration
      val tombstone =
        FeatureTaskRuntimePhaseRecord(
          phaseId = reviewStepId,
          status = WorkflowStepStatus.RUNNING,
          attemptCount = previousReview.attemptCount,
          startedAt = previousReview.startedAt,
          firstStartedAt = previousReview.firstStartedAt,
          resolvedAgentId = REVIEW_INVALIDATION_AGENT_ID,
        )
      val updatedRecords =
        LinkedHashMap(existingRecords).apply {
          put(reviewStepId, tombstone)
        }
      val nextGeneration = storedGeneration + 1
      val patch =
        linkedMapOf<String, Any?>(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
            updatedRecords.mapValues { (_, value) -> value.asWorkflowArtifactEntry() },
          ),
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_REVIEW_GENERATION.entry(nextGeneration),
        )
      GoalSubtaskReviewArtifactDecoder.decode(artifacts)?.state?.let { state ->
        DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.putInto(
          patch,
          GoalSubtaskReviewState.initial(
            reviewBaseSha = state.reviewBaseSha,
            baselineUntrackedPaths = state.baselineUntrackedPaths,
            codeReviewMode = state.codeReviewMode,
          ).toPersistenceWire(),
        )
        DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_RESULTS.putInto(patch, emptyMap<String, String>())
        unitOfWork.unaddressedFindings.clearWorkflowLedger(workflowId)
      }
      workflowPersistence.persistArtifactsPatch(
        unitOfWork.workflowStates,
        record,
        patch,
        WorkflowRowAdvance(
          currentStepId = record.currentStepId,
          workflowStatus = record.workflowStatus.wireValue,
          stepUpdates = stepUpdatesFrom(updatedRecords),
        ),
      )
      nextGeneration
    }

  fun reconcileReviewGeneration(workflowId: String): Int =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@transaction 0
      val artifacts = record.artifacts
      val storedGeneration = reviewGenerationFrom(artifacts)
      val tombstoned =
        decodePhaseRecords(artifacts).values.any { it.resolvedAgentId == REVIEW_INVALIDATION_AGENT_ID }
      if (!tombstoned || storedGeneration > 0) return@transaction storedGeneration
      workflowPersistence.persistArtifactsPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_REVIEW_GENERATION.entry(1)),
      )
      1
    }

  fun invalidateQuarantinedProducerRecord(
    workflowId: String,
    producerPhaseId: String,
    loopId: String,
    edgeIteration: Int,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): Boolean =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@transaction false
      if (record.workflowStatus in WorkflowStatus.terminalStatuses) {
        throw UnsafeFeatureTaskRuntimeRegenerationError(FeatureTaskRuntimeRegenerationRefusal.TERMINAL_WORKFLOW)
      }
      if (producerPhaseId in PhaseSlot.QUALITY_GATE.steps && admitted == null) {
        throw UnsafeFeatureTaskRuntimeRegenerationError(FeatureTaskRuntimeRegenerationRefusal.UNPROVEN_GATE_SEMANTICS)
      }
      admitted?.requireCurrent(unitOfWork.workflowStates, workflowId)
      val artifacts = record.artifacts
      val existingRecords = decodePhaseRecords(artifacts)
      val irreversibleSteps = PhaseSlot.COMMIT_PUSH.steps + PhaseSlot.PULL_REQUEST.steps
      val checkpointIdentities =
        decodeCheckpointIdentitiesFromArtifact(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES.value(artifacts),
        )
      if (
        existingRecords.keys.any { it in irreversibleSteps } ||
        decodePhaseLedger(artifacts).any { it.phaseId in irreversibleSteps } ||
        checkpointIdentities.any { it.phaseId in irreversibleSteps }
      ) {
        throw UnsafeFeatureTaskRuntimeRegenerationError(
          FeatureTaskRuntimeRegenerationRefusal.IRREVERSIBLE_WORK_RECORDED,
        )
      }
      val previous =
        existingRecords[producerPhaseId]
          ?: throw UnsafeFeatureTaskRuntimeRegenerationError(
            FeatureTaskRuntimeRegenerationRefusal.MISSING_PRODUCER_EVIDENCE,
          )
      if (producerPhaseId in PhaseSlot.QUALITY_GATE.steps) {
        requireAdmittedGateRegenerationBoundary(record, existingRecords, producerPhaseId, requireNotNull(admitted))
        val evidence =
          unitOfWork.rejectedOutputDiagnostics.readProducerOutput(
            workflowId,
            producerPhaseId,
            previous.attemptCount,
            previous.resolvedAgentId,
          )
        if (evidence?.payload == null) {
          throw UnsafeFeatureTaskRuntimeRegenerationError(
            FeatureTaskRuntimeRegenerationRefusal.MISSING_PRODUCER_EVIDENCE,
          )
        }
      }
      if (previous.status.workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
        return@transaction true
      }
      val updatedRecords = invalidatedProducerRecords(existingRecords, previous, loopId, edgeIteration)
      workflowPersistence.persistArtifactsPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
            updatedRecords.mapValues { (_, value) -> value.asWorkflowArtifactEntry() },
          ),
        ),
        WorkflowRowAdvance(
          currentStepId = record.currentStepId,
          workflowStatus = record.workflowStatus.wireValue,
          stepUpdates = stepUpdatesFrom(updatedRecords),
        ),
      )
      true
    }

  private fun invalidatedProducerRecords(
    existing: Map<String, FeatureTaskRuntimePhaseRecord>,
    previous: FeatureTaskRuntimePhaseRecord,
    loopId: String,
    edgeIteration: Int,
  ): Map<String, FeatureTaskRuntimePhaseRecord> {
    val invalidated =
      previous.copy(
        status = WorkflowStepStatus.RUNNING,
        finishedAt = null,
        outputArtifact = null,
        loopId = loopId,
        edgeIteration = edgeIteration,
      )
    return LinkedHashMap(existing).apply { put(previous.phaseId, invalidated) }
  }

  fun recordedFindingVerdicts(output: Map<String, Any?>): List<ReviewFindingVerdict> {
    val reviewRunId = GoalSubtaskReviewSummaryReducer.reviewRunIdOf(output) ?: return emptyList()
    return runtimeOwnedPersistence.requiredRead(
      seam = "FeatureTaskRuntimePhaseRecorder.recordedFindingVerdicts",
      expected = "runtime-owned finding verdicts",
    ) { unitOfWork ->
      unitOfWork.reviews.fetchFindingVerdicts(reviewRunId)
    }
  }

  fun fetchUnaddressedLedger(workflowId: String): List<UnaddressedFinding> =
    database.transaction { unitOfWork ->
      unitOfWork.unaddressedFindings.fetchWorkflowLedger(workflowId)
    }

  fun appendRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  ) {
    if (rejected.isEmpty()) return
    database.transaction { unitOfWork ->
      val existing = unitOfWork.unaddressedFindings.fetchWorkflowLedger(workflowId)
      val rejectedById =
        rejected.mapNotNull { finding ->
          finding.findingId?.let { id -> id to finding }
        }.toMap()
      val mergedExisting =
        existing.map { finding ->
          val rejection = finding.findingId?.let { rejectedById[it] } ?: return@map finding
          finding.copy(
            verificationDisposition = rejection.verificationDisposition,
            verificationReason = rejection.verificationReason,
          )
        }
      val existingIds = existing.mapNotNull { it.findingId }.toSet()
      val appended = rejected.filter { it.findingId !in existingIds }
      unitOfWork.unaddressedFindings.replaceLedgerForPass(
        workflowId,
        passNumber,
        mergedExisting + appended,
      )
    }
  }
}
