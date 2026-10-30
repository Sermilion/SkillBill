package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict

/**
 * The state-free run-loop decisions a strategy owns over the loops and re-entries of its slot. The run loop asks the
 * strategy selected for the step, or for the destination step of the loop, so it names no step or loop of that slot
 * itself. A strategy declares only the decisions its slot owns, each as a capability next to this interface, and the
 * run loop reads the neutral answer for every capability the strategy does not declare.
 */
internal interface PhaseLoopRules

/** A slot whose loop re-entries can be left in flight and resumed at their destination step. */
internal interface PhaseInFlightReentryRules : PhaseLoopRules {
  /** Whether a [loopId] re-entry its destination step left in flight resumes at that destination. */
  fun resumesInFlightReentry(loopId: String): Boolean
}

/** A slot that commits a checkpoint before the run advances forward out of one of its steps. */
internal interface PhaseForwardCheckpointRules : PhaseLoopRules {
  /**
   * The checkpoint the run loop tries to commit before advancing forward from [stepId] to [destinationStepId], or
   * null. A failed forward checkpoint is a warning; the run still advances.
   */
  fun forwardCheckpoint(
    stepId: String,
    destinationStepId: String,
  ): PhaseForwardCheckpoint?
}

/** A slot that owns containment of a failure escaping its steps. */
internal interface PhaseFailureClaimRules : PhaseLoopRules {
  /**
   * Whether this slot owns containment of a failure escaping [stepId]. It reads no step binding, so the run loop
   * can still tell a slot's failure from another phase's when building that binding is what failed.
   */
  fun claimsFailure(
    stepId: String,
    context: PhaseLoopContext,
  ): Boolean
}

/**
 * The run-loop decisions a step descriptor owns over the durable state of its slot. They read the accepted binding
 * of type [B] that the descriptor's family constructs, so a decision reaches only the capabilities of that family.
 * A descriptor declares only the decisions its slot owns, each as a capability next to this interface, and the run
 * loop reads the neutral answer for every capability the descriptor does not declare.
 */
internal interface PhaseStepLoopRules<in B : PhaseAcceptedStepExecution> {
  /** The verdict the run loop routes on after [stepId] completed with [verdict]. */
  fun routedVerdict(
    stepId: String,
    verdict: FeatureTaskRuntimeVerdict,
    state: B,
  ): FeatureTaskRuntimeVerdict = verdict
}

/** A slot that reopens, invalidates, discards and checkpoints the durable state of its loop re-entries. */
internal interface PhaseReentryStateRules<in B : PhaseAcceptedStepExecution> : PhaseStepLoopRules<B> {
  /** Reopens settled steps whose judged repository delta changed since they settled, before the run loop starts. */
  fun reopenStaleSettledSteps(
    context: PhaseLoopContext,
    state: B,
  )

  /** Invalidates the durable step evidence the run state marked stale, before the first step dispatches. */
  fun invalidateStaleEvidence(
    context: PhaseLoopContext,
    state: B,
  )

  /** Whether a resumed in-flight re-entry of [loopId] is stale and must be discarded instead of resumed. */
  fun discardsResumedReentry(
    loopId: String,
    state: B,
  ): Boolean

  /** The repository checkpoint the destination step of a [loopId] re-entry launches against, or null. */
  fun reentryCheckpoint(
    loopId: String,
    state: B,
  ): String?
}

/** A slot that refuses entry into some of its steps. */
internal interface PhaseEntryBlockRules<in B : PhaseAcceptedStepExecution> : PhaseStepLoopRules<B> {
  /** Why [stepId] cannot be entered, or null when it can. */
  fun entryBlockReason(
    stepId: String,
    context: PhaseLoopContext,
    state: B,
  ): String?
}

/** A slot that settles some of its steps from durable state without launching them. */
internal interface PhaseEntrySettlementRules<in B : PhaseAcceptedStepExecution> : PhaseStepLoopRules<B> {
  /** Settles [stepId] from durable state without launching it, or null when the step launches. */
  fun settleWithoutLaunch(
    stepId: String,
    context: PhaseLoopContext,
    state: B,
  ): PhaseEntrySettlement?
}

/** A slot that contains its own failures and settles another slot's steps across a declared cross-slot edge. */
internal interface PhaseSlotContainmentRules<in B : PhaseAcceptedStepExecution> : PhaseStepLoopRules<B> {
  /**
   * The verdict this slot settles another slot's [stepId] with before it launches, so a declared cross-slot edge
   * routes the run away from it, or null when [stepId] launches as usual.
   */
  fun crossSlotSettlement(
    stepId: String,
    context: PhaseLoopContext,
    state: B,
  ): FeatureTaskRuntimeVerdict?

  /** Notes that the [loopId] re-entry cap of [cap] is exhausted and the run advances past it instead of blocking. */
  fun onLoopCapExhausted(
    loopId: String,
    cap: Int,
    context: PhaseLoopContext,
    state: B,
  )

  /**
   * The completed output this slot continues [stepId] with when [error] escapes its dispatch, so the slot's failure
   * never blocks the run, or null when the failure is not this slot's to contain.
   */
  fun containFailure(
    stepId: String,
    error: Exception,
    context: PhaseLoopContext,
    state: B,
  ): PhaseFailureContinuationOutput?
}

internal fun <B : PhaseAcceptedStepExecution> PhaseStepLoopRules<B>.reopenStaleSettledSteps(
  context: PhaseLoopContext,
  state: B,
) {
  (this as? PhaseReentryStateRules<B>)?.reopenStaleSettledSteps(context, state)
}

internal fun <B : PhaseAcceptedStepExecution> PhaseStepLoopRules<B>.invalidateStaleEvidence(
  context: PhaseLoopContext,
  state: B,
) {
  (this as? PhaseReentryStateRules<B>)?.invalidateStaleEvidence(context, state)
}

internal fun <B : PhaseAcceptedStepExecution> PhaseStepLoopRules<B>.discardsResumedReentry(
  loopId: String,
  state: B,
): Boolean = (this as? PhaseReentryStateRules<B>)?.discardsResumedReentry(loopId, state) ?: false

internal fun <B : PhaseAcceptedStepExecution> PhaseStepLoopRules<B>.reentryCheckpoint(
  loopId: String,
  state: B,
): String? = (this as? PhaseReentryStateRules<B>)?.reentryCheckpoint(loopId, state)

internal fun <B : PhaseAcceptedStepExecution> PhaseStepLoopRules<B>.entryBlockReason(
  stepId: String,
  context: PhaseLoopContext,
  state: B,
): String? = (this as? PhaseEntryBlockRules<B>)?.entryBlockReason(stepId, context, state)

internal fun <B : PhaseAcceptedStepExecution> PhaseStepLoopRules<B>.settleWithoutLaunch(
  stepId: String,
  context: PhaseLoopContext,
  state: B,
): PhaseEntrySettlement? = (this as? PhaseEntrySettlementRules<B>)?.settleWithoutLaunch(stepId, context, state)

internal fun <B : PhaseAcceptedStepExecution> PhaseStepLoopRules<B>.crossSlotSettlement(
  stepId: String,
  context: PhaseLoopContext,
  state: B,
): FeatureTaskRuntimeVerdict? = (this as? PhaseSlotContainmentRules<B>)?.crossSlotSettlement(stepId, context, state)

internal fun <B : PhaseAcceptedStepExecution> PhaseStepLoopRules<B>.onLoopCapExhausted(
  loopId: String,
  cap: Int,
  context: PhaseLoopContext,
  state: B,
) {
  (this as? PhaseSlotContainmentRules<B>)?.onLoopCapExhausted(loopId, cap, context, state)
}

internal fun <B : PhaseAcceptedStepExecution> PhaseStepLoopRules<B>.containFailure(
  stepId: String,
  error: Exception,
  context: PhaseLoopContext,
  state: B,
): PhaseFailureContinuationOutput? =
  (this as? PhaseSlotContainmentRules<B>)?.containFailure(stepId, error, context, state)

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

  fun onLoopCapExhausted(
    loopId: String,
    cap: Int,
    context: PhaseLoopContext,
  )

  fun containFailure(
    stepId: String,
    error: Exception,
    context: PhaseLoopContext,
  ): PhaseFailureContinuationOutput?

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

  override fun onLoopCapExhausted(
    loopId: String,
    cap: Int,
    context: PhaseLoopContext,
  ) = rules.onLoopCapExhausted(loopId, cap, context, binding)

  override fun containFailure(
    stepId: String,
    error: Exception,
    context: PhaseLoopContext,
  ): PhaseFailureContinuationOutput? = rules.containFailure(stepId, error, context, binding)
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
