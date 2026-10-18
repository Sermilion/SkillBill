package skillbill.di.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStrategyRegistration
import skillbill.engine.featuretask.slot.monitor.MonitorOpus55Strategy
import skillbill.engine.featuretask.slot.monitor.MonitorStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorOpus55Strategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorPublicationStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionOpus55Strategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.qa.QaStrategy

internal class PullRequestPhaseStrategies(
  val prDescription: PrDescriptionStrategy,
  val monitor: MonitorStrategy,
  val standaloneMonitor: StandaloneMonitorStrategy,
  val standalonePublication: StandaloneMonitorPublicationStrategy,
)

@Inject
internal class InjectedPhaseStrategies(
  private val pullRequest: PullRequestPhaseStrategies,
  private val qa: QaStrategy,
) {
  fun registrations(runner: () -> PhaseRunner): List<PhaseStrategyRegistration> =
    listOf(
      PhaseStrategyRegistration(pullRequest.prDescription, runner()),
      PhaseStrategyRegistration(PrDescriptionOpus55Strategy(pullRequest.prDescription), runner()),
      PhaseStrategyRegistration(pullRequest.monitor, runner()),
      PhaseStrategyRegistration(MonitorOpus55Strategy(pullRequest.monitor), runner()),
      PhaseStrategyRegistration(pullRequest.standaloneMonitor, runner()),
      PhaseStrategyRegistration(StandaloneMonitorOpus55Strategy(pullRequest.standaloneMonitor), runner()),
      PhaseStrategyRegistration(pullRequest.standalonePublication, runner()),
      PhaseStrategyRegistration(qa, runner()),
    )
}
