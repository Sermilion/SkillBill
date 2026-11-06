package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
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
internal fun HomeSectionHeader(title: String, count: Int?, action: @Composable () -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(
    Modifier.fillMaxWidth().heightIn(min = 22.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText(title, Modifier.semantics { heading() }, style = type.tSmMedium.copy(color = colors.fgMuted), maxLines = 1)
    count?.let { BasicText(it.toString(), style = type.tXs.copy(color = colors.fgFaint), maxLines = 1) }
    Spacer(Modifier.weight(1f))
    action()
  }
}

@Composable
internal fun SearchChatsButton(onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  HomeGhostAction(onClick, Modifier.semantics { contentDescription = "Search chats" }) { hovered ->
    BasicText("Search chats", style = SkillBillTheme.type.tSm.copy(color = if (hovered) colors.fg else colors.fgMuted), maxLines = 1)
    BasicText(
      "/resume",
      Modifier.background(colors.bgInset, RoundedCornerShape(3.dp))
        .border(1.dp, colors.border, RoundedCornerShape(3.dp))
        .padding(horizontal = 5.dp, vertical = 2.dp),
      style = inboxTextStyle(10.sp, mono = true).copy(color = colors.fgMuted, lineHeight = 12.sp),
      maxLines = 1,
    )
  }
}

@Composable
internal fun WorkSummaryLink(running: Int, finished: Int, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val description = "$running running · $finished finished today"
  HomeGhostAction(onClick, Modifier.semantics { contentDescription = description }) { hovered ->
    BasicText("●", style = SkillBillTheme.type.tSm.copy(color = colors.stActive), maxLines = 1)
    BasicText(description, style = SkillBillTheme.type.tSm.copy(color = if (hovered) colors.fg else colors.fgMuted), maxLines = 1)
    InboxGlyph(InboxGlyphKind.Arrow, if (hovered) colors.fg else colors.fgMuted)
  }
}

@Composable
private fun HomeGhostAction(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.(hovered: Boolean) -> Unit,
) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val shape = RoundedCornerShape(SkillBillRadius.xs)
  Row(
    modifier.height(22.dp).then(if (hovered) Modifier.background(colors.bgHover, shape) else Modifier)
      .pointerHoverIcon(PointerIcon.Hand)
      .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
      .sbFocusRing(interaction, shape).padding(horizontal = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) { content(hovered) }
}
