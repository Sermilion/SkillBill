package dev.skillbill.desktop.core.data.session

import dev.skillbill.desktop.core.common.DatabaseDispatcher
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.session.StorageFailure
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal class OpenSession<G : SessionGraph>(val id: Long, val graph: G, val scope: CoroutineScope)

/**
 * Opens one session per database selection and closes the previous one first. Requests are handled one at a time, so a later
 * selection waits until the old graph is released and two graphs are never live at once.
 */
internal class SessionLifecycle<G : SessionGraph>(
  private val factory: SessionGraphFactory<G>,
  private val settings: AppSettingsRepository,
  private val classify: (Throwable, String) -> StorageFailure?,
  private val dispatcher: DatabaseDispatcher,
  private val diagnostics: Diagnostics,
  private val appScope: CoroutineScope,
) {
  private data class Selection(val databasePath: String?, val claudeConfigDir: String?)

  private sealed interface Request {
    data class Select(val selection: Selection) : Request

    data object Reopen : Request

    data class WatchEnded(val sessionId: Long, val failure: Throwable) : Request
  }

  private val requests = Channel<Request>(Channel.UNLIMITED)
  private val mutableState = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Opening(null))
  private val mutableCurrent = MutableStateFlow<OpenSession<G>?>(null)
  private var selection: Selection? = null
  private var counter = 0L

  val state: StateFlow<DatabaseSessionState> = mutableState.asStateFlow()
  val current: StateFlow<OpenSession<G>?> = mutableCurrent.asStateFlow()

  fun start(): Job = appScope.launch {
    merge(
      settings.settings.map { Request.Select(Selection(it.databasePath, it.claudeConfigDir)) }.distinctUntilChanged(),
      requests.receiveAsFlow(),
    ).collect { request ->
      try {
        handle(request)
      } catch (failure: CancellationException) {
        throw failure
      } catch (failure: Throwable) {
        // Report an unclassified failure without ending the loop, so a later selection or reopen still opens a session.
        appScope.launch { throw failure }
      }
    }
  }

  private suspend fun handle(request: Request) {
    when (request) {
      is Request.Select -> open(request.selection.also { selection = it })
      Request.Reopen -> selection?.let { open(it) }
      is Request.WatchEnded -> if (mutableCurrent.value?.id == request.sessionId) watchEnded(request.failure)
    }
  }

  fun reopen() {
    requests.trySend(Request.Reopen)
  }

  private suspend fun open(selection: Selection) {
    mutableState.value = DatabaseSessionState.Opening(selection.databasePath)
    closeCurrent()
    val id = ++counter
    // The watcher's ending IOException is reported through invokeOnCompletion below; anything else still surfaces.
    val failures = CoroutineExceptionHandler { _, failure -> if (failure !is IOException) appScope.launch { throw failure } }
    val scope = CoroutineScope(SupervisorJob(appScope.coroutineContext.job) + dispatcher.dispatcher + failures)
    var graph: G? = null
    try {
      val (watcher, path) = withContext(dispatcher.dispatcher) {
        val created = factory.create(SessionSelection(id, selection.databasePath, selection.claudeConfigDir, scope))
        graph = created
        created.open() to created.displayPath()
      }
      mutableCurrent.value = OpenSession(id, checkNotNull(graph), scope)
      mutableState.value = DatabaseSessionState.Open(path, id)
      watcher.invokeOnCompletion { cause ->
        if (cause != null && cause !is CancellationException) requests.trySend(Request.WatchEnded(id, cause))
      }
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: Throwable) {
      val path = graph?.let { runCatching { it.displayPath() }.getOrNull() } ?: selection.databasePath.orEmpty()
      scope.cancel()
      graph?.let { release(it) }
      val storageFailure = classify(failure, path)
      if (storageFailure == null) {
        diagnostics.record("desktop_session_open_failed")
        throw failure
      }
      mutableState.value = DatabaseSessionState.Failed(path, storageFailure)
    }
  }

  private suspend fun watchEnded(failure: Throwable) {
    val open = mutableCurrent.value ?: return
    val path = runCatching { open.graph.displayPath() }.getOrDefault(selection?.databasePath.orEmpty())
    closeCurrent()
    mutableState.value = DatabaseSessionState.Failed(path, classify(failure, path) ?: StorageFailure.Access)
  }

  /** Cancels and joins every session-scoped observation and in-flight read, then releases the graph's connections. */
  private suspend fun closeCurrent() {
    val open = mutableCurrent.value ?: return
    mutableCurrent.value = null
    open.scope.cancel()
    open.scope.coroutineContext.job.join()
    release(open.graph)
  }

  private suspend fun release(graph: G) {
    try {
      withContext(dispatcher.dispatcher) { graph.release() }
    } catch (failure: CancellationException) {
      throw failure
    } catch (_: Exception) {
      diagnostics.record("desktop_session_release_failed")
    }
  }
}
