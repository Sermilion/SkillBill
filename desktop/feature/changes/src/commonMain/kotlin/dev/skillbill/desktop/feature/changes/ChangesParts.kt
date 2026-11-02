package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.ui.changes.changesMono
import dev.skillbill.desktop.core.ui.changes.changesSans

/** A keyboard cap: a raised 2 dp bottom edge around the key's name. */
@Composable
internal fun KeyCap(key: String) {
  val colors = SkillBillTheme.colors
  Box(
    Modifier.background(colors.borderStrong, RoundedCornerShape(4.dp))
      .padding(start = 1.dp, top = 1.dp, end = 1.dp, bottom = 2.dp)
      .background(colors.bgRaised, RoundedCornerShape(3.dp))
      .padding(horizontal = 4.dp, vertical = 2.dp),
  ) {
    BasicText(key, style = changesMono(10f, FontWeight.Medium, colors.fgMuted), maxLines = 1, softWrap = false)
  }
}

/** An accent text link, underlined on hover. An empty link still takes the click, as the scope bar's end arrows do. */
@Composable
internal fun ChangesLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val style = changesSans(11.5f, FontWeight.Medium, SkillBillTheme.colors.accentText)
  BasicText(
    text,
    modifier.pointerHoverIcon(PointerIcon.Hand).clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
    style = if (hovered) style.copy(textDecoration = TextDecoration.Underline) else style,
    maxLines = 1,
    softWrap = false,
  )
}

/** A pane's message in place of its content. */
@Composable
internal fun PaneMessage(text: String, modifier: Modifier = Modifier) {
  BasicText(
    text,
    modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 40.dp),
    style = changesSans(12.5f, FontWeight.Normal, SkillBillTheme.colors.fgMuted, lineHeight = 18.75f).copy(textAlign = TextAlign.Center),
  )
}

@Composable
internal fun ChangeStatus.statusColor(): Color {
  val colors = SkillBillTheme.colors
  return when (this) {
    ChangeStatus.Added, ChangeStatus.Untracked -> colors.stDone
    ChangeStatus.Modified -> colors.accentText
    ChangeStatus.Deleted -> colors.stFailed
    ChangeStatus.Renamed -> colors.stPlanning
  }
}

internal fun ChangeStatus.letter() = when (this) {
  ChangeStatus.Added -> "A"
  ChangeStatus.Modified -> "M"
  ChangeStatus.Deleted -> "D"
  ChangeStatus.Renamed -> "R"
  ChangeStatus.Untracked -> "U"
}
