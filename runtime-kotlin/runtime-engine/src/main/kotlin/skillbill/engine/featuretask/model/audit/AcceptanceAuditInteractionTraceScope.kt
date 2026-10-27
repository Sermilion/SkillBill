package skillbill.engine.featuretask.model.audit

import skillbill.review.spec.model.InteractionScenario
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionSameFilePair

data class AcceptanceAuditInteractionTraceScope(
  val pendingRefs: List<String>,
  val pendingScenarios: List<InteractionScenario>,
  val acceptedCriteria: List<AcceptanceAuditAcceptedCriterion>,
  val sameFilePairs: List<AcceptanceCriterionSameFilePair>,
  val recheckRefs: List<String>,
)

data class AcceptanceAuditAcceptedCriterion(
  val criterionRef: String,
  val closureEvidenceRef: String,
)
