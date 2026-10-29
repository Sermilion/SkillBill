package dev.skillbill.desktop.core.designsystem.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
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

/** [editor] renders in place under the row while [expanded]; [enabled] false makes the row read-only. */
@Composable
fun ConfigRow(
  key: String,
  value: String,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
  expanded: Boolean = false,
  override: Boolean = false,
  defaultValue: String? = null,
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
  Column(modifier.fillMaxWidth()) {
    BoxWithConstraints(
      Modifier.fillMaxWidth().heightIn(min = SkillBillSize.row).background(fill)
        .then(
          if (enabled) Modifier.hoverable(interaction)
            .clickable(interaction, indication = null, onClickLabel = "Edit $key", role = Role.Button, onClick = onToggle)
            .expandSemantics(expanded)
          else Modifier,
        )
        .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
      val compact = maxWidth < 720.dp
      if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          BasicText(key, Modifier.fillMaxWidth(), style = type.tSm.copy(fontFamily = mono, fontWeight = FontWeight.Medium, color = colors.fg))
          Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            tier?.let { TierBadge(it) }
            BasicText(
              value, Modifier.weight(1f),
              style = type.tSm.copy(fontFamily = mono, color = if (override) colors.fg else colors.fgMuted),
              maxLines = 1,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (override) SbLabel("override", SbLabelStyle.Tint(colors.accent))
            tags()
            if (enabled) CompositionLocalProvider(LocalContentColor provides colors.fgFaint) {
              SbIcon(if (expanded) LucideIcons.chevronUp else LucideIcons.pencil, SbIconSize.Sm)
            }
          }
          defaultValue?.let {
            BasicText("default $it", Modifier.fillMaxWidth(), style = type.t2xs.copy(fontFamily = mono, color = colors.fgFaint))
          }
        }
      } else {
        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.Top,
        ) {
          BasicText(
            key,
            Modifier.weight(0.8f, fill = false).widthIn(min = 120.dp, max = 320.dp),
            style = type.tSm.copy(fontFamily = mono, fontWeight = FontWeight.Medium, color = colors.fg),
          )
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
              Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              tier?.let { TierBadge(it) }
              BasicText(
                value, Modifier.weight(1f),
                style = type.tSm.copy(fontFamily = mono, color = if (override) colors.fg else colors.fgMuted),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
              )
              if (override) SbLabel("override", SbLabelStyle.Tint(colors.accent))
              tags()
              if (enabled) CompositionLocalProvider(LocalContentColor provides colors.fgFaint) {
                SbIcon(if (expanded) LucideIcons.chevronUp else LucideIcons.pencil, SbIconSize.Sm)
              }
            }
            defaultValue?.let {
              BasicText("default $it", Modifier.fillMaxWidth(), style = type.t2xs.copy(fontFamily = mono, color = colors.fgFaint))
            }
          }
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
