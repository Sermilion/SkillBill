package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.common.DatabaseDispatcher
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.data.di.DatabaseSessionScope
import dev.skillbill.desktop.core.data.session.SessionSelectionHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import me.tatarka.inject.annotations.Inject
import skillbill.di.core.RuntimeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.TimeSource

/** Thrown when a read reaches a session that has closed; callers treat it as a stale session, not as a cancellation. */
internal class SessionClosedException(cause: Throwable? = null) : IllegalStateException("The database session closed.", cause)

/**
 * The one way to call the session's runtime. Calls run on the database dispatcher as caller children; session cancellation also
 * cancels them before releasing the graph.
 */
@Inject
@SingleIn(DatabaseSessionScope::class)
class RuntimeReads(
  private val runtime: RuntimeComponent,
  private val holder: SessionSelectionHolder,
  private val dispatcher: DatabaseDispatcher,
  private val switch: DiagnosticsSwitch,
  private val diagnostics: Diagnostics,
) {
  suspend fun <T> read(name: String, block: (RuntimeComponent) -> T): T = execute("read", name, block)

  suspend fun <T> write(name: String, block: (RuntimeComponent) -> T): T = execute("write", name, block)

  private suspend fun <T> execute(kind: String, name: String, block: (RuntimeComponent) -> T): T {
    val scope = holder.selection.scope
    if (!scope.isActive) throw SessionClosedException()
    val start = if (switch.enabled) TimeSource.Monotonic.markNow() else null
    try {
      return coroutineScope {
        val call = async(dispatcher.dispatcher) { block(runtime) }
        val sessionJob = scope.coroutineContext[Job]
        val sessionCancellation = sessionJob?.invokeOnCompletion { if (!scope.isActive) call.cancel() }
        try {
          call.await()
        } finally {
          sessionCancellation?.dispose()
        }
      }
    } catch (failure: CancellationException) {
      // The session ended under a caller that is still running: report a stale session instead of cancelling the caller.
      if (currentCoroutineContext().isActive) throw SessionClosedException(failure)
      throw failure
    } finally {
      if (start != null) {
        val elapsed = start.elapsedNow().inWholeMilliseconds
        diagnostics.recordRequest("desktop_$kind", mapOf("elapsed_ms" to elapsed))
        System.err.println("skill-bill-desktop $kind $name ${elapsed}ms")
      }
    }
  }
}
