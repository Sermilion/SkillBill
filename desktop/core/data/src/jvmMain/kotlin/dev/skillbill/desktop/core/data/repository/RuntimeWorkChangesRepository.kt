package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.ChangesState
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.repository.WorkChangesRepository
import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The app-scoped change reads: it follows the database session and delegates to that session's change observations.
 * [GitReadLock] serializes every git read across sessions, so at most one runs at a time app-wide.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeWorkChangesRepository(
  private val sessions: DatabaseSessionManager,
  private val diagnostics: Diagnostics,
  private val gitReadLock: GitReadLock,
) : WorkChangesRepository {
  override fun observe(baseline: ChangeBaseline?, running: Boolean): Flow<ChangesState> = sessions.sessionFlow(
    opening = ChangesState.Loading,
    failed = { ChangesState.Unavailable(ReadProblem.Storage(it)) },
  ) { it.changes.observe(baseline, running, gitReadLock.mutex) }

  override suspend fun fileDiff(baseline: ChangeBaseline, baseSha: String, file: ChangedFile): FileDiffState {
    val changes = sessions.sessions.value?.changes
    if (changes == null) {
      diagnostics.record(DIFF_FAILED)
      return FileDiffState.Unavailable(ReadProblem.Runtime("No database session is open."))
    }
    return changes.fileDiff(baseline, baseSha, file, gitReadLock.mutex)
  }

  override suspend fun open(baseline: ChangeBaseline, file: ChangedFile): OpenOutcome {
    val changes = sessions.sessions.value?.changes
    if (changes == null) {
      diagnostics.record(OPEN_FAILED)
      return OpenOutcome.NotOpened
    }
    return changes.open(baseline, file)
  }
}
