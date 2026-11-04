package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.domain.repository.WorkChangeScopesRepository
import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeWorkChangeScopesRepository(
  private val sessions: DatabaseSessionManager,
  private val diagnostics: Diagnostics,
  private val lock: GitReadLock,
  private val cache: CommittedChangeCache,
) : WorkChangeScopesRepository {
  override fun observeSubtasks(goalWorkflowId: String, running: Boolean): Flow<SubtaskScopesState> = sessions.sessionFlow(
    opening = SubtaskScopesState.Loading,
    failed = { SubtaskScopesState.Unavailable(ReadProblem.Storage(it)) },
  ) { it.changeScopes.observeSubtasks(goalWorkflowId, running, lock.mutex, cache) }

  override fun observeCommitStats(target: ChangeScopeTarget): Flow<Map<String, SubtaskStats>> = sessions.sessionFlow(
    opening = emptyMap(),
    failed = { emptyMap() },
  ) { it.changeScopes.observeCommitStats(target, lock.mutex, cache) }

  override fun observeScope(target: ChangeScopeTarget, scope: ChangeScope, running: Boolean): Flow<ScopeChangesState> = sessions.sessionFlow(
    opening = ScopeChangesState.Loading,
    failed = { ScopeChangesState.Unavailable(ReadProblem.Storage(it)) },
  ) { it.changeScopes.observeScope(target, scope, running, lock.mutex, cache) }

  override suspend fun fileDiff(target: ChangeScopeTarget, scope: ChangeScope, file: ChangedFile): FileDiffState {
    val observations = sessions.sessions.value?.changeScopes
    if (observations == null) {
      diagnostics.record(SCOPES_READ_FAILED)
      return FileDiffState.Unavailable(ReadProblem.Runtime("No database session is open."))
    }
    return observations.fileDiff(target, scope, file, lock.mutex, cache)
  }
}
