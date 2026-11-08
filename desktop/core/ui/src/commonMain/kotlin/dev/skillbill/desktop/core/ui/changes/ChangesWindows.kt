package dev.skillbill.desktop.core.ui.changes

import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.flow.StateFlow

sealed interface ChangesSource {
  val id: String

  data class Work(val item: WorkItem) : ChangesSource {
    override val id: String get() = item.id
  }

  data class Chat(val sessionId: String, val title: String, val target: ChangeScopeTarget) : ChangesSource {
    override val id: String get() = "chat:" + sessionId
  }
}

data class ChangesWindowEntry(val source: ChangesSource, val scope: ChangeScope, val focusRequest: Int, val selectedPath: String? = null)

/** The one Changes panel shown over the shell, if any. It is modal, so opening another execution replaces the current one. */
interface ChangesWindows {
  val current: StateFlow<ChangesWindowEntry?>
  fun open(item: WorkItem, scope: ChangeScope)
  fun openChat(chat: ChangesSource.Chat, scope: ChangeScope, path: String? = null)
  fun close()
}
