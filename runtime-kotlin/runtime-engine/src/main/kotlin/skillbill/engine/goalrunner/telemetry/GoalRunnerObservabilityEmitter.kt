package skillbill.engine.goalrunner.telemetry

import skillbill.engine.agentoutput.stderrExcerpt
import skillbill.engine.goalrunner.model.GoalRunnerObservabilityLivenessClass
import skillbill.engine.goalrunner.model.GoalRunnerObservabilityWorkerRole
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.error.core.failureCodeLabel
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.exitCode
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Clock

internal class GoalRunnerObservabilityEmitter(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun recordLaunchLifecycle(
    subject: GoalRunnerObservabilitySubject,
    action: String,
    progress: GoalRunnerWorkflowProgress?,
    launchOutcome: AgentRunLaunchOutcome,
  ) {
    recordStart(subject, action, progress)
    progress?.let { child -> recordProgress(subject, child) }
    (launchOutcome as? AgentRunLaunchFacts)?.let { facts ->
      facts.liveness?.let { recordLiveness(subject, progress, facts) }
      recordOutputSummary(subject, progress, facts)
    }
  }

  internal fun record(
    subject: GoalRunnerObservabilitySubject,
    signal: GoalRunnerObservabilitySignal,
  ) {
    GoalRunnerBestEffortEmission.record(
      diagnostics = diagnostics,
      write = {
        outcomeStore.recordObservabilityEvent(
          request =
            GoalRunnerObservabilityRecordRequest(
              workflowId = subject.workflowId,
              issueKey = subject.issueKey,
              subtaskId = subject.subtaskId,
              workflowPhase = signal.workflowPhase.takeIf(String::isNotBlank) ?: "goal_runner_supervision",
              workerRole = signal.workerRole.wireValue,
              livenessClass = signal.livenessClass.wireValue,
              activitySummary = signal.activitySummary.takeIf(String::isNotBlank) ?: signal.livenessClass.wireValue,
              timestamp = clock.instant().toString(),
            ),
        )
      },
      missingMessage = {
        "Best-effort goal observability emit skipped (workflow not found): " +
          "workflowId='${subject.workflowId}' livenessClass='${signal.livenessClass.wireValue}'"
      },
      failureMessage = { error ->
        "Best-effort goal observability emit failed: workflowId='${subject.workflowId}' " +
          "livenessClass='${signal.livenessClass.wireValue}' " +
          "errorType='${error.failureCodeLabel() ?: error::class.qualifiedName}' " +
          "message='${GoalRunnerBestEffortEmission.boundedMessage(error.message.orEmpty())}'"
      },
    )
  }

  private fun recordStart(
    subject: GoalRunnerObservabilitySubject,
    action: String,
    progress: GoalRunnerWorkflowProgress?,
  ) {
    record(
      subject = subject,
      signal =
        GoalRunnerObservabilitySignal(
          workflowPhase = progress?.currentStepId?.takeIf(String::isNotBlank) ?: "preplan",
          livenessClass =
            if (action == "resume") {
              GoalRunnerObservabilityLivenessClass.RESUME
            } else {
              GoalRunnerObservabilityLivenessClass.SUBTASK_START
            },
          activitySummary = "Goal runner ${action}s subtask ${subject.subtaskId}.",
        ),
    )
  }

  private fun recordProgress(
    subject: GoalRunnerObservabilitySubject,
    child: GoalRunnerWorkflowProgress,
  ) {
    record(
      subject = subject,
      signal =
        GoalRunnerObservabilitySignal(
          workflowPhase = child.currentStepId,
          livenessClass = GoalRunnerObservabilityLivenessClass.PHASE_CHANGE,
          activitySummary = "Child workflow is at step ${child.currentStepId}.",
        ),
    )
    child.latestLivenessSignal?.takeIf(String::isNotBlank)?.let { signal ->
      record(
        subject = subject,
        signal =
          GoalRunnerObservabilitySignal(
            workflowPhase = child.currentStepId,
            livenessClass = GoalRunnerObservabilityLivenessClass.HEARTBEAT,
            activitySummary = signal,
          ),
      )
    }
  }

  private fun recordLiveness(
    subject: GoalRunnerObservabilitySubject,
    progress: GoalRunnerWorkflowProgress?,
    facts: AgentRunLaunchFacts,
  ) {
    val liveness = facts.liveness ?: return
    val phase = liveness.workflowStep ?: progress?.currentStepId ?: liveness.phase
    record(
      subject = subject,
      signal =
        GoalRunnerObservabilitySignal(
          workflowPhase = phase,
          livenessClass = GoalRunnerObservabilityLivenessClass.HEARTBEAT,
          activitySummary = "process_state=${liveness.processState.wireValue}; reason=${liveness.reason}",
        ),
    )
    liveness.lastFileActivityAt?.takeIf(String::isNotBlank)?.let { at ->
      record(
        subject = subject,
        signal =
          GoalRunnerObservabilitySignal(
            workflowPhase = phase,
            livenessClass = GoalRunnerObservabilityLivenessClass.FILE_ACTIVITY,
            activitySummary = liveness.lastFileActivityLabel ?: "file activity observed at $at",
          ),
      )
    }
  }

  private fun recordOutputSummary(
    subject: GoalRunnerObservabilitySubject,
    progress: GoalRunnerWorkflowProgress?,
    facts: AgentRunLaunchFacts,
  ) {
    val stderrDetail =
      stderrExcerpt(facts.stderr, GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS)
        ?.let { excerpt -> "; stderr_excerpt=$excerpt" }
        .orEmpty()
    record(
      subject = subject,
      signal =
        GoalRunnerObservabilitySignal(
          workflowPhase = progress?.currentStepId ?: facts.liveness?.workflowStep ?: "goal_runner_supervision",
          livenessClass = GoalRunnerObservabilityLivenessClass.WORKER_OUTPUT_SUMMARY,
          activitySummary =
            "stdout_chars=${facts.stdout.length}; stderr_chars=${facts.stderr.length}; " +
              "exit_status=${facts.termination.exitCode ?: "none"}$stderrDetail",
        ),
    )
  }
}

internal data class GoalRunnerObservabilitySubject(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: Int,
)

internal data class GoalRunnerObservabilitySignal(
  val workflowPhase: String,
  val workerRole: GoalRunnerObservabilityWorkerRole = GoalRunnerObservabilityWorkerRole.GOAL_RUNNER_SUPERVISOR,
  val livenessClass: GoalRunnerObservabilityLivenessClass,
  val activitySummary: String,
)
