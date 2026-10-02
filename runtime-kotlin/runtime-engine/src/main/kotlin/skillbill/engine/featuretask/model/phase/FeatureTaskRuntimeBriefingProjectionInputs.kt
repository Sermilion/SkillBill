package skillbill.engine.featuretask.model.phase

import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeSharedReviewEvidenceReference

data class FeatureTaskRuntimeBriefingProjectionInputs(
  val handoff: FeatureTaskRuntimePhaseHandoff,
  val declarations: List<PhaseHandoffProjectionDeclaration>,
  val workflowId: String?,
  val sharedReviewEvidence: FeatureTaskRuntimeSharedReviewEvidenceReference?,
  val addonContentBySlug: Map<String, String>,
)
