package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

private val MENU_EDGE_BLEED = 4.dp

sealed interface SbMenuEntry {
  data class Item(val label: String, val onClick: () -> Unit, val icon: ImageVector? = null, val lead: (@Composable () -> Unit)? = null, val endText: String? = null, val checked: Boolean = false, val shortcut: String? = null, val danger: Boolean = false, val enabled: Boolean = true) : SbMenuEntry
  data class Head(val text: String) : SbMenuEntry
  data object Separator : SbMenuEntry
}

@Composable
fun SbMenuItem(item: SbMenuEntry.Item, highlighted: Boolean = false) {
  val interaction = remember { MutableInteractionSource() }
  val active = highlighted || interaction.collectIsHoveredAsState().value || interaction.collectIsFocusedAsState().value
  val fg = when { !item.enabled -> SkillBillTheme.colors.fgFaint; item.danger -> SkillBillTheme.colors.stFailed; active -> SkillBillTheme.colors.fg; else -> SkillBillTheme.colors.fg2 }
  Row(
    Modifier.fillMaxWidth().height(30.dp).background(if (active) SkillBillTheme.colors.bgActive else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(SkillBillRadius.sm))
      .alpha(if (item.enabled) 1f else .4f).hoverable(interaction).clickable(interaction, null, enabled = item.enabled, onClick = item.onClick)
      .sbFocusRing(interaction, RoundedCornerShape(SkillBillRadius.sm))
      .semantics { role = Role.Button; contentDescription = item.label; if (!item.enabled) disabled(); if (item.checked) selected = true }
      .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    CompositionLocalProvider(LocalContentColor provides (if (item.danger) SkillBillTheme.colors.stFailed else if (active) SkillBillTheme.colors.fg else SkillBillTheme.colors.fgMuted)) {
      when { item.lead != null -> item.lead.invoke(); item.icon != null -> SbIcon(item.icon, SbIconSize.Md) }
    }
    BasicText(item.label, Modifier.weight(1f), style = SkillBillTheme.type.tBase.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
    item.endText?.let { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint), maxLines = 1) }
    if (item.checked) SbIcon(LucideIcons.check, SbIconSize.Sm)
    item.shortcut?.let { Kbd(it) }
  }
}

@Composable
fun SbMenu(entries: List<SbMenuEntry>, searchHint: String? = null) {
  val dividerColor = SkillBillTheme.colors.borderSubtle
  val edgeBleedPx = with(androidx.compose.ui.platform.LocalDensity.current) { MENU_EDGE_BLEED.toPx() }
  val dividerStrokePx = with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }
  androidx.compose.foundation.layout.Column(Modifier.padding(4.dp)) {
    searchHint?.let {
      Row(
        Modifier.fillMaxWidth().height(32.dp).drawBehind {
          val y = size.height - dividerStrokePx / 2
          drawLine(dividerColor, Offset(-edgeBleedPx, y), Offset(size.width + edgeBleedPx, y), dividerStrokePx)
        }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        SbIcon(LucideIcons.search, SbIconSize.Sm)
        BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint), maxLines = 1)
      }
      androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
    }
    entries.forEach { entry ->
      when (entry) {
        is SbMenuEntry.Item -> SbMenuItem(entry)
        is SbMenuEntry.Head -> BasicText(entry.text, Modifier.padding(start = 8.dp, top = 6.dp, bottom = 4.dp), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint), maxLines = 1)
        SbMenuEntry.Separator -> Box(
          Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).drawBehind {
            drawLine(dividerColor, Offset(-edgeBleedPx, size.height / 2), Offset(size.width + edgeBleedPx, size.height / 2), dividerStrokePx)
          },
        )
      }
    }
  }
}
