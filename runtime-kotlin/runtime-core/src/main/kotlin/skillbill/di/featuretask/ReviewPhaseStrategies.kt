package skillbill.di.featuretask

import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.standalonereview.DelegatedStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.standalonereview.InlineStandaloneReviewStrategy

internal class ReviewPhaseStrategies(
  val inline: InlineReviewStrategy,
  val inlineStandalone: InlineStandaloneReviewStrategy,
  val delegatedStandalone: DelegatedStandaloneReviewStrategy,
)
