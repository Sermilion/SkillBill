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
  private val mutableWindows = MutableStateFlow<List<ChangesWindowEntry>>(emptyList())
  override val windows: StateFlow<List<ChangesWindowEntry>> = mutableWindows.asStateFlow()

  override fun open(item: WorkItem, scope: ChangeScope) {
    mutableWindows.update { entries ->
      val index = entries.indexOfFirst { it.item.id == item.id }
      if (index < 0) entries + ChangesWindowEntry(item, scope, 0)
      else entries.toMutableList().also { values ->
        val current = values[index]
        values[index] = current.copy(item = item, scope = scope, focusRequest = current.focusRequest + 1)
      }
    }
  }

  override fun close(workId: String) {
    mutableWindows.update { entries -> entries.filterNot { it.item.id == workId } }
  }
}
