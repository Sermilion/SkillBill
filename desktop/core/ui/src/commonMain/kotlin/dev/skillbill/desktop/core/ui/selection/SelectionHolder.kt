package dev.skillbill.desktop.core.ui.selection

import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.ui.di.WindowScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** The window's focused or open item, which the command menu and the shortcuts act on. Screens set it; a session change clears it. */
@Inject
@SingleIn(WindowScope::class)
class SelectionHolder {
  private val selected = MutableStateFlow<WorkItem?>(null)
  val item: StateFlow<WorkItem?> = selected.asStateFlow()

  fun select(item: WorkItem?) {
    selected.value = item
  }
}
