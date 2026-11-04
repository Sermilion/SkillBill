package skillbill.cli.goal.import

import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.goalrunner.model.GoalRunnerImportResult

private const val STATUS_REFUSED = "refused"
private const val STATUS_INCOMPLETE = "incomplete"
private const val STATUS_FAILED = "failed"

internal fun GoalRunnerImportResult.toGoalImportCliMap(): Map<String, Any?> =
  when (this) {
    is GoalRunnerImportResult.Imported ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        GoalRunnerImportPayloadKeys.FEATURE_BRANCH to featureBranch,
        GoalRunnerImportPayloadKeys.COMMIT_SHA to commitSha,
        GoalRunnerImportPayloadKeys.ARCHIVE_PATH to archivePath.toString(),
        GoalRunnerImportPayloadKeys.ROW_COUNTS to rowCounts,
      )
    is GoalRunnerImportResult.Refused ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to STATUS_REFUSED,
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        GoalRunnerImportPayloadKeys.REFUSAL_REASON to reason,
        GoalRunnerImportPayloadKeys.FEATURE_BRANCH to featureBranch,
        GoalRunnerImportPayloadKeys.COMMIT_SHA to commitSha,
      )
    is GoalRunnerImportResult.Failed ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to STATUS_FAILED,
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        GoalRunnerImportPayloadKeys.REFUSAL_REASON to message,
      )
    is GoalRunnerImportResult.Incomplete ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to STATUS_INCOMPLETE,
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        GoalRunnerImportPayloadKeys.FEATURE_BRANCH to featureBranch,
        GoalRunnerImportPayloadKeys.COMMIT_SHA to commitSha,
        GoalRunnerImportPayloadKeys.ARCHIVE_PATH to archivePath.toString(),
        GoalRunnerImportPayloadKeys.REFUSAL_REASON to retryHint,
        GoalRunnerImportPayloadKeys.MISSING_SPEC_PATHS to missingSpecPaths,
      )
  }

internal fun goalImportText(result: GoalRunnerImportResult): String =
  when (result) {
    is GoalRunnerImportResult.Imported ->
      "Imported goal ${result.issueKey} from ${result.archivePath.fileName}. " +
        "The work item is paused and idle on feature branch '${result.featureBranch}' " +
        "at commit '${result.commitSha}'."
    is GoalRunnerImportResult.Refused -> "Import refused for ${result.issueKey}: ${result.reason}"
    is GoalRunnerImportResult.Failed -> "Import failed for ${result.issueKey}: ${result.message}"
    is GoalRunnerImportResult.Incomplete ->
      buildString {
        append("Import of ${result.issueKey} is incomplete; spec bundle files are missing.")
        result.missingSpecPaths.forEach { path -> append("\nMissing spec: $path") }
        append("\n${result.retryHint}")
      }
  }

internal fun goalImportExitCode(result: GoalRunnerImportResult): Int =
  when (result) {
    is GoalRunnerImportResult.Imported -> 0
    is GoalRunnerImportResult.Refused, is GoalRunnerImportResult.Failed, is GoalRunnerImportResult.Incomplete -> 1
  }
