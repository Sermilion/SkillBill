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
    val before = fingerprint(context) { return OperationStepResult.Refused(it) }
    val result = launch(stepName, stepInput(context, stepName, directive, priorValues, READ_ONLY_STEP_POLICY), session)
    if (fingerprint(context) { return OperationStepResult.Refused(it) } != before) {
      return OperationStepResult.Failed(readOnlyViolation(stepName))
    }
    return result
  }

  fun runEditing(
    context: OperationContext,
    stepName: String,
    directive: String,
    priorValues: Map<String, String>,
  ): OperationStepResult {
    val dirty = dirtyPaths(context) { return OperationStepResult.Refused(it) }
    val before = contentIdentities(context, dirty) { return OperationStepResult.Refused(it) }
    val result =
      launch(stepName, stepInput(context, stepName, directive, priorValues, EDITING_STEP_POLICY), session = null)
    return if (result is OperationStepResult.Settled) withReeditedPaths(context, dirty, before, result) else result
  }

  private fun withReeditedPaths(
    context: OperationContext,
    dirty: List<String>,
    before: Map<String, String>,
    result: OperationStepResult.Settled,
  ): OperationStepResult {
    val after = contentIdentities(context, dirty) { return OperationStepResult.Refused(it) }
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
        output.stdout.text,
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
      output.stdout.text.isBlank() -> "Operation step '$stepName' produced no value."
      else -> null
    }
  }

  private inline fun fingerprint(
    context: OperationContext,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ): String = gitOperations.repositoryFingerprint(context.repoRoot).gitValueOr(REPOSITORY_FINGERPRINT, refuse)

  private inline fun dirtyPaths(
    context: OperationContext,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ): List<String> =
    when (val status = gitOperations.worktreeStatus(context.repoRoot)) {
      is WorkflowGitOperationResult.Ok -> FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(status.value.orEmpty())
      else -> refuse(anchorUnreadable(WORKTREE_STATUS, status.error))
    }

  private inline fun contentIdentities(
    context: OperationContext,
    paths: List<String>,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ): Map<String, String> =
    when (val identities = gitOperations.pathContentIdentities(context.repoRoot, paths)) {
      is WorkflowPathContentIdentitiesResult.Resolved -> identities.identities
      is WorkflowPathContentIdentitiesResult.Failed -> refuse(anchorUnreadable(CONTENT_IDENTITIES, identities.error))
    }
}

sealed interface OperationStepResult {
  data class Settled(
    val value: String,
    val changedPaths: List<String> = emptyList(),
    val output: PhaseStepOutput? = null,
  ) : OperationStepResult

  data class Failed(val reason: String) : OperationStepResult

  /** The step could not start or finish because an operation anchor was unreadable; nothing was reported as failed. */
  data class Refused(val refusal: OperationOutcome.Blocked) : OperationStepResult
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

private val READ_ONLY_STEP_POLICY =
  PhaseStepPolicy(
    mutating = false,
    singleAgentSession = true,
    readOnlyIdle = true,
    fileMutating = false,
    generationScoped = false,
  )

private val EDITING_STEP_POLICY =
  PhaseStepPolicy(
    mutating = true,
    singleAgentSession = true,
    readOnlyIdle = false,
    fileMutating = true,
    generationScoped = false,
  )
