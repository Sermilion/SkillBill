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
import java.time.Clock

class FeatureTaskRuntimeReviewCheckpointRecorder(
  database: DatabaseSessionFactory,
  workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
  clock: Clock,
) : FeatureTaskRuntimeReviewGenerationPort, FeatureTaskRuntimeFindingVerificationPort {
  private val reviewGeneration =
    FeatureTaskRuntimeReviewGenerationRecorder(
      database,
      workflowPersistence,
      runtimeOwnedPersistence,
      clock,
    )
  private val findingVerification =
    FeatureTaskRuntimeFindingVerificationRecorder(
      database,
      workflowPersistence,
      clock,
    )

  override fun persistOwnedReviewGenerationInvalidation(
    workflowId: String,
    reviewStepId: String,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Int? = reviewGeneration.persistOwnedReviewGenerationInvalidation(workflowId, reviewStepId, admitted)

  override fun persistReviewGenerationInvalidation(
    workflowId: String,
    reviewStepId: String,
  ): Int? = reviewGeneration.persistReviewGenerationInvalidation(workflowId, reviewStepId)

  override fun reconcileReviewGeneration(workflowId: String): Int =
    reviewGeneration.reconcileReviewGeneration(workflowId)

  override fun invalidateQuarantinedProducerRecord(
    workflowId: String,
    producerPhaseId: String,
    loopId: String,
    edgeIteration: Int,
    admitted: AdmittedFeatureTaskRuntimeExecution?,
  ): Boolean =
    reviewGeneration.invalidateQuarantinedProducerRecord(
      workflowId,
      producerPhaseId,
      loopId,
      edgeIteration,
      admitted,
    )

  override fun recordedFindingVerdicts(output: Map<String, Any?>): List<ReviewFindingVerdict> =
    reviewGeneration.recordedFindingVerdicts(output)

  override fun fetchUnaddressedLedger(workflowId: String): List<UnaddressedFinding> =
    reviewGeneration.fetchUnaddressedLedger(workflowId)

  override fun appendRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  ) = reviewGeneration.appendRejectedVerificationFindings(workflowId, passNumber, rejected)

  override fun appendOwnedRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ) = reviewGeneration.appendRejectedVerificationFindings(workflowId, passNumber, rejected, admitted)

  override fun loadFindingVerificationCheckpoint(
    workflowId: String,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>? =
    findingVerification.loadFindingVerificationCheckpoint(workflowId)

  override fun loadFindingVerificationBoundarySelection(
    workflowId: String,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? =
    findingVerification.loadFindingVerificationBoundarySelection(workflowId)

  override fun persistOwnedFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean = findingVerification.persistOwnedFindingVerificationBoundarySelection(workflowId, selections, admitted)

  override fun persistFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean = findingVerification.persistFindingVerificationBoundarySelection(workflowId, selections)

  override fun loadFindingVerificationDispositions(
    workflowId: String,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>? =
    findingVerification.loadFindingVerificationDispositions(workflowId)

  override fun persistOwnedFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean = findingVerification.persistOwnedFindingVerificationCheckpoint(workflowId, dispositions, admitted)

  override fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean = findingVerification.persistFindingVerificationCheckpoint(workflowId, dispositions)

  override fun clearFindingVerificationCheckpoint(workflowId: String): Boolean =
    findingVerification.clearFindingVerificationCheckpoint(workflowId)
}
