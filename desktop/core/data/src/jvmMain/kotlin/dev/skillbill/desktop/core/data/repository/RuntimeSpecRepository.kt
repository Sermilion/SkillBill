package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.SpecBundleRef
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.concurrent.ConcurrentHashMap

/** The app-scoped spec browser: it follows the database session and delegates to that session's spec observations. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeSpecRepository(private val sessions: DatabaseSessionManager, private val diagnostics: Diagnostics) : SpecRepository {
  override suspend fun projects(): Outcome<List<SpecProject>, ReadProblem> =
    sessions.sessions.value?.specs?.projects() ?: noSession

  /** Remembers the checkout a project was resolved from, so its tree reads against that checkout in every session. */
  override suspend fun projectFor(repoRoot: String): Outcome<SpecProject, ReadProblem> {
    val outcome = sessions.sessions.value?.specs?.projectFor(repoRoot) ?: noSession
    if (outcome is Outcome.Success) rootOf(outcome.value.projectId).value = outcome.value.repoRoot ?: repoRoot
    return outcome
  }

  override fun observeTree(projectId: String): Flow<SpecTreeState> = sessions.sessionFlow(
    opening = SpecTreeState.Loading,
    failed = { SpecTreeState.Unavailable(ReadProblem.Storage(it)) },
  ) { it.specs.observeTree(projectId, rootOf(projectId)) }

  override fun refreshTree(projectId: String) {
    sessions.sessions.value?.specs?.refreshTree(projectId)
  }

  override fun control(bundle: SpecBundleRef): Flow<SpecControlState> = sessions.sessionFlow(
    opening = SpecControlState.Loading,
    failed = { SpecControlState.Unavailable(ReadProblem.Storage(it)) },
  ) { it.specs.control(bundle) }

  override suspend fun openFile(path: String): OpenOutcome = sessions.sessions.value?.specs?.openFile(path) ?: OpenOutcome.NotOpened

  override suspend fun openFileInDefaultApp(path: String): OpenOutcome {
    val observations = sessions.sessions.value?.specs
    if (observations == null) {
      diagnostics.record("desktop_phase_instruction_default_open_unavailable")
      return OpenOutcome.NotOpened
    }
    return observations.openFileInDefaultApp(path)
  }

  override suspend fun revealFolder(path: String): OpenOutcome =
    sessions.sessions.value?.specs?.revealFolder(path) ?: OpenOutcome.NotOpened

  private val preferredRoots = ConcurrentHashMap<String, MutableStateFlow<String?>>()

  private fun rootOf(projectId: String) = preferredRoots.computeIfAbsent(projectId) { MutableStateFlow(null) }

  private val noSession = Outcome.Failure(ReadProblem.Runtime("The database session is not open."))
}
