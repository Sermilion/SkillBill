package skillbill.engine.featuretask.review.core

import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.persist.WorkflowRowAdvance
import skillbill.engine.featuretask.persist.stepUpdatesFrom
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.core.reviewGenerationFrom
import skillbill.engine.featuretask.runloop.state.REVIEW_INVALIDATION_AGENT_ID
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.goalrunner.subtaskreview.reviewRunIdOf
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.persistence.task.runtime.goal.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

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
  ): Boolean =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@transaction false
      val artifacts = record.artifacts
      val existingRecords = decodePhaseRecords(artifacts)
      val previous = existingRecords[producerPhaseId] ?: return@transaction true
      if (previous.status.workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
        return@transaction true
      }
      val invalidated =
        previous.copy(
          status = WorkflowStepStatus.RUNNING,
          finishedAt = null,
          outputArtifact = null,
          rejectedOutput = previous.outputArtifact ?: previous.rejectedOutput,
          loopId = loopId,
          edgeIteration = edgeIteration,
        )
      val updatedRecords = LinkedHashMap(existingRecords).apply { put(producerPhaseId, invalidated) }
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
