package skillbill.engine.featuretask.slot

import skillbill.application.review.service.RuntimeOwnedReviewMode
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeImplementationTurn
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptTraversalHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseAgentStepBinding
import skillbill.engine.featuretask.slot.state.PhaseCommitStepBinding
import skillbill.engine.featuretask.slot.state.PhaseImplementFixStepBinding
import skillbill.engine.featuretask.slot.state.PhaseMonitorStepBinding
import skillbill.engine.featuretask.slot.state.PhasePlanningAgentStepBinding
import skillbill.engine.featuretask.slot.state.PhasePlanningStepBinding
import skillbill.engine.featuretask.slot.state.PhasePlanningUnitBinding
import skillbill.engine.featuretask.slot.state.PhasePullRequestStepBinding
import skillbill.engine.featuretask.slot.state.PhaseQaStepBinding
import skillbill.engine.featuretask.slot.state.PhaseQualityGateStepBinding
import skillbill.engine.featuretask.slot.state.PhaseReviewStepBinding
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseStandaloneMonitorStepBinding
import skillbill.engine.featuretask.slot.state.PhaseStandalonePublicationStepBinding
import skillbill.engine.featuretask.slot.state.PhaseVerifyFindingsStepBinding
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.review.ReviewPassResolution

/**
 * The launch and output behaviour one strategy step adds to the shared attempt path. The shared launch
 * preparation and output gate ask the strategy of the running step for its hooks, so step-specific evidence,
 * prompt sections, and output checks live with the step instead of in the shared code.
 */
public interface PhaseStepHooks<in B : PhaseAcceptedStepExecution> : PhaseStepLaunchHooks<B> {
  public val contextKind: PhaseStepHookContextKind get() = PhaseStepHookContextKind.COMMON

  /** The rule a [completionRejection] of this step is recorded under in the durable block reason. */
  public val completionRejectionRule: String
    get() = PhaseStepOutputCheck.OUTPUT_VERIFICATION_RULE

  /** The failure disposition a blocked terminal output of this step gets when it names none. */
  public val blockedOutputDisposition: FeatureTaskRuntimeFailureDisposition
    get() = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION

  /** Checks validated [outputMap] of [run] before the shared output checks run. */
  public fun checkValidatedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: B,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck = PhaseStepOutputCheck.Accept

  /** Settles the step records that completed [outputMap] of [run] carries, once every completion check passed. */
  public fun settleCompletedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: B,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck = PhaseStepOutputCheck.Accept

  /**
   * The form of completed [attested] output of [capture] the output gate accepts, given its validated [outputMap]. A
   * step stamps the facts the runtime measured around it here.
   */
  public fun acceptedOutput(
    context: PhaseStepOutputContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): NormalizedFeatureTaskRuntimePhaseOutput = attested

  public fun interpretedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: B,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput = output

  public companion object {
    internal val None: PhaseStepHooks<PhaseAcceptedStepExecution> =
      object : PhaseStepHooks<PhaseAcceptedStepExecution> {}
  }
}

/** A step that owns one serial acceptance-repair mutation attempt and rejects an unfinished agent completion. */
internal interface PhaseAcceptanceRepairHooks {
  /** Whether the accepted step owns one serial acceptance-repair mutation attempt. */
  val serialAcceptanceRepairStep: Boolean

  /** The failure code an agent completion that left the step unfinished is rejected with. */
  val unfinishedAgentCompletionRejection: FeatureTaskRuntimeFailureCode
}

/** A step whose agent launch can be refused after briefing persistence. */
internal interface PhaseBeforeLaunchHooks<in B : PhaseAcceptedStepExecution> : PhaseStepHooks<B> {
  /** The reason the strategy cannot proceed with an agent launch after briefing persistence, or null when it can. */
  fun beforeAgentLaunch(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
    state: B,
  ): String?
}

/** A step whose output gate fingerprints the repository when the step completes. */
internal interface PhaseRepositoryFingerprintHooks {
  val fingerprintsCompletedRepository: Boolean
}

/** A step whose completed output must carry the projections its immediate consumers read. */
internal interface PhaseConsumerProjectionHooks {
  val checksImmediateConsumerProjection: Boolean
}

/** A runtime-owned step that carries the pack build command and settles its triage and repair sessions itself. */
internal interface PhaseRuntimeOwnedStepHooks {
  /** Whether the launch prompt of this step carries the build command the validation gate declares. */
  val carriesPackBuildCommand: Boolean

  /**
   * The outcome [outputText] of [run] settles to before the shared output gate decodes it, or null when the shared
   * gate decodes it.
   */
  fun earlyOutput(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
  ): PhaseOutcome?
}

/** A step whose blocked terminal output retries with a correction inside the retryable-failure budget. */
internal interface PhaseBlockedOutputRetryHooks {
  /**
   * The correction a blocked terminal [outputMap] of this step retries with inside the retryable-failure budget, or
   * null when the block settles.
   */
  fun blockedOutputRetryCorrection(outputMap: FeatureTaskRuntimeWorkflowArtifactMap): String?
}

/** A step that rejects completed output the shared output checks accept. */
internal interface PhaseCompletionRejectionHooks<in B : PhaseAcceptedStepExecution> : PhaseStepHooks<B> {
  /** The reason completed [outputMap] of [run] cannot settle, or null when it can. */
  fun completionRejection(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: B,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String?
}

/** A step that settles its round after its records are settled. */
internal interface PhaseCompletedRoundHooks {
  /**
   * The attempt result completed [outputMap] of [capture] settles to once the step's records are settled: a block or
   * an in-phase retry, or null when the output goes on to acceptance.
   */
  fun settleCompletedRound(
    context: PhaseStepOutputContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AttemptResult?
}

/** A step that records evidence from its accepted output before the completed step is persisted. */
internal interface PhaseAcceptedOutputRecordingHooks<in B : PhaseAcceptedStepExecution> : PhaseStepHooks<B> {
  /** Records step evidence from accepted [outputMap] of [run] before the completed step is persisted. */
  fun recordAcceptedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: B,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  )
}

/** A step that decides, once recorded completed, what its completed output means for the rest of the run. */
internal interface PhaseAfterCompletionHooks {
  /**
   * Settles what completed [output] of this step decides for the rest of the run, once the step is recorded
   * completed: the blocked reason that stops the run, or null when the run continues.
   */
  fun afterCompletion(
    context: PhaseAttemptTraversalHookContext,
    output: FeatureTaskRuntimePhaseOutput,
  ): String?
}

/** The launch behaviour one strategy step adds to the shared launch preparation and pre-launch checks. */
public interface PhaseStepLaunchHooks<in B : PhaseAcceptedStepExecution> {
  /** The repository checkpoint the launch handoff of [run] expects, given the [current] checkpoint fingerprint. */
  public fun expectedLaunchCheckpoint(
    run: PhaseRun,
    current: String?,
  ): String? = run.reentry?.expectedRepositoryCheckpoint ?: current

  /** The review pass and review tier the launch prompt of [run] carries. */
  public fun launchReviewTier(
    run: PhaseRun,
    state: B,
  ): PhaseLaunchReviewTier =
    PhaseLaunchReviewTier(
      passNumber = null,
      resolution = null,
      executedTier = RuntimeOwnedReviewMode.execute(run.request.runInvariants.codeReviewMode),
    )

  /**
   * Whether attempt [iteration] of [run] launches, once its required start is acknowledged and before launch
   * preparation composes its prompt. A step that owns durable turn accounting reserves the turn here.
   */
  public fun admitLaunch(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
    state: B,
    iteration: Int,
  ): PhaseLaunchAdmission = PhaseLaunchAdmission.Launch
}

/** A step that appends prompt sections after the composed launch prompt. */
internal interface PhaseLaunchPromptHooks<in B : PhaseAcceptedStepExecution> : PhaseStepLaunchHooks<B> {
  /** The prompt sections appended after the composed launch prompt of [run]. */
  fun launchPromptSupplement(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
    state: B,
  ): String
}

/** A step whose launch handoff carries recorded finding verdicts. */
internal interface PhaseFindingVerdictHooks<in B : PhaseAcceptedStepExecution> : PhaseStepLaunchHooks<B> {
  /** The recorded finding verdicts the launch handoff of this step carries. */
  fun handoffFindingVerdicts(state: B): List<ReviewFindingVerdict>
}

/** A step that continues with a completed output when a stop of [run] would otherwise block or pause it. */
internal interface PhaseFailureContinuationHooks<in B : PhaseAcceptedStepExecution> : PhaseStepLaunchHooks<B> {
  /**
   * The completed output this step continues with when [failure] stops [run], read through the active step [state], or
   * null when the stop keeps its block or pause behaviour.
   */
  fun failureContinuationOutput(
    run: PhaseRun,
    failure: PhaseStepFailure,
    state: B,
  ): PhaseFailureContinuationOutput?
}

/** A step that continues with a completed output computed from its upstream outputs alone, whatever stopped it. */
internal interface PhaseUpstreamFailureContinuationHooks {
  /** The completed output this step continues with when it stops for any reason, from [upstream] alone. */
  fun failureContinuationOutput(upstream: List<FeatureTaskRuntimePhaseOutput>): PhaseFailureContinuationOutput?
}

/** A step that reconciles the durable state [run] reads before the shared pre-launch checks. */
internal interface PhaseLaunchReconciliationHooks {
  /** Reconciles the durable state [run] reads before the shared pre-launch checks decide whether it can launch. */
  fun reconcileBeforeLaunch(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
  )
}

/** A step that launches an implementation turn. */
internal interface PhaseImplementationTurnHooks {
  /** The implementation turn admitted attempt [iteration] of [run] launches, or null when it launches no turn. */
  fun implementationTurn(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
    iteration: Int,
  ): FeatureTaskRuntimeImplementationTurn?
}

internal val PhaseStepHooks<*>.serialAcceptanceRepairStep: Boolean
  get() = (this as? PhaseAcceptanceRepairHooks)?.serialAcceptanceRepairStep ?: false

internal val PhaseStepHooks<*>.fingerprintsCompletedRepository: Boolean
  get() = (this as? PhaseRepositoryFingerprintHooks)?.fingerprintsCompletedRepository ?: false

internal val PhaseStepHooks<*>.checksImmediateConsumerProjection: Boolean
  get() = (this as? PhaseConsumerProjectionHooks)?.checksImmediateConsumerProjection ?: false

internal val PhaseStepLaunchHooks<*>.carriesPackBuildCommand: Boolean
  get() = (this as? PhaseRuntimeOwnedStepHooks)?.carriesPackBuildCommand ?: false

internal fun PhaseStepHooks<*>.earlyOutput(
  run: PhaseRun,
  iteration: Int,
  outputText: String,
): PhaseOutcome? = (this as? PhaseRuntimeOwnedStepHooks)?.earlyOutput(run, iteration, outputText)

internal fun PhaseStepHooks<*>.blockedOutputRetryCorrection(outputMap: FeatureTaskRuntimeWorkflowArtifactMap): String? =
  (this as? PhaseBlockedOutputRetryHooks)?.blockedOutputRetryCorrection(outputMap)

internal fun PhaseStepHooks<*>.settleCompletedRound(
  context: PhaseStepOutputContext,
  capture: ValidatedOutputCapture,
  attested: NormalizedFeatureTaskRuntimePhaseOutput,
  outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
): AttemptResult? = (this as? PhaseCompletedRoundHooks)?.settleCompletedRound(context, capture, attested, outputMap)

internal fun PhaseStepLaunchHooks<*>.implementationTurn(
  run: PhaseRun,
  context: PhaseAttemptLaunchHookContext,
  iteration: Int,
): FeatureTaskRuntimeImplementationTurn? =
  (this as? PhaseImplementationTurnHooks)?.implementationTurn(run, context, iteration)

internal fun <B : PhaseAcceptedStepExecution> PhaseStepHooks<B>.beforeAgentLaunch(
  run: PhaseRun,
  context: PhaseAttemptLaunchHookContext,
  state: B,
): String? = (this as? PhaseBeforeLaunchHooks<B>)?.beforeAgentLaunch(run, context, state)

internal fun <B : PhaseAcceptedStepExecution> PhaseStepHooks<B>.completionRejection(
  run: PhaseRun,
  context: PhaseStepOutputContext,
  state: B,
  outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
): String? = (this as? PhaseCompletionRejectionHooks<B>)?.completionRejection(run, context, state, outputMap)

internal fun <B : PhaseAcceptedStepExecution> PhaseStepHooks<B>.recordAcceptedOutput(
  run: PhaseRun,
  context: PhaseStepOutputContext,
  state: B,
  outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
) {
  (this as? PhaseAcceptedOutputRecordingHooks<B>)?.recordAcceptedOutput(run, context, state, outputMap)
}

internal fun <B : PhaseAcceptedStepExecution> PhaseStepLaunchHooks<B>.launchPromptSupplement(
  run: PhaseRun,
  context: PhaseAttemptLaunchHookContext,
  state: B,
): String = (this as? PhaseLaunchPromptHooks<B>)?.launchPromptSupplement(run, context, state) ?: ""

internal fun <B : PhaseAcceptedStepExecution> PhaseStepLaunchHooks<B>.handoffFindingVerdicts(
  state: B,
): List<ReviewFindingVerdict> = (this as? PhaseFindingVerdictHooks<B>)?.handoffFindingVerdicts(state).orEmpty()

internal fun <B : PhaseAcceptedStepExecution> PhaseStepLaunchHooks<B>.failureContinuationOutput(
  run: PhaseRun,
  failure: PhaseStepFailure,
  state: B,
): PhaseFailureContinuationOutput? =
  (this as? PhaseFailureContinuationHooks<B>)?.failureContinuationOutput(run, failure, state)

/** What a step decides about one attempt before launch preparation. */
public sealed interface PhaseLaunchAdmission {
  public data object Launch : PhaseLaunchAdmission

  public data class Block(
    val reason: String,
    val disposition: FeatureTaskRuntimeFailureDisposition,
  ) : PhaseLaunchAdmission

  public data class WriteRejected(
    val rejection: RequiredPhaseWrite.Rejected,
  ) : PhaseLaunchAdmission

  public data class Settled(
    val outcome: PhaseOutcome,
  ) : PhaseLaunchAdmission
}

public data class PhaseLaunchReviewTier(
  val passNumber: Int?,
  val resolution: ReviewPassResolution?,
  val executedTier: CodeReviewExecutionMode,
)

/** The verdict of a step's check over its validated output. */
public sealed interface PhaseStepOutputCheck {
  public data object Accept : PhaseStepOutputCheck

  public data class Reject(
    val reason: String,
    val rule: String = OUTPUT_VERIFICATION_RULE,
  ) : PhaseStepOutputCheck

  public data class Redeliver(
    val reason: String,
  ) : PhaseStepOutputCheck

  public data class OweFindings(
    val reason: String,
    val refs: Set<String>,
  ) : PhaseStepOutputCheck

  public data class Block(
    val reason: String,
    val disposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
  ) : PhaseStepOutputCheck

  public companion object {
    public const val OUTPUT_VERIFICATION_RULE: String = "output-verification"
  }
}

internal fun NormalizedFeatureTaskRuntimePhaseOutput.withMeasuredFacts(
  facts: Map<String, Any>,
): NormalizedFeatureTaskRuntimePhaseOutput {
  val envelope = envelopeWireMap().toMutableMap()
  val produced = JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]).orEmpty().toMutableMap()
  produced[FeatureTaskRuntimeMeasuredFactKeys.MEASURED_FACTS] = facts
  envelope[SharedPayloadKeys.PRODUCED_OUTPUTS] = produced
  return NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(FeatureTaskRuntimeWorkflowArtifactMap.from(envelope))
}

public enum class PhaseStepHookContextKind {
  COMMON,
  AUDIT,
  COMMIT,
  FINDING_VERIFICATION,
  IMPLEMENTATION,
  PULL_REQUEST,
  PLANNING,
}

internal sealed interface PhaseBindingFamily<B : PhaseAcceptedStepExecution> {
  fun <R> construct(
    factory: PhaseBindingFactory,
    hooks: PhaseStepHooks<B>,
    use: (B) -> R,
  ): R

  data object Agent : PhaseBindingFamily<PhaseAgentStepBinding> {
    override fun <R> construct(
      factory: PhaseBindingFactory,
      hooks: PhaseStepHooks<PhaseAgentStepBinding>,
      use: (PhaseAgentStepBinding) -> R,
    ): R = factory.agent(hooks, use)
  }

  data object PlanningAgent : PhaseBindingFamily<PhasePlanningAgentStepBinding> {
    override fun <R> construct(
      factory: PhaseBindingFactory,
      hooks: PhaseStepHooks<PhasePlanningAgentStepBinding>,
      use: (PhasePlanningAgentStepBinding) -> R,
    ): R = factory.planningAgent(hooks, use)
  }

  data object PlanningFanOut : PhaseBindingFamily<PhasePlanningStepBinding> {
    override fun <R> construct(
      factory: PhaseBindingFactory,
      hooks: PhaseStepHooks<PhasePlanningStepBinding>,
      use: (PhasePlanningStepBinding) -> R,
    ): R = factory.planningFanOut(hooks, use)
  }

  data object PlanningUnit : PhaseBindingFamily<PhasePlanningUnitBinding> {
    override fun <R> construct(
      factory: PhaseBindingFactory,
      hooks: PhaseStepHooks<PhasePlanningUnitBinding>,
      use: (PhasePlanningUnitBinding) -> R,
    ): R = factory.planningUnit(hooks, use)
  }

  data object QaFindings : PhaseBindingFamily<PhaseQaStepBinding> {
    override fun <R> construct(
      factory: PhaseBindingFactory,
      hooks: PhaseStepHooks<PhaseQaStepBinding>,
      use: (PhaseQaStepBinding) -> R,
    ): R = factory.qaFindings(hooks, use)
  }

  data class QualityGate(val operation: PhaseQualityGateOperation) : PhaseBindingFamily<PhaseQualityGateStepBinding> {
    override fun <R> construct(
      factory: PhaseBindingFactory,
      hooks: PhaseStepHooks<PhaseQualityGateStepBinding>,
      use: (PhaseQualityGateStepBinding) -> R,
    ): R = factory.qualityGate(operation, hooks, use)
  }

  data object Commit : PhaseBindingFamily<PhaseCommitStepBinding> {
    override fun <R> construct(
      factory: PhaseBindingFactory,
      hooks: PhaseStepHooks<PhaseCommitStepBinding>,
      use: (PhaseCommitStepBinding) -> R,
    ): R = factory.commit(hooks, use)
  }

  data object Monitor : PhaseBindingFamily<PhaseMonitorStepBinding> {
    override fun <R> construct(
      factory: PhaseBindingFactory,
      hooks: PhaseStepHooks<PhaseMonitorStepBinding>,
      use: (PhaseMonitorStepBinding) -> R,
    ): R = factory.monitor(hooks, use)
  }

  data object StandaloneMonitor : PhaseBindingFamily<PhaseStandaloneMonitorStepBinding> {
    override fun <R> construct(
      factory: PhaseBindingFactory,
      hooks: PhaseStepHooks<PhaseStandaloneMonitorStepBinding>,
      use: (PhaseStandaloneMonitorStepBinding) -> R,
    ): R = factory.standaloneMonitor(hooks, use)
  }

  data object StandalonePublication : PhaseBindingFamily<PhaseStandalonePublicationStepBinding> {
    override fun <R> construct(
      factory: PhaseBindingFactory,
      hooks: PhaseStepHooks<PhaseStandalonePublicationStepBinding>,
      use: (PhaseStandalonePublicationStepBinding) -> R,
    ): R = factory.standalonePublication(hooks, use)
  }

  data object PullRequest : PhaseBindingFamily<PhasePullRequestStepBinding> {
    override fun <R> construct(
      factory: PhaseBindingFactory,
      hooks: PhaseStepHooks<PhasePullRequestStepBinding>,
      use: (PhasePullRequestStepBinding) -> R,
    ): R = factory.pullRequest(hooks, use)
  }
}

internal data object ReviewBindingFamily : PhaseBindingFamily<PhaseReviewStepBinding> {
  override fun <R> construct(
    factory: PhaseBindingFactory,
    hooks: PhaseStepHooks<PhaseReviewStepBinding>,
    use: (PhaseReviewStepBinding) -> R,
  ): R = factory.review(hooks, use)
}

internal data object FindingVerificationBindingFamily : PhaseBindingFamily<PhaseVerifyFindingsStepBinding> {
  override fun <R> construct(
    factory: PhaseBindingFactory,
    hooks: PhaseStepHooks<PhaseVerifyFindingsStepBinding>,
    use: (PhaseVerifyFindingsStepBinding) -> R,
  ): R = factory.findingVerification(hooks, use)
}

internal data object RepairReceiptBindingFamily : PhaseBindingFamily<PhaseImplementFixStepBinding> {
  override fun <R> construct(
    factory: PhaseBindingFactory,
    hooks: PhaseStepHooks<PhaseImplementFixStepBinding>,
    use: (PhaseImplementFixStepBinding) -> R,
  ): R = factory.repairReceipt(hooks, use)
}

public interface PhaseBindingFactory :
  PhaseAgentBindingFactory,
  PhaseReviewBindingFactory,
  PhaseSideEffectBindingFactory

public interface PhaseAgentBindingFactory {
  public fun <R> agent(
    hooks: PhaseStepHooks<PhaseAgentStepBinding>,
    use: (PhaseAgentStepBinding) -> R,
  ): R

  public fun <R> planningAgent(
    hooks: PhaseStepHooks<PhasePlanningAgentStepBinding>,
    use: (PhasePlanningAgentStepBinding) -> R,
  ): R

  public fun <R> planningFanOut(
    hooks: PhaseStepHooks<PhasePlanningStepBinding>,
    use: (PhasePlanningStepBinding) -> R,
  ): R

  public fun <R> planningUnit(
    hooks: PhaseStepHooks<PhasePlanningUnitBinding>,
    use: (PhasePlanningUnitBinding) -> R,
  ): R

  public fun <R> qaFindings(
    hooks: PhaseStepHooks<PhaseQaStepBinding>,
    use: (PhaseQaStepBinding) -> R,
  ): R
}

public interface PhaseReviewBindingFactory {
  public fun <R> review(
    hooks: PhaseStepHooks<PhaseReviewStepBinding>,
    use: (PhaseReviewStepBinding) -> R,
  ): R

  public fun <R> findingVerification(
    hooks: PhaseStepHooks<PhaseVerifyFindingsStepBinding>,
    use: (PhaseVerifyFindingsStepBinding) -> R,
  ): R

  public fun <R> repairReceipt(
    hooks: PhaseStepHooks<PhaseImplementFixStepBinding>,
    use: (PhaseImplementFixStepBinding) -> R,
  ): R
}

public interface PhaseSideEffectBindingFactory {
  public fun <R> qualityGate(
    operation: PhaseQualityGateOperation,
    hooks: PhaseStepHooks<PhaseQualityGateStepBinding>,
    use: (PhaseQualityGateStepBinding) -> R,
  ): R

  public fun <R> commit(
    hooks: PhaseStepHooks<PhaseCommitStepBinding>,
    use: (PhaseCommitStepBinding) -> R,
  ): R

  public fun <R> monitor(
    hooks: PhaseStepHooks<PhaseMonitorStepBinding>,
    use: (PhaseMonitorStepBinding) -> R,
  ): R

  public fun <R> standaloneMonitor(
    hooks: PhaseStepHooks<PhaseStandaloneMonitorStepBinding>,
    use: (PhaseStandaloneMonitorStepBinding) -> R,
  ): R

  public fun <R> standalonePublication(
    hooks: PhaseStepHooks<PhaseStandalonePublicationStepBinding>,
    use: (PhaseStandalonePublicationStepBinding) -> R,
  ): R

  public fun <R> pullRequest(
    hooks: PhaseStepHooks<PhasePullRequestStepBinding>,
    use: (PhasePullRequestStepBinding) -> R,
  ): R
}

public class PhaseStepDescriptor<B : PhaseAcceptedStepExecution> internal constructor(
  internal val family: PhaseBindingFamily<B>,
  internal val hooks: PhaseStepHooks<B>,
  internal val loopRules: PhaseStepLoopRules<B>? = null,
  private val execution: (PhaseRun, B) -> PhaseOutcome,
) {
  public fun bind(factory: PhaseBindingFactory): B = family.construct(factory, hooks) { binding -> binding }

  public fun execute(
    run: PhaseRun,
    binding: B,
  ): PhaseOutcome = execution(run, binding)

  public fun <R> capture(visitor: PhaseStepCapture<R>): R = visitor.capture(this)
}

public interface PhaseStepCapture<R> {
  public fun <B : PhaseAcceptedStepExecution> capture(descriptor: PhaseStepDescriptor<B>): R
}

public class PhaseBoundStepHooks<B : PhaseAcceptedStepExecution> internal constructor(
  private val hooks: PhaseStepHooks<B>,
  private val binding: B,
) {
  internal fun beforeAgentLaunch(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
  ): String? = hooks.beforeAgentLaunch(run, context, binding)

  internal fun launchPromptSupplement(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
  ): String = hooks.launchPromptSupplement(run, context, binding)

  internal fun failureContinuationOutput(
    run: PhaseRun,
    failure: PhaseStepFailure,
  ): PhaseFailureContinuationOutput? = hooks.failureContinuationOutput(run, failure, binding)

  internal fun launchReviewTier(run: PhaseRun): PhaseLaunchReviewTier = hooks.launchReviewTier(run, binding)

  internal fun handoffFindingVerdicts(): List<ReviewFindingVerdict> = hooks.handoffFindingVerdicts(binding)

  internal fun admitLaunch(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
    iteration: Int,
  ): PhaseLaunchAdmission = hooks.admitLaunch(run, context, binding, iteration)

  internal fun checkValidatedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck = hooks.checkValidatedOutput(run, context, binding, outputMap)

  internal fun completionRejection(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? = hooks.completionRejection(run, context, binding, outputMap)

  internal fun settleCompletedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck = hooks.settleCompletedOutput(run, context, binding, outputMap)

  internal fun interpretedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput = hooks.interpretedOutput(run, context, binding, output)

  internal fun recordAcceptedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ) = hooks.recordAcceptedOutput(run, context, binding, outputMap)
}

public enum class PhaseStepFailureSource {
  BLOCK,
  PAUSE,
  THROWN,
}

public data class PhaseStepFailure(
  val reason: String,
  val source: PhaseStepFailureSource,
  val failureCode: String? = null,
  val owedFindingRefs: Set<String> = emptySet(),
)

public data class PhaseFailureContinuationOutput(
  val output: NormalizedFeatureTaskRuntimePhaseOutput,
  val diagnosticDetail: String? = null,
)

/** A step's decision, bound to its active step binding, over one stop of [run]. */
public fun interface PhaseFailureContinuation {
  public fun outputFor(
    run: PhaseRun,
    failure: PhaseStepFailure,
  ): PhaseFailureContinuationOutput?
}

internal interface PhaseAcceptedOutputPersistence {
  fun persistAcceptedOutput(
    recorder: PhaseRunGoal,
    run: PhaseRun,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): String?
}
