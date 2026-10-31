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
import kotlinx.coroutines.sync.Mutex
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The app-scoped change reads: it follows the database session and delegates to that session's change observations. One
 * mutex shared across sessions serializes every git read, so at most one runs at a time app-wide.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeWorkChangesRepository(private val sessions: DatabaseSessionManager, private val diagnostics: Diagnostics) : WorkChangesRepository {
  private val gitReads = Mutex()

  override fun observe(baseline: ChangeBaseline?, running: Boolean): Flow<ChangesState> = sessions.sessionFlow(
    opening = ChangesState.Loading,
    failed = { ChangesState.Unavailable(ReadProblem.Storage(it)) },
  ) { it.changes.observe(baseline, running, gitReads) }

  override suspend fun fileDiff(worktreeRoot: String, baseSha: String, file: ChangedFile): FileDiffState {
    val changes = sessions.sessions.value?.changes
    if (changes == null) {
      diagnostics.record(DIFF_FAILED)
      return FileDiffState.Unavailable(ReadProblem.Runtime("No database session is open."))
    }
    return changes.fileDiff(worktreeRoot, baseSha, file, gitReads)
  }

  override suspend fun open(worktreeRoot: String, file: ChangedFile): OpenOutcome {
    val changes = sessions.sessions.value?.changes
    if (changes == null) {
      diagnostics.record(OPEN_FAILED)
      return OpenOutcome.NotOpened
    }
    return changes.open(worktreeRoot, file)
  }
}
