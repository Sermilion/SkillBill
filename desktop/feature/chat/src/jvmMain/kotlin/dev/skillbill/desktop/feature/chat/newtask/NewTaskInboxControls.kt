package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Kbd
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
  val colors = SkillBillTheme.colors
  Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    listOf(Triple(InboxFilter.All, "All", null), Triple(InboxFilter.NeedsYou, "Needs you", needsCount), Triple(InboxFilter.Chats, "Chats", savedCount)).forEach { (filter, label, count) ->
      val active = selected == filter
      val interaction = remember(filter) { MutableInteractionSource() }
      val hovered by interaction.collectIsHoveredAsState()
      val shape = RoundedCornerShape(4.dp)
      Row(
        Modifier.height(22.dp)
          .then(if (active) Modifier.background(colors.bgActive, shape) else Modifier)
          .pointerHoverIcon(PointerIcon.Hand)
          .clickable(interaction, indication = null, role = Role.Tab, onClick = { onSelect(filter) })
          .sbFocusRing(interaction, shape)
          .semantics { this.selected = active; contentDescription = label }
          .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        val foreground = if (active) colors.fg else if (hovered) colors.fg2 else colors.fgFaint
        BasicText(label, style = inboxTextStyle(12.sp).copy(color = foreground), maxLines = 1)
        count?.let {
          val countColor = if (filter == InboxFilter.NeedsYou && it > 0) colors.stBlocked else colors.fgFaint
          BasicText(it.toString(), style = inboxTextStyle(10.5.sp).copy(color = countColor), maxLines = 1)
        }
      }
    }
  }
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
