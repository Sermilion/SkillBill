package skillbill.engine.operation.core

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepFacts
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.PhaseStepSession
import skillbill.engine.featuretask.slot.state.PhaseLaunchObservation
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.error.operation.OperationAnchorUnreadableError
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

@Inject
class OperationStepRunner(
  private val runner: PhaseRunner,
  private val gitOperations: WorkflowGitOperations,
) {
  fun runReadOnly(
    context: OperationContext,
    stepName: String,
    directive: String,
    priorValues: Map<String, String> = emptyMap(),
    session: PhaseStepSession? = null,
  ): OperationStepResult {
    val before = fingerprint(context)
    val result = launch(stepName, stepInput(context, stepName, directive, priorValues, READ_ONLY_STEP_POLICY), session)
    if (fingerprint(context) != before) return OperationStepResult.Failed(readOnlyViolation(stepName))
    return result
  }

  fun runEditing(
    context: OperationContext,
    stepName: String,
    directive: String,
    priorValues: Map<String, String>,
  ): OperationStepResult {
    val dirty = dirtyPaths(context)
    val before = contentIdentities(context, dirty)
    val result =
      launch(stepName, stepInput(context, stepName, directive, priorValues, EDITING_STEP_POLICY), session = null)
    if (result !is OperationStepResult.Settled) return result
    val after = contentIdentities(context, dirty)
    val reedited = dirty.filter { path -> before[path] != after[path] }
    return result.copy(changedPaths = (result.changedPaths + reedited).distinct().sorted())
  }

  private fun launch(
    stepName: String,
    input: PhaseStepInput?,
    session: PhaseStepSession?,
  ): OperationStepResult {
    input ?: return OperationStepResult.Failed("Operation step '$stepName' launches an agent; name one with --agent.")
    val output =
      session?.let { runner.run(input, OperationPhaseLaunchState, it) } ?: runner.run(input, OperationPhaseLaunchState)
    return failureOf(stepName, output, input.policy)?.let(OperationStepResult::Failed)
      ?: OperationStepResult.Settled(
        output.value,
        output.fileManifest?.let { manifest -> manifest.after - manifest.before.toSet() }.orEmpty(),
        output,
      )
  }

  private fun stepInput(
    context: OperationContext,
    stepName: String,
    directive: String,
    priorValues: Map<String, String>,
    policy: PhaseStepPolicy,
  ): PhaseStepInput? {
    val agentId = context.invokedAgentId ?: return null
    return PhaseStepInput(
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

  private fun dirtyPaths(context: OperationContext): List<String> =
    when (val status = gitOperations.worktreeStatus(context.repoRoot)) {
      is WorkflowGitOperationResult.Ok -> FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(status.value.orEmpty())
      else -> throw OperationAnchorUnreadableError(WORKTREE_STATUS, status.error)
    }

  private fun contentIdentities(
    context: OperationContext,
    paths: List<String>,
  ): Map<String, String> =
    when (val identities = gitOperations.pathContentIdentities(context.repoRoot, paths)) {
      is WorkflowPathContentIdentitiesResult.Resolved -> identities.identities
      is WorkflowPathContentIdentitiesResult.Failed ->
        throw OperationAnchorUnreadableError(CONTENT_IDENTITIES, identities.error)
    }
}

sealed interface OperationStepResult {
  data class Settled(
    val value: String,
    val changedPaths: List<String> = emptyList(),
    val output: PhaseStepOutput? = null,
  ) : OperationStepResult

  data class Failed(val reason: String) : OperationStepResult
}

private fun readOnlyViolation(stepName: String): String =
  "Operation step '$stepName' is read-only but changed the worktree."

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

private const val TRACKED_ATTEMPT = 1

private const val REPOSITORY_FINGERPRINT = "repository fingerprint"
private const val WORKTREE_STATUS = "worktree status"
private const val CONTENT_IDENTITIES = "dirty file contents"

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
