package dev.skillbill.desktop.core.ui.usage.model

import dev.skillbill.desktop.core.domain.model.ChatAgent

sealed interface SelectedNewTaskUsageContext {
  val selectionToken: Long

  data class Absent(override val selectionToken: Long) : SelectedNewTaskUsageContext

  data class Pending(override val selectionToken: Long) : SelectedNewTaskUsageContext

  data class Ready(
    override val selectionToken: Long,
    val agent: ChatAgent,
    val logicalSessionId: String?,
    val activeWork: Boolean,
  ) : SelectedNewTaskUsageContext
}
