package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.runtime.readOrProblem
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.RepositoryBranch
import dev.skillbill.desktop.core.domain.model.RepositoryBranches
import dev.skillbill.desktop.core.domain.model.RepositoryBranchesRead
import dev.skillbill.desktop.core.domain.repository.RepositoryBranchesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.RepositoryBranchRef
import skillbill.ports.workflow.gitops.model.RepositoryBranchesResult
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.time.Instant

private const val READ_FAILED = "desktop_repository_branches_read_failed"

/** Reads branches on IO under the app-wide git lock, never on the database dispatcher. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeRepositoryBranchesRepository(
  private val sessions: DatabaseSessionManager,
  private val diagnostics: Diagnostics,
  private val gitReadLock: GitReadLock,
) : RepositoryBranchesRepository {
  override suspend fun branches(path: String): RepositoryBranchesRead {
    val runtime = sessions.sessions.value?.runtime
      ?: return unavailable(ReadProblem.Runtime("No database session is open."))
    val target = try {
      Path.of(path)
    } catch (_: InvalidPathException) {
      return RepositoryBranchesRead.NotARepository(path)
    }
    val read = readOrProblem(runtime, diagnostics) {
      gitReadLock.mutex.withLock { withContext(Dispatchers.IO) { runtime.repositoryBranchesPort.branches(target) } }
    }
    return when (read) {
      is Outcome.Failure -> unavailable(read.error)
      is Outcome.Success -> when (val result = read.value) {
        is RepositoryBranchesResult.Listed -> RepositoryBranchesRead.Listed(
          RepositoryBranches(result.repositoryRoot, result.defaultBranch, result.local.map { it.toDomain() }, result.remote.map { it.toDomain() }, result.currentBranch),
        )
        is RepositoryBranchesResult.NotARepository -> RepositoryBranchesRead.NotARepository(result.path)
        is RepositoryBranchesResult.Failed -> unavailable(ReadProblem.Runtime(result.message))
      }
    }
  }

  override suspend fun switchToDefaultBranch(path: String, expectedCurrentBranch: String?): Outcome<Unit, ReadProblem> {
    val runtime = sessions.sessions.value?.runtime
      ?: return Outcome.Failure(ReadProblem.Runtime("No database session is open."))
    val target = try {
      Path.of(path)
    } catch (_: InvalidPathException) {
      return Outcome.Failure(ReadProblem.Runtime("Invalid repository path."))
    }
    val read = readOrProblem(runtime, diagnostics) {
      gitReadLock.mutex.withLock { withContext(Dispatchers.IO) { runtime.repositoryBranchesPort.switchToDefaultBranch(target, expectedCurrentBranch) } }
    }
    val outcome = when (read) {
      is Outcome.Failure -> read
      is Outcome.Success -> when (val result = read.value) {
        is WorkflowGitOperationResult.Ok -> Outcome.Success(Unit)
        is WorkflowGitOperationResult.Failed -> Outcome.Failure(ReadProblem.Runtime(result.error))
      }
    }
    if (outcome is Outcome.Failure) diagnostics.record("desktop_repository_branch_switch_failed")
    return outcome
  }

  private fun unavailable(problem: ReadProblem): RepositoryBranchesRead {
    diagnostics.record(READ_FAILED)
    return RepositoryBranchesRead.Unavailable(problem)
  }

  private fun RepositoryBranchRef.toDomain() = RepositoryBranch(name, Instant.fromEpochSeconds(committedAtEpochSeconds))
}
