package skillbill.workflow.taskruntime.model.handoff.task

import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactValidation
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeSharedReviewEvidenceReference

data class FeatureTaskRuntimeHandoffProjectionInputs(
  val consumerPhaseId: String,
  val declarations: List<PhaseHandoffProjectionDeclaration>,
  val resolvedUpstream: FeatureTaskRuntimeResolvedUpstreamOutputs,
  val runInvariants: FeatureTaskRuntimeRunInvariants,
  val resolvedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  val expectedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  val sharedReviewEvidence: FeatureTaskRuntimeSharedReviewEvidenceReference? = null,
  val repairLedger: FeatureTaskRuntimeRepairLedger? = null,
  val recordedFindingVerdicts: List<ReviewFindingVerdict> = emptyList(),
  val branchIdentity: String? = null,
  val baseBranch: String = "main",
  val addonContentBySlug: Map<String, String> = emptyMap(),
  val workflowId: String? = null,
  val validationDepth: ValidationDepth = ValidationDepth.DEFAULT,
  val unselectedStepIds: Set<String> = emptySet(),
  val planningProjectionValidator: FeatureTaskRuntimeWireArtifactValidation,
)

const val MAX_BOUNDED_POINTER_LENGTH: Int = 256
