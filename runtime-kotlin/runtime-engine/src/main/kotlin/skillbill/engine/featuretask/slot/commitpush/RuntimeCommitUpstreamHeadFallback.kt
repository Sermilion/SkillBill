package skillbill.engine.featuretask.slot.commitpush

import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepHookContextKind
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseCommitLaunchHookContext
import skillbill.engine.featuretask.slot.writehistory.HistoryHeadReceipt

internal object RuntimeCommitUpstreamHeadFallback : PhaseStepHooks {
  override val contextKind = PhaseStepHookContextKind.COMMIT

  override fun reconcileBeforeLaunch(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
  ) {
    (
      context as? PhaseCommitLaunchHookContext
        ?: error("Commit recovery requires the accepted commit hook context.")
    ).recoverCommitUpstream(run, HistoryHeadReceipt::syntheticOutput)
  }
}
