package dev.skillbill.tray.core.data.repository

import dev.skillbill.tray.core.common.Diagnostics
import dev.skillbill.tray.core.common.DispatcherProvider
import dev.skillbill.tray.core.common.di.DatabaseSessionScope
import dev.skillbill.tray.core.data.port.CommitSignalSource
import dev.skillbill.tray.core.data.port.ControlRefusal
import dev.skillbill.tray.core.data.port.ControlResult
import dev.skillbill.tray.core.data.port.RuntimeSpecGateway
import dev.skillbill.tray.core.data.port.SpecDirectoryChanges
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.SpecBundleControl
import dev.skillbill.tray.core.domain.model.SpecProject
import dev.skillbill.tray.core.domain.model.SpecSnapshot
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.SpecBrowserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Duration.Companion.milliseconds

private val RefreshDebounce = 250.milliseconds

/**
 * The runtime's spec browser for the selected database. Every runtime failure becomes a [Problem]; the repository adds no
 * "couldn't start" style prefix, the caller words the action.
 */
@Inject
@SingleIn(DatabaseSessionScope::class)
class RuntimeSpecBrowserRepository(
  private val gateway: RuntimeSpecGateway,
  private val directories: SpecDirectoryChanges,
  private val commitSignals: CommitSignalSource,
  private val dispatchers: DispatcherProvider,
  private val diagnostics: Diagnostics,
) : SpecBrowserRepository {
  override suspend fun projects(): Outcome<List<SpecProject>> = guarded(
    "spec_projects_failed",
    "Skill Bill couldn't list its projects. Retry, or check the runtime installation.",
  ) { Outcome.Value(gateway.projects()) }

  override suspend fun projectFor(repositoryRoot: String): Outcome<SpecProject> = guarded(
    "spec_project_failed",
    "Skill Bill couldn't find the project for this checkout.",
  ) { Outcome.Value(gateway.projectFor(repositoryRoot)) }

  override suspend fun control(
    projectId: String,
    bundlePath: String,
    preferredRepoRoot: String?,
  ): Outcome<SpecBundleControl> = guarded(
    "spec_control_failed",
    "Skill Bill couldn't read this spec folder's run state. Retry.",
  ) { Outcome.Value(gateway.control(projectId, bundlePath, preferredRepoRoot)) }

  override suspend fun startGoal(repoRoot: String, issueKey: String, agentId: String?): Outcome<Unit> = guarded(
    "spec_start_failed",
    "Skill Bill couldn't start this goal.",
  ) {
    when (val result = gateway.startGoal(repoRoot, issueKey, agentId)) {
      ControlResult.Accepted, ControlResult.IntentRecorded, ControlResult.ExitConfirmed -> Outcome.Value(Unit)
      is ControlResult.LaunchFailed -> {
        diagnostics.record("spec_start_launch_failed")
        Outcome.Failure(Problem(ProblemCode.UNKNOWN, result.reason))
      }
      is ControlResult.Rejected -> {
        diagnostics.record("spec_start_${result.reasonCode}")
        Outcome.Failure(Problem(ProblemCode.STALE_TARGET, runRefusalMessage(result.refusal)))
      }
      ControlResult.Stale, ControlResult.TerminationAttempted ->
        Outcome.Failure(Problem(ProblemCode.STALE_TARGET, runRefusalMessage(ControlRefusal.OTHER)))
    }
  }

  override suspend fun purgeGoal(repoRoot: String, issueKey: String): Outcome<Unit> = guarded(
    "spec_purge_failed",
    "Skill Bill couldn't purge this goal's run history.",
  ) {
    val result = gateway.purgeGoal(repoRoot, issueKey)
    when {
      result.refusalReason != null -> Outcome.Failure(Problem(ProblemCode.UNKNOWN, result.refusalReason))
      result.leftovers.isNotEmpty() ->
        Outcome.Failure(Problem(ProblemCode.UNKNOWN, "Run data is still present: " + result.leftovers.joinToString(", ")))
      else -> Outcome.Value(Unit)
    }
  }

  @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
  override fun observeSnapshot(projectId: String, preferredRepoRoot: String?): Flow<Outcome<SpecSnapshot>> = channelFlow {
    val specsRoot = MutableStateFlow<String?>(null)

    suspend fun refresh() {
      val outcome = guarded("spec_snapshot_failed", "Skill Bill couldn't read this project's specs. Retry.") {
        Outcome.Value(gateway.snapshot(projectId, preferredRepoRoot))
      }
      if (outcome is Outcome.Value) outcome.value.project.specsRoot.takeIf(String::isNotBlank)?.let { specsRoot.value = it }
      send(outcome)
    }

    refresh()
    // The watcher starts after the first successful read, because that read is what names the spec directory.
    val directoryTriggers = specsRoot.filterNotNull().distinctUntilChanged().flatMapLatest { root ->
      directories.changes(root).catch { diagnostics.record("spec_directory_signal_unavailable") }
    }
    merge(directoryTriggers, storeTriggers()).debounce(RefreshDebounce).conflate().collect { refresh() }
  }

  /** Goal-topic commits of the runtime store; an unreadable store leaves the directory watcher as the only trigger. */
  private fun storeTriggers(): Flow<Unit> = flow {
    val path = withContext(dispatchers.io) { gateway.databasePath() } ?: return@flow
    var lastGoals: Long? = null
    emitAll(
      commitSignals.changes(path, CompletableDeferred()).transform { change ->
        val goals = change.revisions[commitSignals.topics.goals]
        if (change.initial) {
          lastGoals = goals
        } else if (change.resynchronize || goals != lastGoals) {
          lastGoals = goals
          emit(Unit)
        }
      },
    )
  }.catch { diagnostics.record("spec_store_signal_unavailable") }

  private suspend fun <T> guarded(event: String, summary: String, block: () -> Outcome<T>): Outcome<T> =
    withContext(dispatchers.io) {
      try {
        block()
      } catch (failure: CancellationException) {
        throw failure
      } catch (_: Exception) {
        diagnostics.record(event)
        Outcome.Failure(Problem(ProblemCode.UNKNOWN, summary))
      }
    }
}
