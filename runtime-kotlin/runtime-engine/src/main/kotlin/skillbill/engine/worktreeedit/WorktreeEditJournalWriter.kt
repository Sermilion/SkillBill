package skillbill.engine.worktreeedit

import me.tatarka.inject.annotations.Inject
import skillbill.application.getOrElseUnlessCooperative
import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.contracts.workflow.payload.WorktreeEditJournalPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.checkpoint.isRuntimePrivatePath
import skillbill.error.core.failureCodeLabel
import skillbill.idestatus.model.WorktreeEditSource
import skillbill.idestatus.model.WorktreeEditTick
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeNumstatResult
import skillbill.workflow.model.goalobservability.GoalObservabilityFileDiffStat
import java.nio.file.Path
import java.time.Clock

@Inject
class WorktreeEditJournalWriter(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val gitOperations: WorkflowGitOperations,
) {
  fun observer(
    repoRoot: Path,
    resolveWorkflowId: () -> String?,
    resolvePhaseId: () -> String?,
  ): AgentRunWorktreeEditObserver =
    AgentRunWorktreeEditObserver {
      observe(repoRoot, resolveWorkflowId, resolvePhaseId)
    }

  private fun observe(
    repoRoot: Path,
    resolveWorkflowId: () -> String?,
    resolvePhaseId: () -> String?,
  ) {
    val workflowId =
      runCatching { resolveWorkflowId() }
        .getOrElseUnlessCooperative { null }
        ?.takeIf(String::isNotBlank)
        ?: return
    val error =
      runCatching {
        persistMeasuredTick(workflowId, repoRoot, resolvePhaseId)
      }.exceptionOrNull() ?: return
    error.rethrowIfCooperativeCancellationOrInterruption()
    val cause =
      (error.message?.takeIf(String::isNotBlank) ?: error.failureCodeLabel() ?: error::class.simpleName.orEmpty())
        .take(MAX_DIAGNOSTIC_CAUSE_LENGTH)
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=worktree_edit_journal_persist value_expected=persisted_tick value_used=failed " +
        "workflow_id=$workflowId cause=$cause",
    )
  }

  private fun persistMeasuredTick(
    workflowId: String,
    repoRoot: Path,
    resolvePhaseId: () -> String?,
  ) {
    val measured = gitOperations.worktreeNumstat(repoRoot)
    if (!emitMeasureSkipIfNeeded(workflowId, measured)) return
    val entries = measured.files.filterNot(::isRuntimePrivatePath)
    val measuredSet = entries.map { Triple(it.path, it.insertions, it.deletions) }.toSet()
    val memo = memoizedSet(workflowId)
    if (measuredSet == memo) return
    if (measuredSet.isEmpty()) {
      synchronized(memoState) { memoState[workflowId] = measuredSet }
      return
    }
    val phaseId = runCatching { resolvePhaseId() }.getOrElseUnlessCooperative { null }
    val maxRows = WorktreeEditJournalPayloadKeys.MAX_ROWS_PER_WORKFLOW
    val persistEntries = entries.take(maxRows)
    val truncatedRows = entries.size - persistEntries.size
    val tick =
      WorktreeEditTick(
        recordedAt = clock.instant(),
        phaseId = phaseId,
        source = WorktreeEditSource.WORKTREE_PROBE,
        entries = persistEntries,
      )
    val droppedRows =
      database.selfManagedWrite { unitOfWork ->
        unitOfWork.worktreeEditJournal.append(workflowId, tick)
        unitOfWork.worktreeEditJournal.trimToCap(workflowId, maxRows)
      } + truncatedRows
    if (droppedRows > 0) {
      diagnostics.warning(
        "seam=worktree_edit_journal_cap value_expected=rows_within_cap value_used=dropped_oldest_ticks " +
          "workflow_id=$workflowId dropped_rows=$droppedRows",
      )
    }
    synchronized(memoState) { memoState[workflowId] = measuredSet }
  }

  private fun emitMeasureSkipIfNeeded(
    workflowId: String,
    measured: WorkflowWorktreeNumstatResult,
  ): Boolean {
    if (measured.status == WorkflowGitOperationStatus.OK) return true
    val cause =
      (measured.error?.takeIf(String::isNotBlank) ?: measured.status.wireValue)
        .take(MAX_DIAGNOSTIC_CAUSE_LENGTH)
    diagnostics.warning(
      "seam=worktree_edit_journal_measure value_expected=numstat value_used=skipped " +
        "workflow_id=$workflowId cause=$cause",
    )
    return false
  }

  private fun memoizedSet(workflowId: String): Set<Triple<String, Int, Int>> =
    synchronized(memoState) {
      memoState.getOrPut(workflowId) {
        database.readIfPresent { unitOfWork ->
          unitOfWork.worktreeEditJournal.latestTick(workflowId)
            ?.entries
            ?.map { Triple(it.path, it.insertions, it.deletions) }
            ?.toSet()
        } ?: emptySet()
      }
    }

  private fun isRuntimePrivatePath(entry: GoalObservabilityFileDiffStat): Boolean {
    val path = entry.path
    return path == RUNTIME_PRIVATE_ROOT || path.startsWith(RUNTIME_PRIVATE_PREFIX)
  }

  private val memoState =
    object : LinkedHashMap<String, Set<Triple<String, Int, Int>>>(
      INITIAL_TRACKED_WORKFLOWS,
      ACCESS_ORDER_LOAD_FACTOR,
      true,
    ) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<Triple<String, Int, Int>>>?): Boolean =
        size > MAX_TRACKED_WORKFLOWS
    }

  private companion object {
    const val MAX_TRACKED_WORKFLOWS: Int = 512
    const val MAX_DIAGNOSTIC_CAUSE_LENGTH: Int = 256
    const val INITIAL_TRACKED_WORKFLOWS: Int = 16
    const val ACCESS_ORDER_LOAD_FACTOR: Float = 0.75f
    const val RUNTIME_PRIVATE_ROOT: String = ".skill-bill"
    const val RUNTIME_PRIVATE_PREFIX: String = ".skill-bill/"
  }
}
