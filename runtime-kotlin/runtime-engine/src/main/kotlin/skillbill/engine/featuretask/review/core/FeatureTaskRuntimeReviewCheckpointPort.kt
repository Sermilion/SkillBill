package skillbill.engine.featuretask.review.core

import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition

public interface FeatureTaskRuntimeReviewGenerationPort {
  public fun persistOwnedReviewGenerationInvalidation(
    workflowId: String,
    reviewStepId: String,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Int?

  public fun persistReviewGenerationInvalidation(
    workflowId: String,
    reviewStepId: String,
  ): Int?

  public fun reconcileReviewGeneration(workflowId: String): Int

  public fun invalidateQuarantinedProducerRecord(
    workflowId: String,
    producerPhaseId: String,
    loopId: String,
    edgeIteration: Int,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): Boolean

  public fun recordedFindingVerdicts(output: Map<String, Any?>): List<ReviewFindingVerdict>

  public fun fetchUnaddressedLedger(workflowId: String): List<UnaddressedFinding>
}

public interface FeatureTaskRuntimeFindingVerificationPort {
  public fun appendRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  ): Unit

  public fun appendOwnedRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Unit

  public fun loadFindingVerificationCheckpoint(
    workflowId: String,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>?

  public fun loadFindingVerificationBoundarySelection(
    workflowId: String,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>?

  public fun persistOwnedFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean

  public fun persistFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean

  public fun loadFindingVerificationDispositions(
    workflowId: String,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>?

  public fun persistOwnedFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean

  public fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean

  public fun clearFindingVerificationCheckpoint(workflowId: String): Boolean
}
