package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeSet
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.ChangesState
import dev.skillbill.desktop.core.domain.repository.WorkChangesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The detail's open diff. One read runs at a time: a new selection or re-read cancels the previous one, and a result lands only
 * while the diff is still open for the file and base it was read for. Each new change list re-reads a diff whose file or base
 * changed, so the view keeps matching the list.
 */
internal class DetailDiff(
  private val scope: CoroutineScope,
  private val changes: WorkChangesRepository,
  private val current: () -> DetailUiState,
  private val update: ((DiffUi?) -> DiffUi?) -> Unit,
) {
  private var read: Job? = null

  fun select(row: ChangedFile) {
    val shown = current().changes
    val listed = shown.state as? ChangesState.Listed ?: return
    val baseline = shown.baseline ?: return
    val file = listed.changes.rowFor(row) ?: return
    val diff = DiffUi(file, listed.changes.baseSha, baseline.worktreeRoot, DiffContent.Loading)
    update { diff }
    load(diff)
  }

  fun onListed(baseline: ChangeBaseline, changeSet: ChangeSet) {
    val open = current().diff ?: return
    val file = changeSet.rowFor(open.file) ?: changeSet.files.firstOrNull { it.path == open.file.path }
    if (file == null) {
      read?.cancel()
      update { it?.copy(content = DiffContent.NoLongerChanged) }
      return
    }
    val gone = open.content == DiffContent.NoLongerChanged
    if (!gone && file == open.file && changeSet.baseSha == open.baseSha) return
    // The shown lines stay until the re-read lands, so a refresh does not blank the view.
    val next = open.copy(
      file = file,
      baseSha = changeSet.baseSha,
      worktreeRoot = baseline.worktreeRoot,
      content = if (gone) DiffContent.Loading else open.content,
    )
    update { next }
    load(next)
  }

  fun close() {
    read?.cancel()
    update { null }
  }

  private fun load(target: DiffUi) {
    read?.cancel()
    read = scope.launch {
      val result = changes.fileDiff(target.worktreeRoot, target.baseSha, target.file)
      update { open ->
        if (open != null && open.file == target.file && open.baseSha == target.baseSha) open.copy(content = DiffContent.Read(result)) else open
      }
    }
  }
}

/**
 * The listed row for [row], matched by path and status: git lists a path twice when it left the index but stays on disk, once as
 * a deleted entry and once as an untracked file.
 */
internal fun ChangeSet.rowFor(row: ChangedFile): ChangedFile? = files.firstOrNull { it.path == row.path && it.status == row.status }
