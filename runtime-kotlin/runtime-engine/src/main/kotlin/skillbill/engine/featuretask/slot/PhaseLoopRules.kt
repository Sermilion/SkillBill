package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict

/**
 * The state-free run-loop decisions a strategy owns over the loops and re-entries of its slot. The run loop asks the
 * strategy selected for the step, or for the destination step of the loop, so it names no step or loop of that slot
 * itself. Each decision defaults to the neutral answer, so a strategy overrides only the decisions its slot owns.
 */
internal interface PhaseLoopRules {
  /** Whether a [loopId] re-entry its destination step left in flight resumes at that destination. */
  fun resumesInFlightReentry(loopId: String): Boolean = false

  /**
   * The checkpoint the run loop tries to commit before advancing forward from [stepId] to [destinationStepId], or
   * null. A failed forward checkpoint is a warning; the run still advances.
   */
  fun forwardCheckpoint(
    stepId: String,
    destinationStepId: String,
  ): PhaseForwardCheckpoint? = null
}

/**
 * The run-loop decisions a step descriptor owns over the durable state of its slot. They read the accepted binding
 * of type [B] that the descriptor's family constructs, so a decision reaches only the capabilities of that family.
 * Each decision defaults to the neutral answer, so a descriptor overrides only the decisions its slot owns.
 */
internal interface PhaseStepLoopRules<in B : PhaseAcceptedStepExecution> {
  /** Reopens settled steps whose judged repository delta changed since they settled, before the run loop starts. */
  fun reopenStaleSettledSteps(
    context: PhaseLoopContext,
    state: B,
  ) = Unit

  /** Invalidates the durable step evidence the run state marked stale, before the first step dispatches. */
  fun invalidateStaleEvidence(
    context: PhaseLoopContext,
    state: B,
  ) = Unit

  /** Whether a resumed in-flight re-entry of [loopId] is stale and must be discarded instead of resumed. */
  fun discardsResumedReentry(
    loopId: String,
    state: B,
  ): Boolean = false

  /** The repository checkpoint the destination step of a [loopId] re-entry launches against, or null. */
  fun reentryCheckpoint(
    loopId: String,
    state: B,
  ): String? = null

  /** Why [stepId] cannot be entered, or null when it can. */
  fun entryBlockReason(
    stepId: String,
    context: PhaseLoopContext,
    state: B,
  ): String? = null

  /** Settles [stepId] from durable state without launching it, or null when the step launches. */
  fun settleWithoutLaunch(
    stepId: String,
    context: PhaseLoopContext,
    state: B,
  ): PhaseEntrySettlement? = null

  /**
   * The verdict this slot settles another slot's [stepId] with before it launches, so a declared cross-slot edge
   * routes the run away from it, or null when [stepId] launches as usual.
   */
  fun crossSlotSettlement(
    stepId: String,
    context: PhaseLoopContext,
    state: B,
  ): FeatureTaskRuntimeVerdict? = null

  /** The verdict the run loop routes on after [stepId] completed with [verdict]. */
  fun routedVerdict(
    stepId: String,
    verdict: FeatureTaskRuntimeVerdict,
    state: B,
  ): FeatureTaskRuntimeVerdict = verdict
}

/**
 * [PhaseStepLoopRules] correlated with the one binding they read. It is built inside a descriptor capture, so the
 * binding type never escapes and the rules cannot be called with another step's binding.
 */
internal interface PhaseBoundLoopRules {
  val acceptedOwner: PhaseStrategy

  fun reopenStaleSettledSteps(context: PhaseLoopContext)

  fun invalidateStaleEvidence(context: PhaseLoopContext)

  fun discardsResumedReentry(loopId: String): Boolean

  fun reentryCheckpoint(loopId: String): String?

  fun entryBlockReason(
    stepId: String,
    context: PhaseLoopContext,
  ): String?

  fun settleWithoutLaunch(
    stepId: String,
    context: PhaseLoopContext,
  ): PhaseEntrySettlement?

  fun crossSlotSettlement(
    stepId: String,
    context: PhaseLoopContext,
  ): FeatureTaskRuntimeVerdict?

  fun routedVerdict(
    stepId: String,
    verdict: FeatureTaskRuntimeVerdict,
  ): FeatureTaskRuntimeVerdict

  companion object {
    fun <B : PhaseAcceptedStepExecution> of(
      rules: PhaseStepLoopRules<B>,
      binding: B,
    ): PhaseBoundLoopRules = BoundLoopRules(rules, binding)
  }
}

private class BoundLoopRules<B : PhaseAcceptedStepExecution>(
  private val rules: PhaseStepLoopRules<B>,
  private val binding: B,
) : PhaseBoundLoopRules {
  override val acceptedOwner: PhaseStrategy get() = binding.acceptedOwner

  override fun reopenStaleSettledSteps(context: PhaseLoopContext) = rules.reopenStaleSettledSteps(context, binding)

  override fun invalidateStaleEvidence(context: PhaseLoopContext) = rules.invalidateStaleEvidence(context, binding)

  override fun discardsResumedReentry(loopId: String): Boolean = rules.discardsResumedReentry(loopId, binding)

  override fun reentryCheckpoint(loopId: String): String? = rules.reentryCheckpoint(loopId, binding)

  override fun entryBlockReason(
    stepId: String,
    context: PhaseLoopContext,
  ): String? = rules.entryBlockReason(stepId, context, binding)

  override fun settleWithoutLaunch(
    stepId: String,
    context: PhaseLoopContext,
  ): PhaseEntrySettlement? = rules.settleWithoutLaunch(stepId, context, binding)

  override fun crossSlotSettlement(
    stepId: String,
    context: PhaseLoopContext,
  ): FeatureTaskRuntimeVerdict? = rules.crossSlotSettlement(stepId, context, binding)

  override fun routedVerdict(
    stepId: String,
    verdict: FeatureTaskRuntimeVerdict,
  ): FeatureTaskRuntimeVerdict = rules.routedVerdict(stepId, verdict, binding)
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
  val failureWarning: (branch: String, error: String) -> String,
)
