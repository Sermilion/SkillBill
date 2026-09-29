package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupOutcome
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindingCoordinator
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopTransitionOwner
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.state.PhaseRunFanOut

internal class PhaseAttemptRunLoopBindingAccess internal constructor(
  private val host: PhaseAttemptRunHost,
) {
  val request: FeatureTaskRuntimeRunFacts
    get() = host.request

  val boundPhaseId: String
    get() = host.boundPhaseId

  val stepBinding: FeatureTaskRuntimeRunLoopStepBindingCoordinator
    get() = host.stepBinding

  val coupledRunTransitions: FeatureTaskRuntimeRunLoopTransitionOwner
    get() = host.coupledRunTransitions

  fun runAcceptedAgentStep(
    run: PhaseRun,
    call: PhaseStepCall,
  ): PhaseOutcome = host.runAcceptedAttemptLoop(run, call)

  fun ensureFeatureBranch(guardPhase: String): FeatureTaskRuntimeBranchSetupOutcome =
    host.ensureFeatureBranch(guardPhase)

  fun selectedOwnerOf(stepId: String): PhaseStrategy? = host.selectedOwnerOf(stepId)

  fun fanOut(stepId: String): PhaseRunFanOut = host.fanOut(stepId)
}

internal val PhaseAttemptLaunchCollaborationScope.runLoopBinding: PhaseAttemptRunLoopBindingAccess
  get() = PhaseAttemptRunLoopBindingAccess(attemptRunHost())
