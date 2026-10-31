package dev.skillbill.desktop.core.ui.changes

import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.flow.StateFlow

data class ChangesWindowEntry(val item: WorkItem, val scope: ChangeScope, val focusRequest: Int)

interface ChangesWindows {
  val windows: StateFlow<List<ChangesWindowEntry>>
  fun open(item: WorkItem, scope: ChangeScope)
  fun close(workId: String)
}
