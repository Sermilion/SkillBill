package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.ui.changes.ChangesWindowEntry
import dev.skillbill.desktop.core.ui.changes.ChangesWindows
import dev.skillbill.desktop.core.ui.changes.ChangesSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = ChangesWindows::class)
class ChangesWindowRegistry : ChangesWindows {
  private val mutableCurrent = MutableStateFlow<ChangesWindowEntry?>(null)
  override val current: StateFlow<ChangesWindowEntry?> = mutableCurrent.asStateFlow()

  override fun open(item: WorkItem, scope: ChangeScope) {
    open(ChangesSource.Work(item), scope, null)
  }

  override fun openChat(chat: ChangesSource.Chat, scope: ChangeScope, path: String?) {
    open(chat, scope, path)
  }

  private fun open(source: ChangesSource, scope: ChangeScope, path: String?) {
    mutableCurrent.update { entry ->
      if (entry?.source?.id == source.id) entry.copy(source = source, scope = scope, selectedPath = path, focusRequest = entry.focusRequest + 1)
      else ChangesWindowEntry(source, scope, 0, path)
    }
  }

  override fun close() {
    mutableCurrent.value = null
  }
}
