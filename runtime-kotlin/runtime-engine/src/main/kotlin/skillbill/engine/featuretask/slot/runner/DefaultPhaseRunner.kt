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
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunLaunchDenied
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.ports.agentrun.model.READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.telemetry.estimation.estimateTokens
import kotlin.time.Duration.Companion.minutes

class DefaultPhaseRunner(
  private val launcher: GoalRunnerSubtaskLauncher,
  private val gitOperations: WorkflowGitOperations,
) : PhaseRunner {
  override fun run(
    input: PhaseStepInput,
    state: PhaseLaunchState,
  ): PhaseStepOutput = run(input, state, PhaseStepSession(launcher::launch))

  override fun run(
    input: PhaseStepInput,
    state: PhaseLaunchState,
    session: PhaseStepSession,
  ): PhaseStepOutput {
    val step = input.stepName
    val attempt = input.facts.attempt ?: return launchUntracked(input, state, session)
    val before =
      when (val captured = captureBefore(input)) {
        is BeforeCapture.Ready -> captured
        is BeforeCapture.Failed ->
          return notLaunched(
            PhaseLaunchFailure(PhaseLaunchFailureKind.BEFORE_CAPTURE_FAILED, captureReason(step, captured.detail)),
          )
      }
    val prepared = state.prepareLaunch(input) ?: return preparationRejected(step)
    return launchPrepared(prepared, state, attempt, before, session)
  }

  private fun launchPrepared(
    input: PhaseStepInput,
    state: PhaseLaunchState,
    attempt: Int,
    before: BeforeCapture.Ready,
    session: PhaseStepSession,
  ): PhaseStepOutput {
    val step = input.stepName
    val outcome = session.execute(launchRequest(input, state))
    if (outcome is AgentRunLaunchFacts) {
      state.recordTokenUsage(step, estimateTokens(input.facts.briefingText), estimateTokens(outcome.stdout))
    }
    val settled =
      state.settlementTarget(attempt)?.let { target -> state.settledEnvelope(step, target) }
        ?: PhaseSettledEnvelopeRead.None
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
    return launched(outcome, manifest, settled, classify(step, outcome))
  }

  private fun launchUntracked(
    unprepared: PhaseStepInput,
    state: PhaseLaunchState,
    session: PhaseStepSession,
  ): PhaseStepOutput {
    val input = state.prepareLaunch(unprepared) ?: return preparationRejected(unprepared.stepName)
    val outcome = session.execute(launchRequest(input, state))
    return launched(outcome, null, PhaseSettledEnvelopeRead.None, classify(input.stepName, outcome))
  }

  private fun launchRequest(
    input: PhaseStepInput,
    state: PhaseLaunchState,
  ): GoalRunnerSubtaskLaunchRequest {
    val facts = input.facts
    val readOnly = input.policy.readOnlyIdle
    val observation =
      if (facts.observeLaunch) {
        state.launchObservation(input.stepName)
      } else {
        PhaseLaunchObservation(AgentRunActivityStampSink.NONE, AgentRunWorktreeEditObserver.NONE)
      }
    return GoalRunnerSubtaskLaunchRequest(
      invokedAgentId = facts.invokedAgentId,
      configuredAgentOverrideId = facts.configuredAgentOverrideId,
      skillRunRequest =
        SkillRunRequest(
          issueKey = facts.issueKey,
          repoRoot = facts.repoRoot,
          subtaskId = facts.subtaskId,
          timeout = facts.timeout,
          modelOverride = facts.modelOverride,
          effortOverride = facts.effortOverride,
          compaction = facts.compaction,
          outputSink = facts.outputSink,
          promptOverride = composePrompt(input),
          streamOutputForLiveness = facts.streamOutputForLiveness,
          readOnlyPhase = readOnly,
          progressIdleTimeout =
            if (readOnly) READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES.minutes else facts.progressIdleTimeout,
          spawnAuthorization = facts.spawnAuthorization,
          activityStampSink = observation.activityStampSink,
          worktreeEditObserver = observation.worktreeEditObserver,
        ),
    )
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
    if (commit !is WorkflowGitOperationResult.Ok) return BeforeCapture.Failed("before commit")
    return BeforeCapture.Ready(status.value.orEmpty(), commit.value.orEmpty())
  }

  private fun captureAfter(
    input: PhaseStepInput,
    before: BeforeCapture.Ready,
  ): AfterCapture {
    val repoRoot = input.facts.repoRoot
    val status = gitOperations.worktreeStatus(repoRoot)
    if (status !is WorkflowGitOperationResult.Ok) return AfterCapture.Failed("after-file manifest")
    val commit = gitOperations.runtimePhaseHeadCommit(repoRoot)
    if (commit !is WorkflowGitOperationResult.Ok) return AfterCapture.Failed("after commit")
    val committed =
      gitOperations.runtimePhaseChangedPathsBetweenCommits(repoRoot, before.commit, commit.value.orEmpty())
    if (committed !is WorkflowGitNameListResult.Listed) return AfterCapture.Failed("committed file changes")
    return AfterCapture.Ready(
      PhaseStepFileManifest(
        before = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(before.status),
        after = (FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(status.value) + committed.names).distinct().sorted(),
      ),
    )
  }

  private fun classify(
    step: String,
    outcome: AgentRunLaunchOutcome,
  ): PhaseLaunchFailure? =
    when (outcome) {
      is UnsupportedAgentRunLaunch ->
        PhaseLaunchFailure(
          PhaseLaunchFailureKind.UNSUPPORTED_AGENT,
          "Feature-task-runtime phase '$step' could not launch an agent: ${outcome.reason}",
          outcome.reason,
        )
      is AgentRunLaunchDenied -> null
      is AgentRunLaunchFacts ->
        providerLimitSignal(outcome)
          ?.let { PhaseLaunchFailure(PhaseLaunchFailureKind.PROVIDER_LIMIT, providerLimitPauseReason(step, it)) }
          ?: infraFailureReason(step, outcome)?.let { PhaseLaunchFailure(PhaseLaunchFailureKind.INFRASTRUCTURE, it) }
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
    data class Ready(val status: String, val commit: String) : BeforeCapture

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
