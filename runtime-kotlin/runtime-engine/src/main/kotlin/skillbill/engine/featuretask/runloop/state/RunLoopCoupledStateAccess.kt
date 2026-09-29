package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.slot.state.PhaseRunState

internal fun PhaseRunState.runLoopCoupledProgress(): FeatureTaskRuntimeRunState = progress as FeatureTaskRuntimeRunState

internal fun PhaseRunState.runLoopCoupledSession(): FeatureTaskRuntimeRunLoopSession =
  session as FeatureTaskRuntimeRunLoopSession

internal fun coupledRunTransitionOwner(
  progress: FeatureTaskRuntimeRunState,
  session: FeatureTaskRuntimeRunLoopSession,
): FeatureTaskRuntimeRunLoopTransitionOwner = progress.transitionOwnerFor(session)

internal val PhaseRunState.runLoopCoupledTransitions: FeatureTaskRuntimeRunLoopTransitionOwner
  get() = coupledRunTransitionOwner(runLoopCoupledProgress(), runLoopCoupledSession())

internal fun runLoopCoupledTransitions(
  progress: FeatureTaskRuntimeRunState,
  session: FeatureTaskRuntimeRunLoopSession,
): FeatureTaskRuntimeRunLoopTransitionOwner = coupledRunTransitionOwner(progress, session)
