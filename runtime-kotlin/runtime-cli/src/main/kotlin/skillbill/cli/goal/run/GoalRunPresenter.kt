package skillbill.cli.goal.run

import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.system.RuntimeProvenanceContract
import skillbill.engine.goalrunner.model.GoalRunnerEventSink
import skillbill.engine.goalrunner.model.GoalRunnerPauseResult
import skillbill.engine.goalrunner.model.GoalRunnerResumeResult
import skillbill.engine.goalrunner.model.GoalRunnerStopVerbResult
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import java.nio.file.Path

class GoalRunPresenter(
  private val issueKey: String,
  private val inputs: CliRunInputs,
  private val liveOutput: Boolean,
  private val repoRoot: Path,
  private val dbOverride: String?,
  private val runtimeProvenance: RuntimeProvenanceContract,
) {
  fun emitStartupProvenance() {
    val commandPrefix =
      buildString {
        append("skill-bill")
        dbOverride?.let { append(" --db ").append(shellQuote(it)) }
      }
    val rootArgument = "--repo-root ${shellQuote(repoRoot.toString())}"
    inputs.liveStdout(
      "goal $issueKey: launched runtime executable=${runtimeProvenance.executablePath} " +
        "version=${runtimeProvenance.version} build_id=${runtimeProvenance.buildId}\n" +
        "monitor (read-only; mutates nothing; no model tokens):\n" +
        "$commandPrefix goal watch $issueKey $rootArgument --interval-seconds 5\n" +
        "$commandPrefix goal status $issueKey $rootArgument --diff-stat\n",
    )
  }

  fun eventSink(): GoalRunnerEventSink = GoalRunnerEventSink { }

  fun outputSink(includeRawChildOutput: Boolean): AgentRunOutputSink =
    if (!liveOutput) {
      AgentRunOutputSink.NONE
    } else {
      AgentRunOutputSink { stream, text ->
        if (includeRawChildOutput) {
          when (stream) {
            AgentRunOutputStream.STDOUT -> inputs.liveStdout(text)
            AgentRunOutputStream.STDERR -> inputs.liveStderr(text)
          }
        }
      }
    }
}

internal fun GoalRunnerRunReport.toGoalRunCliMap(): Map<String, Any?> =
  when (this) {
    is GoalRunnerRunReport.Completed ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "complete",
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        "feature_name" to featureName,
        "attempted_subtasks" to attemptedSubtasks,
        "subtasks_completed" to subtasksCompleted,
        "subtasks_pending" to subtasksPending,
        "subtasks_blocked" to subtasksBlocked,
        "unaddressed_findings" to unaddressedFindingCount,
        "unaddressed_severity_breakdown" to unaddressedSeverityBreakdown,
        "pull_request_status" to pullRequestStatus.wireValue,
        "pull_request_url" to pullRequestUrl,
      )
    is GoalRunnerRunReport.Stopped ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "stopped",
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        "attempted_subtasks" to attemptedSubtasks,
        SharedPayloadKeys.SUBTASK_ID to stop.subtaskId,
        "reason" to stop.reason.name.lowercase(),
        "blocked_reason" to stop.blockedReason,
        SharedPayloadKeys.WORKFLOW_ID to stop.workflowId,
        "last_resumable_step" to stop.lastResumableStep,
      )
  }

internal fun GoalRunnerPauseResult.toGoalPauseCliMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to status.wireValue,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "parent_workflow_id" to parentWorkflowId,
    "paused" to paused,
    "pause_requested" to pauseRequested,
    "pause_reason" to pauseReason,
  )

internal fun goalPauseText(result: GoalRunnerPauseResult): String =
  buildString {
    appendLine("goal ${result.issueKey}: ${result.status.wireValue}")
    result.pauseReason?.let { appendLine("reason: $it") }
  }

internal fun GoalRunnerStopVerbResult.toGoalStopCliMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to status.wireValue,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "parent_workflow_id" to parentWorkflowId,
    "pause_reason" to pauseReason,
    "paused_at" to pausedAt,
    "termination_attempted" to terminationAttempted,
  )

internal fun goalStopText(result: GoalRunnerStopVerbResult): String =
  buildString {
    appendLine("goal ${result.issueKey}: ${result.status.wireValue}")
    result.pauseReason?.let { appendLine("reason: $it") }
    result.pausedAt?.let { appendLine("paused at: $it") }
  }

internal fun GoalRunnerResumeResult.toGoalResumeCliMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to status.wireValue,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "parent_workflow_id" to parentWorkflowId,
    "paused" to false,
    "pause_requested" to false,
    "cleared_pause_reason" to clearedPauseReason,
  )

internal fun goalResumeText(result: GoalRunnerResumeResult): String =
  buildString {
    appendLine("goal ${result.issueKey}: ${result.status.wireValue}")
    result.clearedPauseReason?.let { appendLine("cleared reason: $it") }
  }

internal fun goalRunText(report: GoalRunnerRunReport): String =
  when (report) {
    is GoalRunnerRunReport.Completed ->
      buildString {
        appendLine("goal ${report.issueKey}: finished")
        append("summary: ")
        append(singleLineBounded(report.featureName.orEmpty().ifBlank { "goal" }))
        append(" — ")
        val completedCount = report.subtasksCompleted
        val pendingCount = report.subtasksPending
        val blockedCount = report.subtasksBlocked
        val totalCount = completedCount + pendingCount + blockedCount
        append(completedCount)
        append("/")
        append(totalCount)
        append(" subtasks complete; pending=")
        append(pendingCount)
        append("; blocked=")
        append(blockedCount)
        report.pullRequestUrl?.takeIf(String::isNotBlank)?.let { url ->
          append("; PR ")
          append(singleLineBounded(url))
        }
        appendLine()
      }
    is GoalRunnerRunReport.Stopped ->
      buildString {
        val reason = report.stop.reason.name.lowercase()
        val verb =
          when {
            report.stop.reason == GoalRunnerStopReason.PAUSED -> "paused"
            report.stop.reason in
              setOf(
                GoalRunnerStopReason.FAILED,
                GoalRunnerStopReason.TIMEOUT,
                GoalRunnerStopReason.PULL_REQUEST_FAILED,
              ) -> "failed"
            else -> "blocked"
          }
        append("goal ${report.issueKey}: $verb")
        append(" at subtask ${report.stop.subtaskId}")
        append(" — ")
        append(singleLineBounded(report.stop.blockedReason.ifBlank { reason }))
        appendLine()
      }
  }

internal fun singleLineBounded(
  value: String,
  limit: Int = MAX_TERMINAL_FIELD_CHARS,
): String = value.replace(Regex("\\s+"), " ").trim().take(limit)

internal const val MAX_TERMINAL_FIELD_CHARS = 240

internal const val GOAL_STATUS_DATABASE_UNAVAILABLE = "database_unavailable"

internal fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
