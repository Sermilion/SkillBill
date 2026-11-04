package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import kotlinx.coroutines.flow.Flow

/** Reads goal subtask scopes and their changed files through the runtime. */
interface WorkChangeScopesRepository {
  /** Observes a goal's subtasks and per-subtask change statistics. */
  fun observeSubtasks(goalWorkflowId: String, running: Boolean): Flow<SubtaskScopesState>

  /** Observes per-commit change statistics for [target]. */
  fun observeCommitStats(target: ChangeScopeTarget): Flow<Map<String, SubtaskStats>>

  /** Observes files in [scope], refreshing while its content follows HEAD or the working tree. */
  fun observeScope(target: ChangeScopeTarget, scope: ChangeScope, running: Boolean): Flow<ScopeChangesState>

  /** Reads the patch for [file] in [scope], with attribution when the scope spans subtasks. */
  suspend fun fileDiff(target: ChangeScopeTarget, scope: ChangeScope, file: ChangedFile): FileDiffState
}
