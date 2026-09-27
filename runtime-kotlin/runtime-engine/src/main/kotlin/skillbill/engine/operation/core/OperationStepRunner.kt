package skillbill.engine.operation.core

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepFacts
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.state.PhaseLaunchObservation
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

/**
 * Runs an operation's read-only agent steps through the generic [PhaseRunner]. Steps settle through their final
 * object; operations have no workflow and record no settlement.
 */
@Inject
class OperationStepRunner(
  private val runner: PhaseRunner,
) {
  fun runReadOnly(
    context: OperationContext,
    stepName: String,
    directive: String,
    priorValues: Map<String, String> = emptyMap(),
  ): OperationStepResult {
    val agentId =
      context.invokedAgentId
        ?: return OperationStepResult.Failed("Operation step '$stepName' launches an agent; name one with --agent.")
    val input =
      PhaseStepInput(
        stepName = stepName,
        directive = directive,
        priorValues = priorValues,
        operatorInstructions = context.instructions,
        facts =
          PhaseStepFacts(
            issueKey = context.invocationId,
            repoRoot = context.repoRoot,
            timeout = null,
            invokedAgentId = agentId,
            configuredAgentOverrideId = null,
            modelOverride = null,
            effortOverride = null,
            compaction = null,
            attempt = TRACKED_ATTEMPT,
            observeLaunch = false,
            briefingText = directive,
          ),
        policy = READ_ONLY_STEP_POLICY,
      )
    val output = runner.run(input, OperationPhaseLaunchState)
    return failureOf(stepName, output)?.let(OperationStepResult::Failed) ?: OperationStepResult.Settled(output.value)
  }

  private fun failureOf(
    stepName: String,
    output: PhaseStepOutput,
  ): String? {
    val manifest = output.fileManifest
    return when {
      output.launchFailure != null -> output.launchFailure.reason
      manifest != null && manifest.before != manifest.after ->
        "Operation step '$stepName' is read-only but changed the worktree."
      output.status in FAILED_STATUSES -> output.summary ?: "Operation step '$stepName' ended ${output.status}."
      output.value.isBlank() -> "Operation step '$stepName' produced no value."
      else -> null
    }
  }
}

sealed interface OperationStepResult {
  data class Settled(val value: String) : OperationStepResult

  data class Failed(val reason: String) : OperationStepResult
}

/** Operation steps pin no settlement target, observe nothing, and keep no token ledger. */
private object OperationPhaseLaunchState : PhaseLaunchState {
  override fun settlementTarget(attempt: Int): FeatureTaskRuntimePhaseSettlementTarget? = null

  override fun launchObservation(stepName: String): PhaseLaunchObservation =
    PhaseLaunchObservation(AgentRunActivityStampSink.NONE, AgentRunWorktreeEditObserver.NONE)

  override fun recordTokenUsage(
    stepName: String,
    inputTokens: Int,
    outputTokens: Int,
  ) = Unit

  override fun settledEnvelope(
    stepName: String,
    target: FeatureTaskRuntimePhaseSettlementTarget,
  ): PhaseSettledEnvelopeRead = PhaseSettledEnvelopeRead.None
}

/** A non-null attempt makes the runner capture the before and after manifests the read-only check compares. */
private const val TRACKED_ATTEMPT = 1

private val FAILED_STATUSES = setOf("blocked", "failed")

private val READ_ONLY_STEP_POLICY =
  PhaseStepPolicy(
    mutating = false,
    relaunchOnInvalidOutput = false,
    singleAgentSession = true,
    readOnlyIdle = true,
    fileMutating = false,
    generationScoped = false,
  )
