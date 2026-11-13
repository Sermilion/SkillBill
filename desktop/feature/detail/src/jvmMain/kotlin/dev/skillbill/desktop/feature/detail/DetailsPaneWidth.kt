package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val DETAILS_WIDTH_WRITE_DELAY = 300.milliseconds
private val DETAILS_WIDTH_FLUSH_TIMEOUT = 1.seconds

@Inject
@SingleIn(WindowScope::class)
class DetailsPaneWidth(
  private val settings: AppSettingsRepository,
  private val diagnostics: Diagnostics,
  windowScope: WindowCoroutineScope,
) {
  private val scope = windowScope.scope
  private val state = MutableStateFlow<Int?>(null)
  private var interacted = false
  private var closed = false
  private var revision = 0L
  private var writeJob: Job? = null
  private var pending: PendingWrite? = null
  private val writes = Mutex()

  val widthDp: StateFlow<Int?> = state.asStateFlow()

  init {
    scope.launch {
      settings.settings.map { it.detailsWidthDp }.collect { saved ->
        if (!interacted && !closed) state.value = saved
      }
    }
  }

  fun resizeTo(widthDp: Int) {
    if (closed) return
    val value = widthDp.coerceAtLeast(AppSettings.DETAILS_WIDTH_MIN_DP)
    interacted = true
    state.value = value
    schedule(PendingWrite(++revision, value))
  }

  fun reset() {
    if (closed) return
    interacted = true
    state.value = null
    schedule(PendingWrite(++revision, null))
  }

  suspend fun close() {
    closed = true
    writeJob?.cancel()
    val write = pending ?: return
    val flushed = withTimeoutOrNull(DETAILS_WIDTH_FLUSH_TIMEOUT) {
      persist(write)
      true
    }
    if (flushed == null) diagnostics.record("detail.details_width_save_failed")
  }

  private fun schedule(write: PendingWrite) {
    pending = write
    writeJob?.cancel()
    writeJob = scope.launch {
      delay(DETAILS_WIDTH_WRITE_DELAY)
      persist(write)
    }
  }

  private suspend fun persist(write: PendingWrite) {
    writes.withLock {
      if (pending?.revision != write.revision) return
      when (settings.setDetailsWidthDp(write.widthDp)) {
        is Outcome.Success -> clearPending(write)
        is Outcome.Failure -> diagnostics.record("detail.details_width_save_failed")
      }
    }
  }

  private fun clearPending(write: PendingWrite) {
    if (pending?.revision == write.revision) pending = null
  }

  private data class PendingWrite(val revision: Long, val widthDp: Int?)
}
