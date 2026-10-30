package skillbill.engine.featuretask.runloop.durable

import me.tatarka.inject.annotations.Inject
import skillbill.application.getOrElseUnlessCooperative
import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.contracts.projectstore.LegacyProjectStatePaths
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
    val maxRows = WorktreeEditJournalPayloadKeys.MAX_ROWS_PER_WORKFLOW
    val persistEntries = entries.take(maxRows)
    if (persistEntries.isEmpty()) {
      database.transaction { unitOfWork ->
        unitOfWork.worktreeEditJournal.latestTick(workflowId)?.recordedAt?.plusNanos(1)?.let { cutoff ->
          unitOfWork.worktreeEditJournal.deleteTicksBefore(workflowId, cutoff)
        }
      }
      return
    }
    val measuredEntries = persistEntries.map { Triple(it.path, it.insertions, it.deletions) }
    val phaseId = runCatching { resolvePhaseId() }.getOrElseUnlessCooperative { null }
    val truncatedRows = entries.size - persistEntries.size
    val tick =
      WorktreeEditTick(
        recordedAt = clock.instant(),
        phaseId = phaseId,
        source = WorktreeEditSource.WORKTREE_PROBE,
        entries = persistEntries,
      )
    database.transaction { unitOfWork ->
      val journal = unitOfWork.worktreeEditJournal
      val latestEntries =
        journal.latestTick(workflowId)
          ?.entries
          ?.map { Triple(it.path, it.insertions, it.deletions) }
      if (latestEntries != measuredEntries) {
        journal.append(workflowId, tick)
        journal.deleteTicksBefore(workflowId, tick.recordedAt)
      }
    }
    if (truncatedRows > 0) {
      diagnostics.warning(
        "seam=worktree_edit_journal_cap value_expected=rows_within_cap value_used=truncated_entries " +
          "workflow_id=$workflowId dropped_rows=$truncatedRows",
      )
    }
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

  private fun isRuntimePrivatePath(entry: GoalObservabilityFileDiffStat): Boolean {
    val path = entry.path
    return path == LegacyProjectStatePaths.RUNTIME_STATE_DIRECTORY ||
      path.startsWith(LegacyProjectStatePaths.RUNTIME_STATE_PREFIX)
  }

  private companion object {
    const val MAX_DIAGNOSTIC_CAUSE_LENGTH: Int = 256
  }
}
