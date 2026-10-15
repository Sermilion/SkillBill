package dev.skillbill.tray.core.data.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The single timer of one subscription owner. Publishing a deadline replaces the armed one, so an append, eviction,
 * refresh, selection change or replacement that moves the earliest deadline re-arms the same timer instead of adding a
 * second. The timer runs in the connection generation's scope; a replacement cancels it and the next [publish] re-arms it.
 */
internal class DeadlineScheduler(private val clock: Clock) {
  private data class Armed(val at: Instant?, val revision: Long)

  private val armed = MutableStateFlow(Armed(null, 0))
  private var runner: Job? = null

  /**
   * Makes [at] the owner's earliest deadline; null disarms. [fire] runs once the deadline passes and must itself verify
   * that the owner is still current. Callers hold the session lock.
   */
  fun publish(scope: CoroutineScope, at: Instant?, fire: suspend () -> Unit) {
    armed.update { Armed(at, it.revision + 1) }
    if (runner?.isActive == true) return
    runner = scope.launch {
      armed.collectLatest { state ->
        val deadline = state.at ?: return@collectLatest
        delay(waitMillis(deadline - clock.now()))
        fire()
      }
    }
  }

  /** Cancels the timer and waits for it, so no fire runs after the owner is released. */
  suspend fun stop() {
    runner?.cancelAndJoin()
    runner = null
  }
}
