package skillbill.engine.goalrunner.execution.support

import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.engine.goalrunner.execution.core.GoalRunnerProgressReader
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path
import java.time.Clock

internal val RUNTIME_WORKFLOW_ID_PREFIX: String = WorkflowFamily.TASK_RUNTIME.definition.workflowIdPrefix

internal const val MAX_VALIDATION_QUALITY_RETRIES = 3

internal val CHILD_WORKFLOW_BLOCK_REASONS: Set<GoalRunnerStopReason> =
  setOf(
    GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
    GoalRunnerStopReason.TIMEOUT,
    GoalRunnerStopReason.INTERRUPTED,
  )

public fun isFeatureSpecPath(path: String): Boolean {
  val normalized = path.trim().trimEnd('/').removeSurrounding("\"").removePrefix("./")
  val dotted = if (normalized.startsWith(".")) normalized else ".$normalized"
  return dotted == LegacyProjectStatePaths.FEATURE_SPECS_DIRECTORY ||
    dotted.startsWith(LegacyProjectStatePaths.FEATURE_SPECS_PREFIX)
}

internal fun goalRunnerRepositoryContentIdentity(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
): WorkflowGitOperationResult {
  val tracked = gitOperations.trackedPaths(repoRoot)
  if (tracked !is WorkflowGitNameListResult.Listed) {
    return WorkflowGitOperationResult.Failed(error = "Could not enumerate tracked repository paths.")
  }
  val owned = gitOperations.repositoryOwnedPaths(repoRoot)
  if (owned !is WorkflowGitNameListResult.Listed) {
    return WorkflowGitOperationResult.Failed(error = "Could not enumerate untracked repository paths.")
  }
  val paths = (tracked.names + owned.names).distinct().filterNot(::isFeatureSpecPath)
  return gitOperations.scopedContentFingerprint(repoRoot, paths)
}

public data class GoalRunnerProgressState(
  public val subtask: DecompositionSubtask,
  public val childProgress: GoalRunnerWorkflowProgress?,
)

public class GoalRunnerTickProgressReader(
  private val manifestStore: GoalRunnerManifestStore,
  private val progressReader: GoalRunnerProgressReader,
  private val issueKey: String,
  private val subtaskId: Int,
  private val request: GoalRunnerRunRequest,
  private val clock: Clock,
) {
  private var cachedAtMillis: Long = 0
  private var cachedHasValue: Boolean = false
  private var cached: GoalRunnerProgressState? = null

  public fun progressState(): GoalRunnerProgressState? {
    val now = clock.millis()
    if (cachedHasValue && now >= cachedAtMillis && now - cachedAtMillis < TICK_MEMO_WINDOW_MILLIS) {
      return cached
    }
    cached = resolve()
    cachedAtMillis = now
    cachedHasValue = true
    return cached
  }

  private fun resolve(): GoalRunnerProgressState? {
    val subtask =
      manifestStore.loadByIssueKey(issueKey, request.repoRoot)
        ?.manifest
        ?.subtasks
        ?.firstOrNull { subtask -> subtask.id == subtaskId }
        ?: return null
    val childProgress =
      subtask.workflowId
        ?.takeIf(String::isNotBlank)
        ?.let { workflowId -> readChildProgress(workflowId) }
    return GoalRunnerProgressState(subtask, childProgress)
  }

  private fun readChildProgress(workflowId: String): GoalRunnerWorkflowProgress? =
    when (val read = progressReader.read(workflowId)) {
      is GoalRunnerChildProgressRead.Present -> read.progress
      is GoalRunnerChildProgressRead.Absent -> null
      is GoalRunnerChildProgressRead.Failed -> null
    }

  internal companion object {
    const val SUPERVISOR_POLL_CADENCE_MILLIS: Long = 250L
    const val TICK_MEMO_WINDOW_MILLIS: Long = 200L
  }
}
