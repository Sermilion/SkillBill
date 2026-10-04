package skillbill.engine.featuretask.slot

import skillbill.application.review.service.RuntimeOwnedReviewMode
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptTraversalHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseImplementFixStepBinding
import skillbill.engine.featuretask.slot.state.PhaseReviewPassState
import skillbill.engine.featuretask.slot.state.PhaseStepBinding
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
internal interface PhaseStepHooks : PhaseStepLaunchHooks {
  val contextKind: PhaseStepHookContextKind get() = PhaseStepHookContextKind.COMMON

  /** The reason the strategy cannot proceed with an agent launch after briefing persistence, or null when it can. */
  fun beforeAgentLaunch(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
    state: PhaseStepBinding,
  ): String? = null

  /** Whether the output gate fingerprints the repository when this step completes. */
  val fingerprintsCompletedRepository: Boolean
    get() = false

  /** Whether completed output of this step must carry the projections its immediate consumers read. */
  val checksImmediateConsumerProjection: Boolean
    get() = false

  /** The failure disposition a blocked terminal output of this step gets when it names none. */
  val blockedOutputDisposition: FeatureTaskRuntimeFailureDisposition
    get() = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION

  /**
   * The outcome [outputText] of [run] settles to before the shared output gate decodes it, or null when the shared
   * gate decodes it. A runtime-owned step settles its triage and repair sessions here.
   */
  fun earlyOutput(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
  ): PhaseOutcome? = null

  /** Checks validated [outputMap] of [run] before the shared output checks run. */
  fun checkValidatedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseAcceptedStepExecution,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck = PhaseStepOutputCheck.Accept

  /** The reason completed [outputMap] of [run] cannot settle, or null when it can. */
  fun completionRejection(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseAcceptedStepExecution,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? = null

  /** Settles the step records that completed [outputMap] of [run] carries, once every completion check passed. */
  fun settleCompletedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseAcceptedStepExecution,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck = PhaseStepOutputCheck.Accept

  /**
   * The attempt result completed [outputMap] of [capture] settles to once the step's records are settled: a block or
   * an in-phase retry, or null when the output goes on to acceptance.
   */
  fun settleCompletedRound(
    context: PhaseStepOutputContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AttemptResult? = null

  /**
   * The form of completed [attested] output of [capture] the output gate accepts, given its validated [outputMap]. A
   * step stamps the facts the runtime measured around it here.
   */
  fun acceptedOutput(
    context: PhaseStepOutputContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): NormalizedFeatureTaskRuntimePhaseOutput = attested

  fun interpretedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseAcceptedStepExecution,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput = output

  /** Records step evidence from accepted [outputMap] of [run] before the completed step is persisted. */
  fun recordAcceptedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseStepBinding,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ) = Unit

  /**
   * Settles what completed [output] of this step decides for the rest of the run, once the step is recorded
   * completed: the blocked reason that stops the run, or null when the run continues.
   */
  fun afterCompletion(
    context: PhaseAttemptTraversalHookContext,
    output: FeatureTaskRuntimePhaseOutput,
  ): String? = null

  companion object {
    val None: PhaseStepHooks = object : PhaseStepHooks {}
  }
}

/** The launch behaviour one strategy step adds to the shared launch preparation and pre-launch checks. */
internal interface PhaseStepLaunchHooks {
  /** Whether the launch prompt of this step carries the build command the validation gate declares. */
  val carriesPackBuildCommand: Boolean
    get() = false

  val carriesPackValidationCommand: Boolean
    get() = false

  /** The prompt sections appended after the composed launch prompt of [run]. */
  fun launchPromptSupplement(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
    state: PhaseStepBinding,
  ): String = ""

  /** The repository checkpoint the launch handoff of [run] expects, given the [current] checkpoint fingerprint. */
  fun expectedLaunchCheckpoint(
    run: PhaseRun,
    current: String?,
  ): String? = run.reentry?.expectedRepositoryCheckpoint ?: current

  /** The review pass and review tier the launch prompt of [run] carries. */
  fun launchReviewTier(
    run: PhaseRun,
    state: PhaseReviewPassState,
  ): PhaseLaunchReviewTier =
    PhaseLaunchReviewTier(
      passNumber = null,
      resolution = null,
      executedTier = RuntimeOwnedReviewMode.execute(run.request.runInvariants.codeReviewMode),
    )

  /** The recorded finding verdicts the launch handoff of this step carries. */
  fun handoffFindingVerdicts(state: PhaseImplementFixStepBinding): List<ReviewFindingVerdict> = emptyList()

  /** Resets the step state a launch of [run] must not carry over from a prior process. */
  fun onLaunch(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
  ) = Unit

  /** Reconciles the durable state [run] reads before the shared pre-launch checks decide whether it can launch. */
  fun reconcileBeforeLaunch(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
  ) = Unit
}

internal data class PhaseLaunchReviewTier(
  val passNumber: Int?,
  val resolution: ReviewPassResolution?,
  val executedTier: CodeReviewExecutionMode,
)

/** The verdict of a step's check over its validated output. */
internal sealed interface PhaseStepOutputCheck {
  data object Accept : PhaseStepOutputCheck

  data class Reject(
    val reason: String,
    val rule: String = OUTPUT_VERIFICATION_RULE,
  ) : PhaseStepOutputCheck

  data class Redeliver(
    val reason: String,
  ) : PhaseStepOutputCheck

  data class Block(
    val reason: String,
    val disposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
  ) : PhaseStepOutputCheck

  companion object {
    const val OUTPUT_VERIFICATION_RULE = "output-verification"
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

internal enum class PhaseStepHookContextKind { COMMON, AUDIT, COMMIT, FINDING_VERIFICATION, PULL_REQUEST, PLANNING }
