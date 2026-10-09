package skillbill.cli.goal.purge

import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.goalrunner.model.GoalRunnerPurgeResult

internal const val GOAL_PURGE_RETAINED_NOTE: String =
  "`telemetry_outbox` rows are retained as anonymised history and do not affect relaunch."

private const val STATUS_REFUSED = "refused"
private const val STATUS_INCOMPLETE = "incomplete"

internal fun GoalRunnerPurgeResult.toGoalPurgeCliMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to purgeStatus(),
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    GoalRunnerPurgePayloadKeys.PARENT_WORKFLOW_IDS to parentWorkflowIds,
    GoalRunnerPurgePayloadKeys.DELETED_CHILD_WORKFLOW_IDS to deletedChildWorkflowIds,
    GoalRunnerPurgePayloadKeys.REMOVED_ROW_COUNTS to removedRowCounts,
    GoalRunnerPurgePayloadKeys.REMOVED_PATHS to removedPaths,
    GoalRunnerPurgePayloadKeys.SPEC_BUNDLE_ACTIONS to
      specBundleActions.map { action ->
        linkedMapOf("path" to action.path, "action" to action.kind.name.lowercase())
      },
    GoalRunnerPurgePayloadKeys.CHECKPOINT_REFS_PRUNED to checkpointRefsPruned,
    GoalRunnerPurgePayloadKeys.LEFTOVERS to leftovers,
    GoalRunnerPurgePayloadKeys.NOTHING_TO_REMOVE to nothingToRemove,
    GoalRunnerPurgePayloadKeys.RETAINED_NOTE to GOAL_PURGE_RETAINED_NOTE,
    GoalRunnerPurgePayloadKeys.REFUSAL_REASON to refusalReason,
  )

private fun GoalRunnerPurgeResult.purgeStatus(): String =
  when {
    refusalReason != null -> STATUS_REFUSED
    leftovers.isNotEmpty() -> STATUS_INCOMPLETE
    else -> CliPayloadStatus.OK
  }

internal fun goalPurgeText(result: GoalRunnerPurgeResult): String =
  buildList {
    when {
      result.refusalReason != null -> add("Purge refused for ${result.issueKey}: ${result.refusalReason}")
      result.nothingToRemove -> add("Nothing to remove for ${result.issueKey}.")
      result.leftovers.isNotEmpty() -> add("Purge of ${result.issueKey} is incomplete; leftovers remain.")
      else -> add("Purged goal runtime state for ${result.issueKey}.")
    }
    val rowCounts = result.removedRowCounts.filterValues { count -> count > 0 }
    if (rowCounts.isNotEmpty()) {
      add("Removed rows: ${rowCounts.entries.joinToString(", ") { (table, count) -> "$table=$count" }}")
    }
    result.removedPaths.forEach { path -> add("Removed path: $path") }
    result.specBundleActions.forEach { action -> add("Spec bundle ${action.kind.name.lowercase()}: ${action.path}") }
    if (result.checkpointRefsPruned > 0) add("Pruned checkpoint refs: ${result.checkpointRefsPruned}")
    result.leftovers.forEach { leftover -> add("Leftover: $leftover") }
    add(GOAL_PURGE_RETAINED_NOTE)
  }.joinToString("\n")

internal fun goalPurgeExitCode(result: GoalRunnerPurgeResult): Int =
  if (result.refusalReason != null || result.leftovers.isNotEmpty()) 1 else 0
