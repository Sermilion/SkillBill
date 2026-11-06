package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused

/** Lists every open chat and filters larger sets by title or repository. */
@Composable
internal fun NewTaskChatOverflowMenu(
  expanded: Boolean,
  items: List<OpenChatItem>,
  selected: NewTaskDraft,
  onPick: (NewTaskDraft) -> Unit,
  onDismiss: () -> Unit,
  returnFocusTo: FocusRequester?,
) {
  var query by remember { mutableStateOf("") }
  val inputFocus = remember { FocusRequester() }
  LaunchedEffect(expanded) {
    if (expanded) {
      query = ""
      if (items.size > 8) {
        withFrameNanos { }
        inputFocus.requestFocus()
      }
    }
  }
  SbPopover(expanded, onDismiss, width = 280.dp, returnFocusTo = returnFocusTo) {
    if (items.size > 8) {
      SbInput(query, { query = it }, Modifier.fillMaxWidth().focusRequester(inputFocus).gatesShortcutsWhileFocused(), placeholder = "Filter open chats", leadingIcon = LucideIcons.search)
    }
    val matches = filterOpenChats(items, if (items.size > 8) query else "")
    if (matches.isEmpty()) {
      BasicText("No open chat matches.", Modifier.padding(horizontal = 8.dp, vertical = 10.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint))
    } else {
      Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
        matches.forEach { item ->
          key(item.draft) {
            OpenChatRow(item, selected = item.draft === selected, onClick = { onPick(item.draft) }, onClose = null)
          }
        }
      }
    }
  }
}
