package skillbill.engine.featuretask.slot.commitpush

import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseCommitLaunchHookContext

internal object RuntimeCommitUpstreamHeadFallback : PhaseStepHooks {
  override fun reconcileBeforeLaunch(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
  ) {
    (
      context as? PhaseCommitLaunchHookContext
        ?: error("Commit recovery requires the accepted commit hook context.")
    ).recoverCommitUpstream(run)
  }
}
