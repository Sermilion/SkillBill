package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.common.DatabaseDispatcher
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.data.di.DatabaseSessionScope
import dev.skillbill.desktop.core.data.session.SessionSelectionHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import skillbill.di.core.RuntimeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.TimeSource

/** Thrown when a read reaches a session that has closed; callers treat it as a stale session, not as a cancellation. */
internal class SessionClosedException(cause: Throwable? = null) : IllegalStateException("The database session closed.", cause)

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
      return runSessionOwnedCall(scope, dispatcher.dispatcher) { block(runtime) }
    } finally {
      if (start != null) {
        val elapsed = start.elapsedNow().inWholeMilliseconds
        diagnostics.recordRequest("desktop_$kind", mapOf("elapsed_ms" to elapsed))
        System.err.println("skill-bill-desktop $kind $name ${elapsed}ms")
      }
    }
  }
}

internal suspend fun <T> runSessionOwnedCall(
  scope: kotlinx.coroutines.CoroutineScope,
  dispatcher: kotlinx.coroutines.CoroutineDispatcher,
  block: () -> T,
): T {
  val call = scope.async(dispatcher) { block() }
  try {
    return call.await()
  } catch (failure: CancellationException) {
    withContext(NonCancellable) { call.join() }
    if (currentCoroutineContext().isActive) throw SessionClosedException(failure)
    throw failure
  }
}
