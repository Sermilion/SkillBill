package dev.skillbill.desktop.core.designsystem.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.rememberHoverColor
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.expandSemantics
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

private val phaseColumnWidth = 156.dp
private val settingValueMaxWidth = 280.dp

/**
 * Two layouts share one row. Without [label] it is the model-matrix row: [key] in a 156 dp mono column, the
 * [tier] badge, the resolved [value], tags and an edit icon, 36 dp tall. With [label] it reads like a setting
 * row: [label] and [description] on the left, the mono [value] on the right. [editor] renders in place under the
 * row while [expanded]; [enabled] false makes the row read-only. [override] adds the "override" tag,
 * [tierChanged] the "tier changed" tag, and [emphasized] draws the value in the foreground color.
 */
@Composable
fun ConfigRow(
  key: String,
  value: String,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
  expanded: Boolean = false,
  override: Boolean = false,
  emphasized: Boolean = override,
  tierChanged: Boolean = false,
  label: String? = null,
  description: String? = null,
  tier: Tier? = null,
  enabled: Boolean = true,
  tags: @Composable RowScope.() -> Unit = {},
  editor: (@Composable ColumnScope.() -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val mono = type.mono.fontFamily
  val interaction = remember { MutableInteractionSource() }
  val hovered = enabled && interaction.collectIsHoveredAsState().value
  val fill = rememberHoverColor(hovered, if (expanded) colors.bgActive else Color.Transparent, if (expanded) colors.bgActive else colors.bgHover).value
  val interactive = if (enabled) {
    Modifier.hoverable(interaction)
      .clickable(interaction, indication = null, onClickLabel = "Edit ${label ?: key}", role = Role.Button, onClick = onToggle)
      .expandSemantics(expanded)
  } else Modifier
  val trailing: @Composable RowScope.() -> Unit = {
    if (override) SbLabel("override", SbLabelStyle.Tint(colors.accent))
    if (tierChanged) SbLabel("tier changed")
    tags()
    if (enabled) CompositionLocalProvider(LocalContentColor provides colors.fg) {
      SbIcon(if (expanded) LucideIcons.chevronUp else LucideIcons.pencil, SbIconSize.Sm)
    }
  }
  Column(modifier.fillMaxWidth()) {
    if (label == null) {
      Row(
        // `.sb-cfg` is 36px including the divider above it, which SettingsGroup draws as a separate 1 dp line.
        Modifier.fillMaxWidth().height(if (LocalSettingsRowDivided.current) SkillBillSize.row - 1.dp else SkillBillSize.row).background(fill).then(interactive)
          .padding(start = 16.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        BasicText(
          key, Modifier.width(phaseColumnWidth),
          style = type.tSm.copy(fontFamily = mono, fontWeight = FontWeight.Medium, color = colors.fg),
          maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        tier?.let { TierBadge(it) }
        BasicText(
          value, Modifier.weight(1f),
          style = type.tSm.copy(fontFamily = mono, fontWeight = FontWeight.Normal, color = if (emphasized) colors.fg else colors.fgMuted),
          maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        trailing()
      }
    } else {
      Row(
        Modifier.fillMaxWidth().background(fill).then(interactive).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          BasicText(label, style = type.tBaseMedium.copy(color = colors.fg))
          description?.let { BasicText(it, style = type.tSm.copy(color = colors.fgMuted, lineHeight = 1.45.em)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          tier?.let { TierBadge(it) }
          BasicText(
            value, Modifier.widthIn(max = settingValueMaxWidth),
            style = type.tSm.copy(fontFamily = mono, fontWeight = FontWeight.Normal, color = if (emphasized) colors.fg else colors.fgFaint),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
          )
          trailing()
        }
      }
    }
    if (expanded && editor != null) {
      Column(
        Modifier.fillMaxWidth().background(colors.bgRaised).hairline(Edge.Top, colors.borderSubtle).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = editor,
      )
    }
  }
}
