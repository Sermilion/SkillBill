package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict

/**
 * The run-loop decisions a strategy owns over the loops, re-entries, and settled steps of its slot. The run loop
 * asks the strategy selected for the step, or for the destination step of the loop, so it names no step or loop of
 * that slot itself. Each decision uses the accepted [PhaseAcceptedStepExecution] binding.
 * defaults to the neutral answer, so a strategy overrides only the decisions its slot owns.
 */
internal interface PhaseLoopRules {
  /** Reopens settled steps whose judged repository delta changed since they settled, before the run loop starts. */
  fun reopenStaleSettledSteps(
    context: PhaseLoopContext,
    state: PhaseAcceptedStepExecution,
  ) = Unit

  /** Invalidates the durable step evidence the run state marked stale, before the first step dispatches. */
  fun invalidateStaleEvidence(
    context: PhaseLoopContext,
    state: PhaseAcceptedStepExecution,
  ) = Unit

  /** Whether a resumed in-flight re-entry of [loopId] is stale and must be discarded instead of resumed. */
  fun discardsResumedReentry(
    loopId: String,
    state: PhaseAcceptedStepExecution,
  ): Boolean = false

  /** Whether a [loopId] re-entry its destination step left in flight resumes at that destination. */
  fun resumesInFlightReentry(loopId: String): Boolean = false

  /** The repository checkpoint the destination step of a [loopId] re-entry launches against, or null. */
  fun reentryCheckpoint(
    loopId: String,
    state: PhaseAcceptedStepExecution,
  ): String? = null

  /** Why [stepId] cannot be entered, or null when it can. */
  fun entryBlockReason(
    stepId: String,
    context: PhaseLoopContext,
    state: PhaseAcceptedStepExecution,
  ): String? = null

  /** Settles [stepId] from durable state without launching it, or null when the step launches. */
  fun settleWithoutLaunch(
    stepId: String,
    context: PhaseLoopContext,
    state: PhaseAcceptedStepExecution,
  ): PhaseEntrySettlement? = null

  /** The verdict the run loop routes on after [stepId] completed with [verdict]. */
  fun routedVerdict(
    stepId: String,
    verdict: FeatureTaskRuntimeVerdict,
    state: PhaseAcceptedStepExecution,
  ): FeatureTaskRuntimeVerdict = verdict

  /** The checkpoint the run loop commits before advancing forward from [stepId] to [destinationStepId], or null. */
  fun forwardCheckpoint(
    stepId: String,
    destinationStepId: String,
  ): PhaseForwardCheckpoint? = null
}

/** How a step settled from durable state without a launch. */
internal sealed interface PhaseEntrySettlement {
  data class Completed(
    val verdict: FeatureTaskRuntimeVerdict,
  ) : PhaseEntrySettlement

  data class Blocked(
    val reason: String,
  ) : PhaseEntrySettlement
}

internal class PhaseForwardCheckpoint(
  val intent: String,
  val blockedReason: (branch: String, error: String) -> String,
)
