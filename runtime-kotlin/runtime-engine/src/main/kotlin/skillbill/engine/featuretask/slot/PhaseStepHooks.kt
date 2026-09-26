package skillbill.engine.featuretask.slot

import skillbill.application.review.service.RuntimeOwnedReviewMode
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.goalreview.ReviewPassResolution
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

/**
 * The launch and output behaviour one strategy step adds to the shared attempt path. The shared launch
 * preparation and output gate ask the strategy of the running step for its hooks, so step-specific evidence,
 * prompt sections, and output checks live with the step instead of in the shared code.
 */
internal interface PhaseStepHooks {
  /** Whether the output gate fingerprints the repository when this step completes. */
  val fingerprintsCompletedRepository: Boolean
    get() = false

  /** The failure disposition a blocked terminal output of this step gets when it names none. */
  val blockedOutputDisposition: FeatureTaskRuntimeFailureDisposition
    get() = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION

  /** The prompt sections appended after the composed launch prompt of [run]. */
  fun launchPromptSupplement(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
    state: PhaseRunState,
  ): String = ""

  /** The repository checkpoint the launch handoff of [run] expects, given the [current] checkpoint fingerprint. */
  fun expectedLaunchCheckpoint(
    run: PhaseRun,
    current: String?,
  ): String? = run.reentry?.expectedRepositoryCheckpoint ?: current

  /** The review pass and review tier the launch prompt of [run] carries. */
  fun launchReviewTier(
    run: PhaseRun,
    state: PhaseRunState,
  ): PhaseLaunchReviewTier =
    PhaseLaunchReviewTier(
      passNumber = null,
      resolution = null,
      executedTier = RuntimeOwnedReviewMode.execute(run.request.runInvariants.codeReviewMode),
    )

  /** The recorded finding verdicts the launch handoff of this step carries. */
  fun handoffFindingVerdicts(state: PhaseRunState): List<ReviewFindingVerdict> = emptyList()

  /** Retains step evidence from [outputText] after the output gate rejected its schema. */
  fun retainSchemaRejectedOutput(
    state: PhaseRunState,
    outputText: String,
  ) = Unit

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
    context: FeatureTaskRuntimeRunLoopContext,
    state: PhaseRunState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck = PhaseStepOutputCheck.Accept

  /** The reason completed [outputMap] of [run] cannot settle, or null when it can. */
  fun completionRejection(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
    state: PhaseRunState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? = null

  /** Settles the step records that completed [outputMap] of [run] carries, once every completion check passed. */
  fun settleCompletedOutput(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
    state: PhaseRunState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck = PhaseStepOutputCheck.Accept

  /** Resets the step state a launch of [run] must not carry over from a prior process. */
  fun onLaunch(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
  ) = Unit

  /**
   * The attempt result completed [outputMap] of [capture] settles to once the step's records are settled: a block or
   * an in-phase retry, or null when the output goes on to acceptance.
   */
  fun settleCompletedRound(
    context: FeatureTaskRuntimeRunLoopContext,
    capture: ValidatedOutputCapture,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AttemptResult? = null

  /**
   * The form of completed [attested] output of [capture] the output gate accepts, given its validated [outputMap]. A
   * step stamps the facts the runtime measured around it here.
   */
  fun acceptedOutput(
    context: FeatureTaskRuntimeRunLoopContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): NormalizedFeatureTaskRuntimePhaseOutput = attested

  /** Records step evidence from accepted [outputMap] of [run] before the completed step is persisted. */
  fun recordAcceptedOutput(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
    state: PhaseRunState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ) = Unit

  /**
   * Settles what completed [output] of this step decides for the rest of the run, once the step is recorded
   * completed: the blocked reason that stops the run, or null when the run continues.
   */
  fun afterCompletion(
    context: FeatureTaskRuntimeRunLoopContext,
    output: FeatureTaskRuntimePhaseOutput,
  ): String? = null

  /** Reconciles the durable state [run] reads before the shared pre-launch checks decide whether it can launch. */
  fun reconcileBeforeLaunch(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
  ) = Unit

  companion object {
    val None: PhaseStepHooks = object : PhaseStepHooks {}
  }
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

  data class Redeliver(val reason: String) : PhaseStepOutputCheck

  data class Block(
    val reason: String,
    val disposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
  ) : PhaseStepOutputCheck

  /** The step keeps repairing in its session; [previousValue] is handed to the next attempt. */
  data class ContinueRepair(val previousValue: String) : PhaseStepOutputCheck

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
  return copy(envelope = envelope, canonicalJson = JsonCodec.mapToJsonString(envelope))
}
