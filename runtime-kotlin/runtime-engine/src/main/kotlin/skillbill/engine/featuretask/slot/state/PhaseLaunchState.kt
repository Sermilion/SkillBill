package skillbill.engine.featuretask.slot.state

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunBuildObserver
import skillbill.ports.agentrun.model.AgentRunModelObserver
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

/**
 * The run state a [PhaseRunner] reads and writes around one launch: the settlement target a step attempt pins,
 * launch observation, token accounting, and the settled envelope an attempt recorded.
 */
public interface PhaseLaunchState {
  /** The prompt section appended to every launch of this state; a fan-out unit names the work it owns here. */
  public val launchPromptContext: String

  /**
   * Prepares [input] for launch once the runner has captured the before-state. Returns the input to launch, or
   * null when preparation rejected the launch and the step must not launch.
   */
  public fun prepareLaunch(input: PhaseStepInput): PhaseStepInput? = input

  /**
   * The settlement target the step prompt pins for [attempt], or null when the state records no settlements and the
   * step settles through its final object.
   */
  public fun settlementTarget(attempt: Int): FeatureTaskRuntimePhaseSettlementTarget?

  /** The activity, worktree-edit and build observation attached to a launch of [stepName]. */
  public fun launchObservation(stepName: String): PhaseLaunchObservation

  /** Records the estimated token usage of one launch of [stepName]. */
  public fun recordTokenUsage(
    stepName: String,
    inputTokens: Int,
    outputTokens: Int,
  )

  /** Reads the envelope settled for [stepName] at [target], if any. */
  public fun settledEnvelope(
    stepName: String,
    target: FeatureTaskRuntimePhaseSettlementTarget,
  ): PhaseSettledEnvelopeRead
}

public data class PhaseLaunchObservation(
  val activityStampSink: AgentRunActivityStampSink,
  val worktreeEditObserver: AgentRunWorktreeEditObserver,
  val progressEmitter: ((AgentRunProgressEmission) -> Unit)? = null,
  val buildObserver: AgentRunBuildObserver = AgentRunBuildObserver.NONE,
  val modelObserver: AgentRunModelObserver? = null,
)

public sealed interface PhaseSettledEnvelopeRead {
  public data object NotRequired : PhaseSettledEnvelopeRead

  public data object None : PhaseSettledEnvelopeRead

  public data class Found(val envelope: FeatureTaskRuntimeWorkflowArtifactMap) : PhaseSettledEnvelopeRead

  public data class Failed(val error: SkillBillRuntimeException) : PhaseSettledEnvelopeRead
}
