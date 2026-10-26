package dev.skillbill.tray.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.composition.EntryComponent
import dev.skillbill.tray.core.designsystem.amber
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.border
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.ui.navigation.EntryComponentHolder
import dev.skillbill.tray.core.ui.rememberViewModel
import dev.skillbill.tray.feature.terminal.TerminalTabs
import dev.skillbill.tray.feature.terminal.TerminalViewModel
import dev.skillbill.tray.lifetime.WindowOwner

/**
 * The window's one terminal-pane ViewModel, held by the window's current entry store: it feeds both the title-bar toggle and the
 * pane, and it ends with the store when a database generation ends. The window's [tabs] outlive it, so the next one keeps them.
 */
@Composable
fun rememberTerminalViewModel(window: WindowOwner, tabs: TerminalTabs): TerminalViewModel {
  val component = rememberViewModel(window.entryStoreOwner, "entry-component:terminal") { EntryComponentHolder<EntryComponent>(window.entries.create()) }.component
  return rememberViewModel(window.entryStoreOwner, "terminal") { component.viewModels.terminal(tabs) }
}

/** The title-bar button for the terminal pane: amber while the pane is open. */
@Composable
fun TerminalToggle(open: Boolean, onClick: () -> Unit) {
  val shape = RoundedCornerShape(6.dp)
  Row(
    Modifier.height(22.dp)
      .background(if (open) amber.copy(alpha = .16f) else Color.Transparent, shape)
      .border(1.dp, if (open) amber.copy(alpha = .55f) else border, shape)
      .clickable(role = Role.Button, onClick = onClick)
      .semantics { stateDescription = if (open) "Open" else "Closed" }
      .padding(horizontal = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Terminal", color = if (open) amber else muted, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 11.sp)
  }
}
