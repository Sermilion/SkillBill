package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Kbd
import dev.skillbill.desktop.core.designsystem.component.ViewTab
import dev.skillbill.desktop.core.designsystem.component.ViewTabs
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing

@Composable
internal fun inboxTextStyle(size: TextUnit = 13.sp, mono: Boolean = false): TextStyle {
  val type = SkillBillTheme.type
  return (if (mono) type.mono else type.tBase).copy(
    fontSize = size,
    lineHeight = size * 1.45f,
    fontWeight = FontWeight.Normal,
    fontFeatureSettings = if (mono) null else "cv11, ss01",
  )
}

@Composable
internal fun InboxFilterControl(selected: InboxFilter, needsCount: Int, savedCount: Int, onSelect: (InboxFilter) -> Unit) {
  ViewTabs(
    listOf(
      ViewTab(InboxFilter.All, "All"),
      ViewTab(InboxFilter.NeedsYou, "Needs you", count = needsCount, attention = needsCount > 0),
      ViewTab(InboxFilter.Chats, "Chats", count = savedCount),
    ),
    selected,
    onSelect,
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InboxHint(home: NewTaskHome.Returning?, showAll: Boolean, onShowAll: () -> Unit, onShowAllWork: () -> Unit) {
  val colors = SkillBillTheme.colors
  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
      Kbd("1")
      BasicText("–", style = inboxTextStyle(11.5.sp).copy(color = colors.fgFaint))
      Kbd("9")
      BasicText("open", style = inboxTextStyle(11.5.sp).copy(color = colors.fgFaint))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
      Kbd("↑")
      Kbd("↓")
      BasicText("move", style = inboxTextStyle(11.5.sp).copy(color = colors.fgFaint))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
      Kbd("Del")
      BasicText("discard chat", style = inboxTextStyle(11.5.sp).copy(color = colors.fgFaint))
    }
    if (home != null) InboxHintLink(
      "${home.finished} finished today", onShowAllWork, arrow = true,
      description = "${home.running} running · ${home.finished} finished today",
    )
    if (showAll) InboxHintLink("Show all", onShowAll)
  }
}

@Composable
private fun InboxHintLink(text: String, onClick: () -> Unit, arrow: Boolean = false, description: String? = null) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  Row(
    Modifier.pointerHoverIcon(PointerIcon.Hand)
      .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
      .semantics { if (description != null) contentDescription = description }
      .sbFocusRing(interaction, RoundedCornerShape(3.dp)),
    horizontalArrangement = Arrangement.spacedBy(5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val color = if (hovered) colors.fg else colors.fgFaint
    BasicText(text, style = inboxTextStyle(11.5.sp).copy(color = color))
    if (arrow) InboxGlyph(InboxGlyphKind.Arrow, color)
  }
}
