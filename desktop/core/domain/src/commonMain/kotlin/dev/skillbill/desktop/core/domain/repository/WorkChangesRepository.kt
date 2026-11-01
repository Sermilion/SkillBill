package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.ChangesState
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import kotlinx.coroutines.flow.Flow

/** The open execution's changed files, read from git through the runtime. */
interface WorkChangesRepository {
  /** Reads [baseline]'s changes once, and again every few seconds while [running]; a null baseline is `NoSubtask`. */
  fun observe(baseline: ChangeBaseline?, running: Boolean): Flow<ChangesState>

  /** Git's own patch for [file] against [baseSha], parsed into hunks. */
  suspend fun fileDiff(worktreeRoot: String, baseSha: String, file: ChangedFile): FileDiffState

  /** Opens [file] in the system default application; a deleted file is never opened. */
  suspend fun open(worktreeRoot: String, file: ChangedFile): OpenOutcome
}
