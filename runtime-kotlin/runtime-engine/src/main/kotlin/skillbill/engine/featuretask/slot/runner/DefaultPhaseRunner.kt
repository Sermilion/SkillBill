package skillbill.engine.featuretask.slot.runner

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.engine.featuretask.runner.infraFailureReason
import skillbill.engine.featuretask.runner.providerLimitPauseReason
import skillbill.engine.featuretask.runner.providerLimitSignal
import skillbill.engine.featuretask.slot.PhaseLaunchFailure
import skillbill.engine.featuretask.slot.PhaseLaunchFailureKind
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepFileManifest
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.PhaseStepSession
import skillbill.engine.featuretask.slot.PhaseStepStdout
import skillbill.engine.featuretask.slot.state.PhaseLaunchObservation
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.engine.featuretask.validation.check.ValidationLiveChecks
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunApprovalRequirement
import skillbill.ports.agentrun.model.AgentRunLaunchDenied
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunMcpStartupProbe
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.ports.agentrun.model.READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import skillbill.telemetry.estimation.estimateTokens
import kotlin.time.Duration.Companion.minutes

public class DefaultPhaseRunner(
  private val launcher: GoalRunnerSubtaskLauncher,
  private val gitOperations: WorkflowGitOperations,
  private val liveChecks: ValidationLiveChecks? = null,
) : PhaseRunner {
  override fun run(
    input: PhaseStepInput,
    state: PhaseLaunchState,
  ): PhaseStepOutput = run(input, state, AgentRunOutputSink.NONE, false)

  override fun run(
    input: PhaseStepInput,
    state: PhaseLaunchState,
    outputSink: AgentRunOutputSink,
    captureStreamOutput: Boolean,
  ): PhaseStepOutput = run(input, state, PhaseStepSession(launcher::launch), outputSink, captureStreamOutput)

  override fun run(
    input: PhaseStepInput,
    state: PhaseLaunchState,
    session: PhaseStepSession,
  ): PhaseStepOutput = run(input, state, session, AgentRunOutputSink.NONE, false)

  override fun run(
    input: PhaseStepInput,
    state: PhaseLaunchState,
    session: PhaseStepSession,
    outputSink: AgentRunOutputSink,
    captureStreamOutput: Boolean,
  ): PhaseStepOutput {
    val step = input.stepName
    val attempt = input.facts.attempt ?: return launchUntracked(input, state, session, outputSink, captureStreamOutput)
    val before =
      when (val captured = captureBefore(input)) {
        is BeforeCapture.Ready -> captured
        is BeforeCapture.Failed ->
          return notLaunched(
            PhaseLaunchFailure(PhaseLaunchFailureKind.BEFORE_CAPTURE_FAILED, captureReason(step, captured.detail)),
          )
      }
    val prepared = state.prepareLaunch(input) ?: return preparationRejected(step)
    markLiveChecks(prepared)
    val outcome = session.execute(launchRequest(prepared, state, outputSink, captureStreamOutput))
    return launchPrepared(prepared, state, attempt, before, outcome)
  }

  private fun launchPrepared(
    input: PhaseStepInput,
    state: PhaseLaunchState,
    attempt: Int,
    before: BeforeCapture.Ready,
    outcome: AgentRunLaunchOutcome,
  ): PhaseStepOutput {
    val step = input.stepName
    if (outcome is AgentRunLaunchFacts) {
      state.recordTokenUsage(step, estimateTokens(input.facts.briefingText), estimateTokens(outcome.stdout))
    }
    val settled =
      state.settlementTarget(attempt)?.let { target -> state.settledEnvelope(step, target) }
        ?: PhaseSettledEnvelopeRead.NotRequired
    val manifest =
      when (val captured = captureAfter(input, before)) {
        is AfterCapture.Ready -> captured.manifest
        is AfterCapture.Failed ->
          return launched(
            outcome,
            null,
            settled,
            PhaseLaunchFailure(PhaseLaunchFailureKind.AFTER_CAPTURE_FAILED, captureReason(step, captured.detail)),
          )
      }
    return launched(outcome, manifest, settled, classify(input, outcome))
  }

  private fun launchUntracked(
    unprepared: PhaseStepInput,
    state: PhaseLaunchState,
    session: PhaseStepSession,
    outputSink: AgentRunOutputSink,
    captureStreamOutput: Boolean,
  ): PhaseStepOutput {
    val input = state.prepareLaunch(unprepared) ?: return preparationRejected(unprepared.stepName)
    markLiveChecks(input)
    val outcome = session.execute(launchRequest(input, state, outputSink, captureStreamOutput))
    return launched(outcome, null, PhaseSettledEnvelopeRead.NotRequired, classify(input, outcome))
  }

  private fun launchRequest(
    input: PhaseStepInput,
    state: PhaseLaunchState,
    outputSink: AgentRunOutputSink,
    captureStreamOutput: Boolean,
  ): GoalRunnerSubtaskLaunchRequest {
    val facts = input.facts
    val readOnly = input.policy.readOnlyIdle
    val launchObservation = state.launchObservation(input.stepName)
    val observation =
      if (facts.observeLaunch) {
        launchObservation
      } else {
        PhaseLaunchObservation(
          AgentRunActivityStampSink.NONE,
          AgentRunWorktreeEditObserver.NONE,
          buildObserver = launchObservation.buildObserver,
          modelObserver = launchObservation.modelObserver,
        )
      }
    val liveChecksInvocation = facts.liveChecks?.takeIf { it.agentCapable }?.invocationId
    return GoalRunnerSubtaskLaunchRequest(
      invokedAgentId = facts.invokedAgentId,
      configuredAgentOverrideId = facts.configuredAgentOverrideId,
      skillRunRequest =
        SkillRunRequest(
          issueKey = facts.issueKey,
          repoRoot = facts.agentWorkingDirectory ?: facts.repoRoot,
          additionalWritableDirectories = facts.additionalWritableDirectories,
          subtaskId = facts.subtaskId,
          timeout = facts.timeout,
          modelObserver = observation.modelObserver,
          modelOverride = facts.modelOverride,
          effortOverride = facts.effortOverride,
          compaction = facts.compaction,
          outputSink = outputSink,
          promptOverride = composePrompt(input),
          streamProviderOutput = captureStreamOutput || observation.modelObserver != null,
          streamOutputForLiveness = facts.streamOutputForLiveness || captureStreamOutput,
          captureDisplayOutput = captureStreamOutput || observation.modelObserver != null,
          readOnlyPhase = readOnly,
          progressIdleTimeout =
            if (readOnly) READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES.minutes else facts.progressIdleTimeout,
          spawnAuthorization = facts.spawnAuthorization,
          approval = AgentRunApprovalRequirement.OutsideApproval,
          activityStampSink = observation.activityStampSink,
          worktreeEditObserver = observation.worktreeEditObserver,
          buildObserver = observation.buildObserver,
          progressEmitter =
            observation.progressEmitter?.let { callback -> AgentRunProgressEmitter(callback) }
              ?: AgentRunProgressEmitter.NONE,
          validationCheckInvocationId = liveChecksInvocation,
          mcpStartupProbe = liveChecksInvocation?.let { liveChecks?.startupProbe(it) } ?: AgentRunMcpStartupProbe.NONE,
        ),
    )
  }

  private fun markLiveChecks(input: PhaseStepInput) {
    input.facts.liveChecks?.let { liveChecks?.beforeLaunch(it.invocationId, it.agentCapable) }
  }

  private fun composePrompt(input: PhaseStepInput): String {
    val instructions = input.operatorInstructions?.takeIf(String::isNotBlank)
    if (input.priorValues.isEmpty() && instructions == null) return input.directive
    return buildString {
      append(input.directive)
      input.priorValues.forEach { (step, value) ->
        appendLine()
        appendLine()
        appendLine("## Prior step value: $step")
        append(value)
      }
      instructions?.let {
        appendLine()
        appendLine()
        appendLine("## Operator instructions")
        append(it)
      }
    }
  }

  private fun captureBefore(input: PhaseStepInput): BeforeCapture {
    val status = gitOperations.worktreeStatus(input.facts.repoRoot)
    if (status !is WorkflowGitOperationResult.Ok) return BeforeCapture.Failed("before-file manifest: ${status.error}")
    val commit = gitOperations.runtimePhaseHeadCommit(input.facts.repoRoot)
    if (commit !is WorkflowGitOperationResult.Ok) return BeforeCapture.Failed("before commit: ${commit.error}")
    val statusText = status.value.orEmpty()
    val paths =
      (
        FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(statusText) +
          FeatureTaskRuntimePhaseSafetyPolicy.deletedPaths(statusText)
      ).distinct().sorted()
    val identities =
      if (input.policy.mutating) {
        when (val read = gitOperations.pathContentIdentities(input.facts.repoRoot, paths)) {
          is WorkflowPathContentIdentitiesResult.Resolved -> read.identities
          is WorkflowPathContentIdentitiesResult.Failed ->
            return BeforeCapture.Failed("before content identities: ${read.error}")
        }
      } else {
        emptyMap()
      }
    return BeforeCapture.Ready(
      statusText,
      commit.value.orEmpty(),
      if (input.policy.mutating) paths else emptyList(),
      identities,
    )
  }

  private fun captureAfter(
    input: PhaseStepInput,
    before: BeforeCapture.Ready,
  ): AfterCapture {
    val repoRoot = input.facts.repoRoot
    val status = gitOperations.worktreeStatus(repoRoot)
    if (status !is WorkflowGitOperationResult.Ok) return AfterCapture.Failed("after-file manifest: ${status.error}")
    val commit = gitOperations.runtimePhaseHeadCommit(repoRoot)
    if (commit !is WorkflowGitOperationResult.Ok) return AfterCapture.Failed("after commit: ${commit.error}")
    val committed =
      when (
        val changes =
          gitOperations.runtimePhaseChangedPathsBetweenCommits(
            repoRoot,
            before.commit,
            commit.value.orEmpty(),
          )
      ) {
        is WorkflowGitNameListResult.Listed -> changes.names
        is WorkflowGitNameListResult.Failed -> return AfterCapture.Failed("committed file changes: ${changes.error}")
      }
    val afterIdentities =
      if (before.dirtyPaths.isEmpty()) {
        WorkflowPathContentIdentitiesResult.Resolved(emptyMap())
      } else {
        gitOperations.pathContentIdentities(repoRoot, before.dirtyPaths)
      }
    return when (afterIdentities) {
      is WorkflowPathContentIdentitiesResult.Failed ->
        AfterCapture.Failed("after content identities: ${afterIdentities.error}")
      is WorkflowPathContentIdentitiesResult.Resolved ->
        AfterCapture.Ready(
          PhaseStepFileManifest(
            before =
              (
                FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(before.status) +
                  FeatureTaskRuntimePhaseSafetyPolicy.deletedPaths(before.status)
              ).distinct().sorted(),
            after =
              (
                FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(status.value) +
                  FeatureTaskRuntimePhaseSafetyPolicy.deletedPaths(status.value) + committed
              ).distinct().sorted(),
            reeditedPaths =
              before.dirtyPaths.filter { path ->
                before.contentIdentities[path] != afterIdentities.identities[path]
              },
          ),
        )
    }
  }

  private fun classify(
    input: PhaseStepInput,
    outcome: AgentRunLaunchOutcome,
  ): PhaseLaunchFailure? {
    val step = input.stepName
    return when (outcome) {
      is UnsupportedAgentRunLaunch ->
        PhaseLaunchFailure(
          PhaseLaunchFailureKind.UNSUPPORTED_AGENT,
          "Feature-task-runtime phase '$step' could not launch an agent: ${outcome.reason}",
          outcome.reason,
        )
      is AgentRunLaunchDenied -> null
      is AgentRunLaunchFacts -> {
        val agentId =
          input.facts.invokedAgentId.takeIf(String::isNotBlank) ?: outcome.agent.wireValue
        providerLimitSignal(outcome)
          ?.let {
            PhaseLaunchFailure(
              PhaseLaunchFailureKind.PROVIDER_LIMIT,
              providerLimitPauseReason(step, agentId, it),
            )
          }
          ?: infraFailureReason(step, outcome)?.let { PhaseLaunchFailure(PhaseLaunchFailureKind.INFRASTRUCTURE, it) }
      }
    }
  }

  private fun launched(
    outcome: AgentRunLaunchOutcome,
    manifest: PhaseStepFileManifest?,
    settled: PhaseSettledEnvelopeRead,
    failure: PhaseLaunchFailure?,
  ): PhaseStepOutput {
    val facts = outcome as? AgentRunLaunchFacts
    val stdout =
      facts?.let {
        PhaseStepStdout(
          text = it.stdout,
          bytes = it.stdout.encodeToByteArray(),
          truncated = it.stdoutTruncated,
          byteSize = it.stdoutByteSize,
          sha256 = it.stdoutSha256,
        )
      } ?: EMPTY_STDOUT
    return PhaseStepOutput(
      stdout = stdout,
      stderr = facts?.stderr.orEmpty(),
      processStarted = facts?.processStarted ?: false,
      termination = facts?.termination,
      fileManifest = manifest,
      settledEnvelope = settled,
      launchFailure = failure,
      launchOutcome = outcome,
    )
  }

  private fun notLaunched(failure: PhaseLaunchFailure) =
    PhaseStepOutput(
      stdout = EMPTY_STDOUT,
      stderr = "",
      processStarted = false,
      termination = null,
      fileManifest = null,
      settledEnvelope = PhaseSettledEnvelopeRead.None,
      launchFailure = failure,
    )

  private fun preparationRejected(step: String) =
    notLaunched(
      PhaseLaunchFailure(
        PhaseLaunchFailureKind.PREPARATION_REJECTED,
        "Feature-task-runtime phase '$step' launch preparation rejected the launch",
      ),
    )

  private fun captureReason(
    step: String,
    detail: String,
  ) = "Feature-task-runtime phase '$step' could not capture its $detail"

  private sealed interface BeforeCapture {
    data class Ready(
      val status: String,
      val commit: String,
      val dirtyPaths: List<String>,
      val contentIdentities: Map<String, String>,
    ) : BeforeCapture

    data class Failed(val detail: String) : BeforeCapture
  }

  private sealed interface AfterCapture {
    data class Ready(val manifest: PhaseStepFileManifest) : AfterCapture

    data class Failed(val detail: String) : AfterCapture
  }

  private companion object {
    val EMPTY_STDOUT = PhaseStepStdout("", ByteArray(0), truncated = false, byteSize = 0L, sha256 = "")
  }
}
