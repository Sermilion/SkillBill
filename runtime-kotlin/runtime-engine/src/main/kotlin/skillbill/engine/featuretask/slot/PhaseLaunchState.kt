package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

/**
 * The run state a [PhaseRunner] reads and writes around one launch: the settlement target a step attempt pins,
 * launch observation, token accounting, and the settled envelope an attempt recorded.
 */
interface PhaseLaunchState {
  /**
   * Prepares [input] for launch once the runner has captured the before-state. Returns the input to launch, or
   * null when preparation rejected the launch and the step must not launch.
   */
  fun prepareLaunch(input: PhaseStepInput): PhaseStepInput? = input

  /**
   * The settlement target the step prompt pins for [attempt], or null when the state records no settlements and the
   * step settles through its final object.
   */
  fun settlementTarget(attempt: Int): FeatureTaskRuntimePhaseSettlementTarget?

  /** The activity and worktree-edit observation attached to a launch of [stepName]. */
  fun launchObservation(stepName: String): PhaseLaunchObservation

  /** Records the estimated token usage of one launch of [stepName]. */
  fun recordTokenUsage(
    stepName: String,
    inputTokens: Int,
    outputTokens: Int,
  )

  /** Reads the envelope settled for [stepName] at [target], if any. */
  fun settledEnvelope(
    stepName: String,
    target: FeatureTaskRuntimePhaseSettlementTarget,
  ): PhaseSettledEnvelopeRead
}

data class PhaseLaunchObservation(
  val activityStampSink: AgentRunActivityStampSink,
  val worktreeEditObserver: AgentRunWorktreeEditObserver,
)

sealed interface PhaseSettledEnvelopeRead {
  data object None : PhaseSettledEnvelopeRead

  data class Found(val envelope: FeatureTaskRuntimeWorkflowArtifactMap) : PhaseSettledEnvelopeRead

  data class Failed(val error: InvalidFeatureTaskRuntimeValidationEvidenceSchemaError) : PhaseSettledEnvelopeRead
}
