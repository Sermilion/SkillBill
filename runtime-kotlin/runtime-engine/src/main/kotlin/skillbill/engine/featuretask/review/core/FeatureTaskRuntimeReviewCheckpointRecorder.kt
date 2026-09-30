package skillbill.engine.featuretask.review.core

import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.review.finding.FeatureTaskRuntimeFindingVerificationRecorder
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition

class FeatureTaskRuntimeReviewCheckpointRecorder(
  database: DatabaseSessionFactory,
  workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
) {
  private val reviewGeneration =
    FeatureTaskRuntimeReviewGenerationRecorder(
      database,
      workflowPersistence,
      runtimeOwnedPersistence,
    )
  private val findingVerification =
    FeatureTaskRuntimeFindingVerificationRecorder(
      database,
      workflowPersistence,
    )

  fun persistReviewGenerationInvalidation(
    workflowId: String,
    reviewStepId: String,
  ): Int? = reviewGeneration.persistReviewGenerationInvalidation(workflowId, reviewStepId)

  fun reconcileReviewGeneration(workflowId: String): Int = reviewGeneration.reconcileReviewGeneration(workflowId)

  fun invalidateQuarantinedProducerRecord(
    workflowId: String,
    producerPhaseId: String,
    loopId: String,
    edgeIteration: Int,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): Boolean =
    reviewGeneration.invalidateQuarantinedProducerRecord(
      workflowId,
      producerPhaseId,
      loopId,
      edgeIteration,
      admitted,
    )

  fun recordedFindingVerdicts(output: Map<String, Any?>): List<ReviewFindingVerdict> =
    reviewGeneration.recordedFindingVerdicts(output)

  fun fetchUnaddressedLedger(workflowId: String): List<UnaddressedFinding> =
    reviewGeneration.fetchUnaddressedLedger(workflowId)

  fun appendRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  ) = reviewGeneration.appendRejectedVerificationFindings(workflowId, passNumber, rejected)

  fun loadFindingVerificationCheckpoint(workflowId: String): List<FeatureTaskRuntimeFindingVerificationDisposition>? =
    findingVerification.loadFindingVerificationCheckpoint(workflowId)

  fun loadFindingVerificationBoundarySelection(
    workflowId: String,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? =
    findingVerification.loadFindingVerificationBoundarySelection(workflowId)

  fun persistFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean = findingVerification.persistFindingVerificationBoundarySelection(workflowId, selections)

  fun loadFindingVerificationDispositions(workflowId: String): List<FeatureTaskRuntimeFindingVerificationDisposition>? =
    findingVerification.loadFindingVerificationDispositions(workflowId)

  fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean = findingVerification.persistFindingVerificationCheckpoint(workflowId, dispositions)

  fun clearFindingVerificationCheckpoint(workflowId: String): Boolean =
    findingVerification.clearFindingVerificationCheckpoint(workflowId)
}
