package skillbill.di.featuretask

import skillbill.engine.featuretask.slot.monitor.MonitorStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy

internal class PullRequestPhaseStrategies(
  val prDescription: PrDescriptionStrategy,
  val monitor: MonitorStrategy,
)
