package dev.skillbill.desktop.app.host

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

sealed interface HostIntent {
  data object ShowMain : HostIntent
  data class OpenDetail(val item: WorkItem) : HostIntent
  data object OpenSettings : HostIntent
  data object Refresh : HostIntent
  data object Quit : HostIntent
}

@Inject
@SingleIn(AppScope::class)
class HostIntents {
  private val mutable = MutableSharedFlow<HostIntent>(extraBufferCapacity = 16)
  val intents = mutable.asSharedFlow()
  fun send(intent: HostIntent) { mutable.tryEmit(intent) }
}
