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
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

/**
 * Runs an operation's agent steps through the generic [PhaseRunner]. Steps settle through their final object;
 * operations have no workflow and record no settlement.
 */
@Inject
class OperationStepRunner(
  private val runner: PhaseRunner,
  private val gitOperations: WorkflowGitOperations,
) {
  /**
   * Runs a step that must not change the worktree. The repository fingerprint is compared before and after, so an
   * edit to an already-dirty file fails the step even though the changed-path manifest stays the same.
   */
  fun runReadOnly(
    context: OperationContext,
    stepName: String,
    directive: String,
    priorValues: Map<String, String> = emptyMap(),
  ): OperationStepResult {
    val before = fingerprint(context)
    val result = launch(context, stepName, directive, priorValues, READ_ONLY_STEP_POLICY)
    if (fingerprint(context) != before) return OperationStepResult.Failed(readOnlyViolation(stepName))
    return result
  }

  /** Runs a step that edits the worktree; only a confirmed proposal's execute calls it. */
  fun runEditing(
    context: OperationContext,
    stepName: String,
    directive: String,
    priorValues: Map<String, String>,
  ): OperationStepResult = launch(context, stepName, directive, priorValues, EDITING_STEP_POLICY)

  private fun launch(
    context: OperationContext,
    stepName: String,
    directive: String,
    priorValues: Map<String, String>,
    policy: PhaseStepPolicy,
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
        policy = policy,
      )
    val output = runner.run(input, OperationPhaseLaunchState)
    return failureOf(stepName, output, policy)?.let(OperationStepResult::Failed)
      ?: OperationStepResult.Settled(output.value)
  }

  private fun failureOf(
    stepName: String,
    output: PhaseStepOutput,
    policy: PhaseStepPolicy,
  ): String? {
    val manifest = output.fileManifest
    return when {
      output.launchFailure != null -> output.launchFailure.reason
      !policy.fileMutating && manifest != null && manifest.before != manifest.after -> readOnlyViolation(stepName)
      output.status in FAILED_STATUSES -> output.summary ?: "Operation step '$stepName' ended ${output.status}."
      output.value.isBlank() -> "Operation step '$stepName' produced no value."
      else -> null
    }
  }

  private fun fingerprint(context: OperationContext): String =
    gitOperations.repositoryFingerprint(context.repoRoot).requireGitValue(REPOSITORY_FINGERPRINT)
}

sealed interface OperationStepResult {
  data class Settled(val value: String) : OperationStepResult

  data class Failed(val reason: String) : OperationStepResult
}

private fun readOnlyViolation(stepName: String): String =
  "Operation step '$stepName' is read-only but changed the worktree."

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

private const val REPOSITORY_FINGERPRINT = "repository fingerprint"

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

private val EDITING_STEP_POLICY =
  PhaseStepPolicy(
    mutating = true,
    relaunchOnInvalidOutput = false,
    singleAgentSession = true,
    readOnlyIdle = false,
    fileMutating = true,
    generationScoped = false,
  )
