package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.application.agentoutput.agentFailureExcerpt
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.engine.featuretask.lifecycle.continuation.GoalContinuationStateRecordRequest
import skillbill.engine.featuretask.lifecycle.continuation.agentAttributionFromPhaseState
import skillbill.engine.featuretask.lifecycle.continuation.completedGoalContinuationOutcome
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeSubtaskOutcome
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.goalrunner.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeProviderLimitSignal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitDetector

internal fun terminalBlockedReasonFrom(
  phaseId: String,
  outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  blockedDisposition: FeatureTaskRuntimeFailureDisposition,
): String? {
  val status = outputMap[SharedPayloadKeys.STATUS] as? String
  if (status.workflowStepStatus() != WorkflowStepStatus.BLOCKED &&
    status.workflowStepStatus() != WorkflowStepStatus.FAILED
  ) {
    return null
  }
  val summary = (outputMap[SharedPayloadKeys.SUMMARY] as? String).orEmpty().trim()
  val blockingReasons =
    (outputMap[SharedPayloadKeys.PRODUCED_OUTPUTS] as? Map<*, *>)
      ?.get("blocking_reasons")
      ?.let { value ->
        when (value) {
          is List<*> -> value.mapNotNull { it as? String }
          is String -> listOf(value)
          else -> emptyList()
        }
      }
      .orEmpty()
  val detail =
    (listOf(summary) + blockingReasons)
      .filter(String::isNotBlank)
      .joinToString("; ")
  val disposition = FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(outputMap, blockedDisposition)
  val operatorTerminalQualityGate =
    disposition == FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION &&
      phaseId in PhaseSlot.QUALITY_GATE.steps
  val prefix =
    when {
      operatorTerminalQualityGate -> "Phase output reported status '$status'."
      blockedDisposition == FeatureTaskRuntimeFailureDisposition.RETRYABLE ->
        "Validation phase reported status '$status'; retrying so the agent can fix failures."
      else -> "Phase output reported status '$status'."
    }
  return prefix + detail.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
}

@Inject
class FeatureTaskRuntimeLaunchOutcomes(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val gitOperations: WorkflowGitOperations,
  private val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
) {
  fun finalizingAgentId(request: FeatureTaskRuntimeRunRequest): String? =
    agentAttributionFromPhaseState(recorder.phaseQuery, request.workflowId).finalizingAgentId

  fun persistGoalContinuationOutcome(
    request: FeatureTaskRuntimeRunRequest,
    report: FeatureTaskRuntimeRunReport,
    commitStepId: String,
  ): FeatureTaskRuntimeRunReport {
    val context = request.goalContinuation ?: return report
    val outcome =
      goalContinuationOutcomeFor(request, context, report, commitStepId)?.let { base ->
        val attribution = agentAttributionFromPhaseState(recorder.phaseQuery, request.workflowId)
        base.copy(
          finalizingAgentId = attribution.finalizingAgentId,
          participatingAgentIds = attribution.participatingAgentIds,
        )
      }
    outcome?.let { terminal ->
      goalContinuationRecorder.recordGoalContinuationState(
        request =
          GoalContinuationStateRecordRequest(
            workflowId = request.workflowId,
            outcome =
              FeatureTaskRuntimeGoalContinuationOutcome(
                issueKey = terminal.issueKey,
                subtaskId = terminal.subtaskId,
                status = terminal.status,
                workflowId = terminal.workflowId,
                commitSha = terminal.commitSha,
                blockedReason = terminal.blockedReason,
                lastResumableStep = terminal.lastResumableStep,
                finalizingAgentId = terminal.finalizingAgentId,
                participatingAgentIds = terminal.participatingAgentIds,
              ),
            workflowStatus =
              when (terminal.status) {
                GoalRunnerTerminalStatus.COMPLETE -> "completed"
                GoalRunnerTerminalStatus.PAUSED -> FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
                GoalRunnerTerminalStatus.FAILED,
                GoalRunnerTerminalStatus.BLOCKED,
                GoalRunnerTerminalStatus.TIMEOUT,
                GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME,
                GoalRunnerTerminalStatus.RECONCILABLE,
                -> "blocked"
              },
          ),
      )
    }
    return when {
      report is FeatureTaskRuntimeRunReport.Completed && outcome != null -> report.copy(subtaskOutcome = outcome)
      report is FeatureTaskRuntimeRunReport.Blocked && outcome != null -> report.copy(subtaskOutcome = outcome)
      report is FeatureTaskRuntimeRunReport.Paused && outcome != null -> report.copy(subtaskOutcome = outcome)
      else -> report
    }
  }

  private fun goalContinuationOutcomeFor(
    request: FeatureTaskRuntimeRunRequest,
    context: FeatureTaskRuntimeGoalContinuationContext,
    report: FeatureTaskRuntimeRunReport,
    commitStepId: String,
  ): FeatureTaskRuntimeSubtaskOutcome? =
    when (report) {
      is FeatureTaskRuntimeRunReport.Completed ->
        completedGoalContinuationOutcome(recorder, gitOperations, request, context, commitStepId)
      is FeatureTaskRuntimeRunReport.Blocked ->
        FeatureTaskRuntimeSubtaskOutcome(
          issueKey = context.parentIssueKey,
          subtaskId = context.subtaskId,
          status = GoalRunnerTerminalStatus.BLOCKED,
          commitSha = null,
          workflowId = request.workflowId,
          blockedReason = report.blockedReason,
          lastResumableStep = report.lastIncompletePhase,
        )
      is FeatureTaskRuntimeRunReport.Paused ->
        FeatureTaskRuntimeSubtaskOutcome(
          issueKey = context.parentIssueKey,
          subtaskId = context.subtaskId,
          status = GoalRunnerTerminalStatus.PAUSED,
          commitSha = null,
          workflowId = request.workflowId,
          blockedReason = report.pauseReason,
          lastResumableStep = report.resumableStep,
        )
      is FeatureTaskRuntimeRunReport.Decomposed -> null
    }
}

fun infraFailureReason(
  phaseId: String,
  facts: AgentRunLaunchFacts,
): String? =
  when (val termination = facts.termination) {
    AgentRunTermination.SpawnFailed -> {
      val base = "Feature-task-runtime phase '$phaseId' failed to launch: the agent process could not be spawned."
      val excerpt = agentFailureExcerpt(facts.stderr, facts.stdout, GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS)
      if (excerpt != null) "$base\n$excerpt" else base
    }
    AgentRunTermination.TimedOut ->
      "Feature-task-runtime phase '$phaseId' launch timed out before the agent produced an output."
    AgentRunTermination.Interrupted ->
      "Feature-task-runtime phase '$phaseId' launch was interrupted before completion."
    is AgentRunTermination.Exited ->
      if (termination.code == 0) {
        null
      } else {
        val base = "Feature-task-runtime phase '$phaseId' agent exited with non-zero status ${termination.code}."
        val excerpt = agentFailureExcerpt(facts.stderr, facts.stdout, GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS)
        if (excerpt != null) "$base\n$excerpt" else base
      }
  }

fun providerLimitSignal(facts: AgentRunLaunchFacts): FeatureTaskRuntimeProviderLimitSignal? {
  val exited = facts.termination as? AgentRunTermination.Exited ?: return null
  if (exited.code == 0) return null
  return FeatureTaskRuntimeProviderLimitDetector.detect(facts.stderr, facts.stdout)
}

fun providerLimitPauseReason(
  phaseId: String,
  signal: FeatureTaskRuntimeProviderLimitSignal,
): String {
  val reset = signal.resetHint?.let { " Access resets $it." }.orEmpty()
  return "Feature-task-runtime phase '$phaseId' stopped because the agent provider refused the request at a " +
    "usage limit.$reset The phase produced no output and consumed no repair attempt; the run is paused and " +
    "resumes at '$phaseId'. Provider said: ${signal.evidence}"
}

fun isProcessFailureBlockReason(
  phaseId: String,
  reason: String,
): Boolean =
  reason.startsWith("Feature-task-runtime phase '$phaseId' ") &&
    PROCESS_FAILURE_REASON_MARKERS.any(reason::contains)

private val PROCESS_FAILURE_REASON_MARKERS: List<String> =
  listOf(
    "agent exited with non-zero status",
    "failed to launch:",
    "launch timed out",
    "launch was interrupted",
    "could not launch an agent",
  )

fun phaseDeclaration(
  phaseId: String,
  featureSize: FeatureTaskRuntimeFeatureSize,
  omittedStepIds: Set<String>,
): FeatureTaskRuntimePhaseDeclaration =
  FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclarationWithoutSteps(phaseId, featureSize, omittedStepIds)

fun missingUpstream(
  declaration: FeatureTaskRuntimePhaseDeclaration,
  recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
): List<String>? {
  val resolved =
    FeatureTaskRuntimeHandoffContract
      .resolveUpstreamOutputs(declaration, recordedOutputs)
      .outputsByPhaseId
      .keys
  return declaration.consumedUpstreamPhaseIds.filterNot(resolved::contains).takeIf { it.isNotEmpty() }
}
