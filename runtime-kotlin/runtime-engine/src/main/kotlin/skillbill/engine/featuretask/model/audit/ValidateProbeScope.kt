package skillbill.engine.featuretask.model.audit

import skillbill.review.spec.model.InteractionScenario
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionSameFilePair

public data class ValidateProbeScope(
  public val repairedScenarios: List<InteractionScenario>,
  public val requiredCriteria: List<ValidateProbeCriterion>,
  internal val repairedPairs: List<AcceptanceCriterionSameFilePair> = emptyList(),
)

public data class ValidateProbeCriterion(
  public val criterionRef: String,
  public val text: String,
)
