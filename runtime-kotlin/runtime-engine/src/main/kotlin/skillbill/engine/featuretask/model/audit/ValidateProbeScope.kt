package skillbill.engine.featuretask.model.audit

import skillbill.review.spec.model.InteractionScenario

data class ValidateProbeScope(
  val repairedScenarios: List<InteractionScenario>,
  val requiredCriteria: List<ValidateProbeCriterion>,
)

data class ValidateProbeCriterion(
  val criterionRef: String,
  val text: String,
)
