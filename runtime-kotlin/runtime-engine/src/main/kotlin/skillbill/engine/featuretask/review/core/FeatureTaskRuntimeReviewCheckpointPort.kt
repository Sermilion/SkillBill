package skillbill.engine.featuretask.review.core

import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition

internal interface FeatureTaskRuntimeReviewGenerationPort {
  fun persistOwnedReviewGenerationInvalidation(
    workflowId: String,
    reviewStepId: String,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Int?

  fun persistReviewGenerationInvalidation(
    workflowId: String,
    reviewStepId: String,
  ): Int?

  fun reconcileReviewGeneration(workflowId: String): Int

  fun invalidateQuarantinedProducerRecord(
    workflowId: String,
    producerPhaseId: String,
    loopId: String,
    edgeIteration: Int,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): Boolean

  fun recordedFindingVerdicts(output: Map<String, Any?>): List<ReviewFindingVerdict>

  fun fetchUnaddressedLedger(workflowId: String): List<UnaddressedFinding>
}

internal interface FeatureTaskRuntimeFindingVerificationPort {
  fun appendRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  )

  fun appendOwnedRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  )

  fun loadFindingVerificationCheckpoint(workflowId: String): List<FeatureTaskRuntimeFindingVerificationDisposition>?

  fun loadFindingVerificationBoundarySelection(
    workflowId: String,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>?

  fun persistOwnedFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean

  fun persistFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean

  fun loadFindingVerificationDispositions(workflowId: String): List<FeatureTaskRuntimeFindingVerificationDisposition>?

  fun persistOwnedFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean

  fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean

  fun clearFindingVerificationCheckpoint(workflowId: String): Boolean
}
