package dev.skillbill.tray.feature.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key as pressedKey
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.amber
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted

private val UNDERLINE_HEIGHT = 2.dp

/**
 * The pane's tabs in one horizontally scrolling row, so tabs that do not fit scroll instead of shrinking. Left and right arrows on a
 * focused tab select the neighbour, wrapping at the ends, and move focus with the selection.
 */
@Composable
internal fun TerminalTabStrip(
  tabs: List<TerminalTab>,
  activeTabId: Long?,
  onSelect: (Long) -> Unit,
  onClose: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  val requesters = remember { mutableMapOf<Long, FocusRequester>() }
  requesters.keys.retainAll(tabs.mapTo(mutableSetOf(), TerminalTab::id))
  Row(modifier.horizontalScroll(rememberScrollState()).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
    tabs.forEachIndexed { index, tab ->
      key(tab.id) {
        val requester = requesters.getOrPut(tab.id) { FocusRequester() }
        val active = tab.id == activeTabId
        val select: (Int) -> Boolean = { offset ->
          val target = tabs[(index + offset).mod(tabs.size)]
          onSelect(target.id)
          requesters[target.id]?.requestFocus()
          true
        }
        Row(
          Modifier.fillMaxHeight().drawBehind {
            if (active) {
              val thickness = UNDERLINE_HEIGHT.toPx()
              drawRect(amber, Offset(0f, size.height - thickness), Size(size.width, thickness))
            }
          },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
            Modifier.fillMaxHeight()
              .focusRequester(requester)
              .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.pressedKey) {
                  Key.DirectionLeft -> select(-1)
                  Key.DirectionRight -> select(1)
                  else -> false
                }
              }
              .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab.id) })
              .padding(start = 8.dp, end = 4.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(tab.label, color = if (active) ink else muted, fontFamily = body, fontSize = 11.sp)
          }
          Box(
            Modifier.size(18.dp).clickable(role = Role.Button) { onClose(tab.id) }.clearAndSetSemantics {
              contentDescription = "Close ${tab.label} tab"
              role = Role.Button
            },
            contentAlignment = Alignment.Center,
          ) {
            Text("×", color = dim, fontFamily = mono, fontSize = 13.sp)
          }
        }
      }
    }
  }
}
