package skillbill.engine.featuretask.model.audit

import skillbill.review.spec.model.InteractionScenario
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionSameFilePair

public data class AcceptanceAuditInteractionTraceScope(
  internal val pendingRefs: List<String>,
  internal val pendingScenarios: List<InteractionScenario>,
  internal val acceptedCriteria: List<AcceptanceAuditAcceptedCriterion>,
  internal val sameFilePairs: List<AcceptanceCriterionSameFilePair>,
  public val recheckRefs: List<String>,
)

public data class AcceptanceAuditAcceptedCriterion(
  public val criterionRef: String,
  public val closureEvidenceRef: String,
)
