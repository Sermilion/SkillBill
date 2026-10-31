package skillbill.engine.featuretask.model.phase

import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeSharedReviewEvidenceReference

internal data class FeatureTaskRuntimeBriefingProjectionInputs(
  public val handoff: FeatureTaskRuntimePhaseHandoff,
  public val declarations: List<PhaseHandoffProjectionDeclaration>,
  public val workflowId: String?,
  public val sharedReviewEvidence: FeatureTaskRuntimeSharedReviewEvidenceReference?,
  public val addonContentBySlug: Map<String, String>,
)
