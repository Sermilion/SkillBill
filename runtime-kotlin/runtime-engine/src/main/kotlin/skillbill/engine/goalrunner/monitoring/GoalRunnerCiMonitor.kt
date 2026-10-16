package skillbill.engine.goalrunner.monitoring

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goalrunner.model.GoalProgressEventDraft
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.net.URI
import java.time.Clock

fun interface GoalRunnerCiMonitor {
  fun monitor(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): PhaseRunResult
}

@Inject
class DefaultGoalRunnerCiMonitor(
  private val phaseRunEntry: PhaseRunEntry,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val clock: Clock,
) : GoalRunnerCiMonitor {
  override fun monitor(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): PhaseRunResult =
    phaseRunEntry.runForGoal(
      PhaseRunRequest(
        definitionId = SkeletonDefinition.MONITOR.id,
        repoRoot = request.repoRoot,
        invokedAgentId = request.configuredAgentOverrideId ?: request.invokedAgentId,
        intake = request.issueKey,
        timeout = request.timeout,
        agentAddonSelection = request.agentAddonSelection,
        modelAssignment = request.modelAssignment,
        eventSink = FeatureTaskRuntimeRunEventSink { event -> record(state, request, event) },
        activityOutput = { stepName, activity -> recordActivity(state, request, stepName, activity) },
      ),
      state.parentWorkflowId,
      state.manifest.featureBranch.orEmpty(),
    )

  private fun record(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    event: FeatureTaskRuntimeRunEvent,
  ) {
    if (event is FeatureTaskRuntimeRunEvent.CiStillRunning) {
      val activity = ciWaitingActivity(event)
      recordProgress(
        state,
        request,
        phaseId = event.phaseId,
        kind = GoalProgressEventKind.OPERATION_HEARTBEAT,
        operationName = ciWaitingOperationName(event),
        expectedLong = true,
      )
      request.outputSink.write(AgentRunOutputStream.STDERR, "$activity\n")
      return
    }
    val kind =
      when (event) {
        is FeatureTaskRuntimeRunEvent.PhaseStarted -> GoalProgressEventKind.PHASE_STARTED
        is FeatureTaskRuntimeRunEvent.PhaseCompleted,
        is FeatureTaskRuntimeRunEvent.PhaseBlocked,
        is FeatureTaskRuntimeRunEvent.PhasePaused,
        -> GoalProgressEventKind.PHASE_COMPLETED
        else -> return
      }
    val outcome =
      when (event) {
        is FeatureTaskRuntimeRunEvent.PhaseCompleted -> GoalProgressOutcome.SUCCEEDED
        is FeatureTaskRuntimeRunEvent.PhaseBlocked -> GoalProgressOutcome.FAILED
        is FeatureTaskRuntimeRunEvent.PhasePaused -> GoalProgressOutcome.CANCELLED
        else -> GoalProgressOutcome.NONE
      }
    recordProgress(state, request, event.phaseId, kind, outcome = outcome)
    request.outputSink.write(AgentRunOutputStream.STDERR, "${event.phaseId}: ${kind.wireValue}\n")
  }

  private fun recordActivity(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    stepName: String,
    activity: String,
  ) {
    recordProgress(
      state,
      request,
      phaseId = stepName,
      kind = GoalProgressEventKind.OPERATION_HEARTBEAT,
      operationName = activity,
      expectedLong = true,
    )
    request.outputSink.write(AgentRunOutputStream.STDERR, "$activity\n")
  }

  private fun recordProgress(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    phaseId: String,
    kind: GoalProgressEventKind,
    operationName: String? = null,
    expectedLong: Boolean = false,
    outcome: GoalProgressOutcome = GoalProgressOutcome.NONE,
  ) {
    val persisted =
      outcomeStore.recordProgressEvent(
        GoalRunnerProgressEventRecordRequest(
          workflowId = state.parentWorkflowId,
          issueKey = request.issueKey,
          draft =
            GoalProgressEventDraft(
              eventKind = kind,
              workflowId = state.parentWorkflowId,
              workflowPhase = phaseId,
              processAlive = true,
              timestamp = clock.instant(),
              stepId = phaseId,
              operationName = operationName?.take(MAX_OPERATION_NAME_LENGTH),
              operationKind = GOAL_FINALIZATION_OPERATION_KIND,
              expectedLong = expectedLong,
              outcome = outcome,
            ),
        ),
      )
    check(persisted) { "Goal monitoring could not record progress for '${state.parentWorkflowId}'." }
  }

  private fun ciWaitingOperationName(event: FeatureTaskRuntimeRunEvent.CiStillRunning): String =
    if (event.pendingChecks.isEmpty()) {
      "Waiting for CI check registration"
    } else {
      val names =
        event.pendingChecks
          .take(MAX_VISIBLE_CHECKS)
          .joinToString(", ") { check -> check.name.filterNot(Char::isISOControl).take(MAX_CHECK_NAME_LENGTH) }
      "Waiting for CI checks: $names"
    }

  private fun ciWaitingActivity(event: FeatureTaskRuntimeRunEvent.CiStillRunning): String {
    val pullRequest = safeHttpLink(event.pullRequestUrl)
    val checks =
      event.pendingChecks
        .take(MAX_VISIBLE_CHECKS)
        .joinToString(", ") { check ->
          val name = check.name.filterNot(Char::isISOControl).take(MAX_CHECK_NAME_LENGTH)
          listOf(name, safeHttpLink(check.link)).filter(String::isNotBlank).joinToString(" ")
        }
    val detail = if (checks.isBlank()) "waiting for checks to be reported" else "waiting for $checks"
    return listOf("CI is $detail", pullRequest)
      .filter(String::isNotBlank)
      .joinToString("; ")
      .take(MAX_CI_ACTIVITY_LENGTH)
  }

  private fun safeHttpLink(value: String): String =
    runCatching {
      val uri = URI(value)
      val scheme = uri.scheme?.takeIf { it == "http" || it == "https" }
      val host = uri.host?.takeIf(String::isNotBlank)
      if (scheme == null || host == null || uri.userInfo != null) {
        ""
      } else {
        URI(scheme, null, host, uri.port, uri.path, null, null).toASCIIString().take(MAX_PROGRESS_LINK_LENGTH)
      }
    }.getOrDefault("")
}

const val GOAL_FINALIZATION_OPERATION_KIND: String = "goal_finalization"
private const val MAX_OPERATION_NAME_LENGTH = 160
private const val MAX_VISIBLE_CHECKS = 4
private const val MAX_CHECK_NAME_LENGTH = 48
private const val MAX_CI_ACTIVITY_LENGTH = 500
private const val MAX_PROGRESS_LINK_LENGTH = 120
