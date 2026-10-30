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
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.handCursor
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

private val MENU_EDGE_BLEED = 4.dp

sealed interface SbMenuEntry {
  data class Item(val label: String, val onClick: () -> Unit, val icon: ImageVector? = null, val lead: (@Composable () -> Unit)? = null, val endText: String? = null, val checked: Boolean = false, val shortcut: String? = null, val danger: Boolean = false, val enabled: Boolean = true, val mono: Boolean = false) : SbMenuEntry
  data class Head(val text: String) : SbMenuEntry
  data object Separator : SbMenuEntry
}

@Composable
fun SbMenuItem(item: SbMenuEntry.Item, highlighted: Boolean = false) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  // `[disabled]` sets pointer-events: none, so a disabled item never takes the hover fill.
  val active = item.enabled && (highlighted || interaction.collectIsHoveredAsState().value || interaction.collectIsFocusedAsState().value)
  // Danger keeps its red on hover; disabled items keep their color and fade to 40%.
  val fg = when { item.danger -> colors.stFailed; active -> colors.fg; else -> colors.fg2 }
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  Row(
    Modifier.fillMaxWidth().height(30.dp).background(if (active) colors.bgActive else androidx.compose.ui.graphics.Color.Transparent, shape)
      .alpha(if (item.enabled) 1f else .4f).hoverable(interaction).clickable(interaction, null, enabled = item.enabled, onClick = item.onClick).handCursor(item.enabled)
      .sbFocusRing(interaction, shape)
      .semantics { role = Role.Button; contentDescription = item.label; if (!item.enabled) disabled(); if (item.checked) selected = true }
      .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // `.sb-menu-item .sb-icon` stays fg-muted on hover.
    CompositionLocalProvider(LocalContentColor provides (if (item.danger) colors.stFailed else colors.fgMuted)) {
      when { item.lead != null -> item.lead.invoke(); item.icon != null -> SbIcon(item.icon, SbIconSize.Md) }
    }
    // `<span class="mono">` labels switch family only and keep the item's 13px size.
    BasicText(item.label, Modifier.weight(1f), style = SkillBillTheme.type.tBase.copy(color = fg, lineHeight = 1.em, fontFamily = if (item.mono) SkillBillTheme.type.mono.fontFamily else SkillBillTheme.type.tBase.fontFamily), maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (item.endText != null || item.checked || item.shortcut != null) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        item.endText?.let { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint, lineHeight = 1.em), maxLines = 1, softWrap = false) }
        if (item.checked) CompositionLocalProvider(LocalContentColor provides (if (item.danger) colors.stFailed else colors.fgMuted)) { SbIcon(LucideIcons.check, SbIconSize.Sm) }
        item.shortcut?.let { Kbd(it) }
      }
    }
  }
}

@Composable
fun SbMenu(entries: List<SbMenuEntry>, searchHint: String? = null) {
  val colors = SkillBillTheme.colors
  val dividerColor = colors.borderSubtle
  val edgeBleedPx = with(androidx.compose.ui.platform.LocalDensity.current) { MENU_EDGE_BLEED.toPx() }
  val dividerStrokePx = with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }
  androidx.compose.foundation.layout.Column {
    // `.sb-menu-search` cancels the popover padding with margin: -4px -4px 4px.
    searchHint?.let {
      Row(
        Modifier.fillMaxWidth().height(32.dp).drawBehind {
          val y = size.height - dividerStrokePx / 2
          drawLine(dividerColor, Offset(0f, y), Offset(size.width, y), dividerStrokePx)
        // border-box height: the 1px border-bottom comes out of the 32px, so content centers in 31px.
        }.padding(start = 8.dp, end = 8.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(LucideIcons.search, SbIconSize.Sm) }
        BasicText(it, style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint, lineHeight = 1.em), maxLines = 1, softWrap = false)
      }
    }
    androidx.compose.foundation.layout.Column(Modifier.padding(MENU_EDGE_BLEED)) {
      entries.forEach { entry ->
        when (entry) {
          is SbMenuEntry.Item -> SbMenuItem(entry)
          is SbMenuEntry.Head -> BasicText(entry.text, Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 4.dp), style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint, lineHeight = 1.em), maxLines = 1)
          SbMenuEntry.Separator -> Box(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).drawBehind {
              drawLine(dividerColor, Offset(-edgeBleedPx, size.height / 2), Offset(size.width + edgeBleedPx, size.height / 2), dividerStrokePx)
            },
          )
        }
      }
    }
  }
}
