package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.ui.input.ShellLayer

/** HANDOFF 9.2 items 4 and 5: the run-actions row and the lines under it. Pause and Cancel act at once; only Recover confirms. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunActions(
  view: RunActionsView,
  agentChoices: List<String>,
  menuOpen: Boolean,
  recoverFocus: FocusRequester,
  onAction: (ControlsAction) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      view.pauseAfter?.let { ActionButton(it, LucideIcons.pause, "P") { onAction(ControlsAction.PauseAfterSubtask) } }
      view.pauseNow?.let { ActionButton(it, LucideIcons.stop, "⇧ P", SbButtonVariant.Danger) { onAction(ControlsAction.PauseNow) } }
      view.launch?.let { button ->
        val variant = if (button.enabled) SbButtonVariant.Primary else SbButtonVariant.Secondary
        ActionButton(button, LucideIcons.play, "R", variant) { onAction(ControlsAction.Launch) }
      }
      view.agent?.let { button ->
        Box {
          ActionButton(button, LucideIcons.bot) { onAction(ControlsAction.OpenAgentMenu) }
          if (menuOpen) {
            val close = { onAction(ControlsAction.CloseAgentMenu) }
            ShellLayer(close)
            SbPopover(expanded = true, onDismissRequest = close) {
            SbMenu(agentChoices.map { agent -> SbMenuEntry.Item(agent, { onAction(ControlsAction.ChooseAgent(agent)) }, checked = agent == view.agentId) })
            }
          }
        }
      }
      ActionButton(view.cancel, LucideIcons.x, variant = SbButtonVariant.Danger) { onAction(ControlsAction.Cancel) }
      view.recover?.let { ActionButton(it, LucideIcons.refresh, modifier = Modifier.focusRequester(recoverFocus)) { onAction(ControlsAction.Recover) } }
    }
    view.unavailableReason?.let { Hint(it, FeedbackTone.Warn) }
    if (view.launchNote) BasicText("Starts the installed agent harness", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint))
    if (view.pausePending) Hint("Pause recorded. The goal pauses at its next checkpoint.", FeedbackTone.Info, LucideIcons.pause)
    if (view.refreshNeeded) Hint(REFRESH_BEFORE_CONTROLS, FeedbackTone.Warn)
  }
}

@Composable
private fun ActionButton(
  button: ControlButton,
  icon: ImageVector,
  shortcut: String? = null,
  variant: SbButtonVariant = SbButtonVariant.Secondary,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  SbButton(button.label, onClick, modifier, variant, icon = icon, shortcut = shortcut.takeIf { button.enabled }, enabled = button.enabled, tooltip = button.tooltip)
}
