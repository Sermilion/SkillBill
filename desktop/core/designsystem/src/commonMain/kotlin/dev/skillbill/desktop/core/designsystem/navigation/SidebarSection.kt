package dev.skillbill.desktop.core.designsystem.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.expandSemantics
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.menuSpec

@Composable
fun SidebarSection(
  label: String,
  expanded: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
  collapsible: Boolean = true,
  items: @Composable ColumnScope.() -> Unit,
) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  val rotation = animateFloatAsState(if (expanded) 0f else -90f, menuSpec(), label = "sidebar-section-chevron").value
  val color = if (hovered) colors.fgMuted else colors.fgFaint
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
    Row(
      Modifier.padding(top = 12.dp).fillMaxWidth().height(28.dp)
        .hoverable(interaction)
        .then(if (collapsible) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(interaction, indication = null, role = Role.Button, onClick = onToggle).expandSemantics(expanded) else Modifier)
        .padding(horizontal = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      BasicText(label, style = SkillBillTheme.type.tXs.copy(color = color, fontWeight = FontWeight.Medium), maxLines = 1)
      CompositionLocalProvider(LocalContentColor provides color) {
        SbIcon(LucideIcons.chevronDown, SbIconSize.Xs, modifier = Modifier.rotate(rotation))
      }
    }
    if (expanded || !collapsible) items()
  }
}
