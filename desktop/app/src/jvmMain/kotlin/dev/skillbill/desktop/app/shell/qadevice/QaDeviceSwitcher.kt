package dev.skillbill.desktop.app.shell.qadevice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.component.SbPopoverPlacement
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

@Composable
fun QaDeviceSwitcher(state: QaDevicePickerState, modifier: Modifier = Modifier) {
  DisposableEffect(state) { onDispose { state.close() } }
  val ui by state.ui.collectAsState()
  val closed = qaDeviceClosedState(ui.pick, ui.result)
  val focus = remember { FocusRequester() }
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val shape = RoundedCornerShape(SkillBillRadius.md)
  val colors = SkillBillTheme.colors
  val pipColor = when (closed.pip) {
    QaDevicePip.Failed -> colors.stFailed
    QaDevicePip.Blocked -> colors.stBlocked
    QaDevicePip.Done -> colors.stDone
    QaDevicePip.Off -> colors.fgFaint
  }
  Box(modifier.fillMaxWidth().padding(top = 8.dp)) {
    Row(
      Modifier.fillMaxWidth().height(40.dp).clip(shape)
        .background(if (ui.open) colors.bgActive else if (hovered) colors.bgHover else colors.bgPanel)
        .border(1.dp, colors.borderSubtle, shape)
        .sbFocusRing(interaction, shape)
        .focusRequester(focus)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = { state.open() })
        .semantics { contentDescription = "QA device: ${closed.title}, ${closed.subtitle}" }
        .padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        SbIcon(
          when (closed.glyph) {
            QaDeviceGlyph.Auto -> LucideIcons.sparkles
            QaDeviceGlyph.Phone -> LucideIcons.smartphone
            QaDeviceGlyph.Emulator -> LucideIcons.monitor
          },
          SbIconSize.Sm,
        )
        Box(Modifier.align(Alignment.BottomEnd).size(7.dp).background(pipColor, CircleShape).border(1.dp, colors.bgPanel, CircleShape))
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        BasicText(
          closed.title,
          style = (if (closed.titleMono) SkillBillTheme.type.monoSm else SkillBillTheme.type.tSm).copy(color = colors.fg),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (closed.subtitle.isNotEmpty()) BasicText(
          closed.subtitle,
          style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      SbIcon(LucideIcons.chevronsUpDown, SbIconSize.Sm)
    }
    QaDeviceDropdown(state, focus, QaDeviceMenuAnchor.Sidebar, SbPopoverPlacement.Above)
  }
}
