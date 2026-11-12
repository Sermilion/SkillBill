package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbSpinner
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons

@Composable
internal fun RunAppSplitButton(
  view: RunAppView,
  deviceMenu: @Composable (FocusRequester) -> Unit,
  onRun: () -> Unit,
  onStop: () -> Unit,
  onOpenDevices: () -> Unit,
) {
  val focus = remember { FocusRequester() }
  val variant = if (view.enabled) SbButtonVariant.Primary else SbButtonVariant.Secondary
  val labelColor = if (view.enabled) SkillBillTheme.colors.accentFg else SkillBillTheme.colors.fg
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    val buttonLabel = if (view.busy) view.label else "${view.label} · ${view.device}"
    val mainButton: @Composable () -> Unit = {
      SbButton(
        label = buttonLabel,
        onClick = onRun,
        variant = variant,
        icon = if (view.busy) null else LucideIcons.play,
        enabled = view.enabled && !view.busy,
        tooltip = view.tooltip.takeIf { view.enabled || view.busy },
        contentSlot = {
          if (view.busy) SbSpinner(size = 13.dp, contentDescription = "Building")
          BasicText(
            if (view.busy) view.label else "Run app",
            style = SkillBillTheme.type.tSmMedium.copy(color = labelColor),
            maxLines = 1,
            softWrap = false,
          )
          if (!view.busy) {
            BasicText("·", style = SkillBillTheme.type.tSm.copy(color = labelColor))
            BasicText(
              view.device,
              style = (if (view.monospace) SkillBillTheme.type.monoSm else SkillBillTheme.type.tSm).copy(color = labelColor),
              maxLines = 1,
              softWrap = false,
            )
          }
        },
      )
    }
    if (view.enabled || view.busy) mainButton() else SbTooltip(view.tooltip, content = mainButton)
    Box {
      if (view.busy) SbButton(null, onStop, icon = LucideIcons.stop, variant = SbButtonVariant.Danger, tooltip = "Stop the build")
      else SbButton(null, onOpenDevices, Modifier.focusRequester(focus), icon = LucideIcons.chevronDown, enabled = true, tooltip = "Choose device")
      deviceMenu(focus)
    }
  }
}

@Composable
internal fun RunAppStatusLine(status: RunAppStatusView, onOpenLog: () -> Unit) {
  val colors = SkillBillTheme.colors
  val tone = when (status.tone) {
    RunAppTone.Building, RunAppTone.Running -> colors.stActive
    RunAppTone.Failed -> colors.stFailed
    RunAppTone.Contended -> colors.stBlocked
    RunAppTone.Stopped -> colors.fgMuted
    RunAppTone.Off -> colors.fgFaint
  }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    if (status.tone == RunAppTone.Building) SbSpinner(size = 10.dp, color = tone)
    else Box(Modifier.background(tone, CircleShape).padding(3.dp))
    BasicText(status.text, Modifier.weight(1f, fill = false), style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted))
    status.logLabel?.let { label ->
      BasicText(
        label,
        Modifier.clickable(role = Role.Button, onClick = onOpenLog).padding(horizontal = 2.dp),
        style = SkillBillTheme.type.tXs.copy(color = colors.accent),
        maxLines = 1,
      )
    }
  }
}
