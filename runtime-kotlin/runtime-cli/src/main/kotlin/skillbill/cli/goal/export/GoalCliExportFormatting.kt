package skillbill.cli.goal.export

import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.goalrunner.model.GoalRunnerExportResult

private const val STATUS_REFUSED = "refused"
private const val STATUS_FAILED = "failed"

internal fun GoalRunnerExportResult.toGoalExportCliMap(): Map<String, Any?> =
  when (this) {
    is GoalRunnerExportResult.Exported ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        GoalRunnerExportPayloadKeys.FEATURE_BRANCH to featureBranch,
        GoalRunnerExportPayloadKeys.COMMIT_SHA to commitSha,
        GoalRunnerExportPayloadKeys.ARCHIVE_PATH to archivePath.toString(),
        GoalRunnerExportPayloadKeys.FILE_COUNT to fileCount,
        GoalRunnerExportPayloadKeys.ROW_COUNTS to rowCounts,
      )
    is GoalRunnerExportResult.Refused ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to STATUS_REFUSED,
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        GoalRunnerExportPayloadKeys.REFUSAL_REASON to reason,
      )
    is GoalRunnerExportResult.Failed ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to STATUS_FAILED,
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        GoalRunnerExportPayloadKeys.REFUSAL_REASON to message,
      )
  }

internal fun goalExportText(result: GoalRunnerExportResult): String =
  when (result) {
    is GoalRunnerExportResult.Exported ->
      buildList {
        add("Exported goal ${result.issueKey} to ${result.archivePath}.")
        add("Feature branch: ${result.featureBranch}")
        add("Commit SHA: ${result.commitSha}")
        add("Archive files: ${result.fileCount}")
        val rowCounts = result.rowCounts.filterValues { count -> count > 0 }
        if (rowCounts.isNotEmpty()) {
          add("Row counts: ${rowCounts.entries.joinToString(", ") { (table, count) -> "$table=$count" }}")
        }
      }.joinToString("\n")
    is GoalRunnerExportResult.Refused -> "Export refused for ${result.issueKey}: ${result.reason}"
    is GoalRunnerExportResult.Failed -> "Export failed for ${result.issueKey}: ${result.message}"
  }

internal fun goalExportExitCode(result: GoalRunnerExportResult): Int =
  when (result) {
    is GoalRunnerExportResult.Exported -> 0
    is GoalRunnerExportResult.Refused, is GoalRunnerExportResult.Failed -> 1
  }
