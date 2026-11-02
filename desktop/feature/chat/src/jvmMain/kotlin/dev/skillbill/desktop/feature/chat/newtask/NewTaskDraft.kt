package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.core.ui.di.WindowScope
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

data class NewTaskRepository(val root: String, val name: String)

/** What the operator set up on New task. It lives with the window, so navigation and session switches keep it. */
@Inject
@SingleIn(WindowScope::class)
class NewTaskDraft {
  var text: String by mutableStateOf("")
  var repository: NewTaskRepository? by mutableStateOf(null)
  var baseBranch: String by mutableStateOf("")
  var agentId: String? by mutableStateOf(null)
}
