package skillbill.engine.goalrunner.execution.support

import skillbill.engine.goalrunner.execution.core.GoalRunnerProgressReader
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.time.Clock

val RUNTIME_WORKFLOW_ID_PREFIX: String = WorkflowFamily.TASK_RUNTIME.definition.workflowIdPrefix

const val FEATURE_SPEC_ROOT = ".feature-specs"
const val GIT_PORCELAIN_MIN_LENGTH = 4
const val GIT_PORCELAIN_STATUS_PREFIX_LENGTH = 3
const val MAX_VALIDATION_QUALITY_RETRIES = 3
const val MAX_REPORTED_FINALIZE_DIRTY_PATHS = 10

val CHILD_WORKFLOW_BLOCK_REASONS: Set<GoalRunnerStopReason> =
  setOf(
    GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
    GoalRunnerStopReason.TIMEOUT,
    GoalRunnerStopReason.INTERRUPTED,
  )

fun isFeatureSpecPath(path: String): Boolean {
  val normalized = path.trim().trimEnd('/').removeSurrounding("\"").removePrefix("./")
  val dotted = if (normalized.startsWith(".")) normalized else ".$normalized"
  return dotted == FEATURE_SPEC_ROOT || dotted.startsWith("$FEATURE_SPEC_ROOT/")
}

fun parseGitPorcelainPaths(output: String): List<String> =
  output
    .lineSequence()
    .map(String::trimEnd)
    .filter { line -> line.length >= GIT_PORCELAIN_MIN_LENGTH }
    .map { line -> line.substring(GIT_PORCELAIN_STATUS_PREFIX_LENGTH).substringAfterLast(" -> ").trim() }
    .filter(String::isNotBlank)
    .toList()

internal data class GoalRunnerProgressState(
  val subtask: DecompositionSubtask,
  val childProgress: GoalRunnerWorkflowProgress?,
)

class GoalRunnerTickProgressReader(
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

  internal fun progressState(): GoalRunnerProgressState? {
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
