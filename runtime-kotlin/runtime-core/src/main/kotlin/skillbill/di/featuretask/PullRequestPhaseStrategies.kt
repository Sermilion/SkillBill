package skillbill.di.featuretask

import skillbill.engine.featuretask.slot.monitor.MonitorStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorPublicationStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy

internal class PullRequestPhaseStrategies(
  val prDescription: PrDescriptionStrategy,
  val monitor: MonitorStrategy,
  val standaloneMonitor: StandaloneMonitorStrategy,
  val standalonePublication: StandaloneMonitorPublicationStrategy,
)
