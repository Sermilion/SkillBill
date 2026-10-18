package dev.skillbill.tray.core.data.session

import dev.skillbill.tray.core.data.port.WorkStoreConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Everything one opened connection owns: the connection, its commit-signal observer and a child scope that every owner
 * callback, deadline timer and in-flight read of the connection runs in. Retiring the generation cancels and joins that
 * scope before the registrations and the connection close, so nothing launched for it can outlive the connection.
 */
internal class SessionGeneration(
  val connection: WorkStoreConnection,
  val observationSession: Long,
  val scope: CoroutineScope,
) {
  var observer: Job? = null
}

/** Names the connection generation and observation session a deadline or owner callback was created for. */
internal data class SessionToken(val generation: Long, val observationSession: Long)
