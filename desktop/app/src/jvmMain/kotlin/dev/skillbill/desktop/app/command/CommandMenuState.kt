package dev.skillbill.desktop.app.command

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.core.ui.di.WindowScope
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Whether the command menu is open and what is typed in it; the highlighted row belongs to `CommandPalette`. */
@Inject
@SingleIn(WindowScope::class)
class CommandMenuState {
  var open by mutableStateOf(false)
    private set
  var query by mutableStateOf("")

  fun toggle() {
    open = !open
    query = ""
  }

  fun close() {
    open = false
    query = ""
  }
}
