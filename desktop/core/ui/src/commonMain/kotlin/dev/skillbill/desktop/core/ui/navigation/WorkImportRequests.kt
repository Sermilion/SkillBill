package dev.skillbill.desktop.core.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import dev.skillbill.desktop.core.ui.di.WindowScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

class NavigationEntryToken

val LocalEntryToken = staticCompositionLocalOf<NavigationEntryToken> { error("LocalEntryToken is provided by the shell's navigation host.") }

class WorkImportRequest(val sessionId: Long, val entryToken: NavigationEntryToken)

@Inject
@SingleIn(WindowScope::class)
class WorkImportRequests {
  private val mutablePending = MutableStateFlow<WorkImportRequest?>(null)
  val pending: StateFlow<WorkImportRequest?> = mutablePending.asStateFlow()

  fun submit(request: WorkImportRequest) {
    mutablePending.value = request
  }

  fun consume(request: WorkImportRequest): Boolean {
    return mutablePending.compareAndSet(request, null)
  }

  fun cancel(request: WorkImportRequest) {
    mutablePending.compareAndSet(request, null)
  }

  fun retainFor(entryToken: NavigationEntryToken) {
    val request = mutablePending.value
    if (request?.entryToken !== entryToken) mutablePending.compareAndSet(request, null)
  }
}
