package dev.skillbill.desktop.core.ui.changes

import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.flow.StateFlow

data class ChangesWindowEntry(val item: WorkItem, val scope: ChangeScope, val focusRequest: Int)

/** The one Changes panel shown over the shell, if any. It is modal, so opening another execution replaces the current one. */
interface ChangesWindows {
  val current: StateFlow<ChangesWindowEntry?>
  fun open(item: WorkItem, scope: ChangeScope)
  fun close()
}
