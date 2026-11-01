package skillbill.engine.featuretask.model.audit

import skillbill.review.spec.model.InteractionScenario

public data class ValidateProbeScope(
  public val repairedScenarios: List<InteractionScenario>,
  public val requiredCriteria: List<ValidateProbeCriterion>,
)

public data class ValidateProbeCriterion(
  public val criterionRef: String,
  public val text: String,
)
