package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.ui.changes.ChangesWindowEntry
import dev.skillbill.desktop.core.ui.changes.ChangesWindows
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
    mutableCurrent.update { entry ->
      if (entry?.item?.id == item.id) entry.copy(item = item, scope = scope, focusRequest = entry.focusRequest + 1)
      else ChangesWindowEntry(item, scope, 0)
    }
  }

  override fun close() {
    mutableCurrent.value = null
  }
}
