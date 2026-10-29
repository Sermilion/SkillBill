package dev.skillbill.desktop.feature.specs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Callout
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.IdTag
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecRunState
import dev.skillbill.desktop.core.ui.input.ShellEdit
import dev.skillbill.desktop.core.ui.input.ShellLayer

@Composable
internal fun SpecRunToolbar(state: SpecsUiState, onAction: (SpecsAction) -> Unit) {
  val toolbar = state.toolbar
  val control = (toolbar.control as? SpecControlState.Loaded)?.control
  val key = toolbar.bundleKey ?: control?.issueKey
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    when (val selection = toolbar.selection) {
      ToolbarSelection.None -> Hint(HINT_SELECT_FOLDER)
      is ToolbarSelection.FileInBundle -> Hint(fileHint(selection.key))
      is ToolbarSelection.Bundle -> {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          key?.let { IdTag(it) }
          BasicText(toolbar.bundleTitle.orEmpty(), Modifier.weight(1f), style = SkillBillTheme.type.tBaseMedium.copy(color = SkillBillTheme.colors.fg, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
          control?.let { RunState(it.runState) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          SbButton(control?.startLabel ?: "Start", { onAction(SpecsAction.Start) }, size = SbControlSize.Small, variant = if (control?.canStart == true) SbButtonVariant.Primary else SbButtonVariant.Secondary, icon = LucideIcons.play, enabled = control?.canStart == true && !toolbar.inFlight && !toolbar.reserved)
          Box {
            SbButton(toolbar.chosenAgent?.let { "Agent: $it" } ?: "Choose agent", { onAction(SpecsAction.OpenAgentMenu) }, size = SbControlSize.Small, icon = LucideIcons.bot, enabled = control != null && !toolbar.inFlight && !toolbar.reserved)
            if (toolbar.agentMenuOpen && control != null) {
              val dismiss = { onAction(SpecsAction.DismissAgentMenu) }
              ShellLayer(dismiss)
              SbPopover(expanded = true, onDismissRequest = dismiss) {
                SbMenu(control.agentChoices.map { agent -> SbMenuEntry.Item(agent, { onAction(SpecsAction.ChooseAgent(agent)) }, checked = agent == toolbar.chosenAgent) })
              }
            }
          }
          SbButton(if (control?.pauseRequested == true) "Pause requested" else "Pause", { onAction(SpecsAction.Pause) }, size = SbControlSize.Small, icon = LucideIcons.pause, enabled = control?.canPause == true && control?.pauseRequested == false && !toolbar.inFlight)
          SbButton("Cancel", { onAction(SpecsAction.RequestCancel) }, size = SbControlSize.Small, variant = SbButtonVariant.Danger, icon = LucideIcons.x, enabled = control?.canCancel == true && !toolbar.inFlight)
          Spacer(Modifier.weight(1f))
          if (control?.purgeVisible == true) {
            val label = purgeLabel(key ?: "spec")
            val enabled = control.canPurge && !toolbar.inFlight
            SbTooltip(if (enabled) label else PURGE_DISABLED_TOOLTIP) {
              SbIconButton(LucideIcons.trash, label, { onAction(SpecsAction.RequestPurge) }, enabled = enabled)
            }
          }
        }
        control?.unavailableReason?.let { Hint(it, FeedbackTone.Error) }
        if (toolbar.confirmingCancel && key != null) {
          ShellEdit(onCancel = { onAction(SpecsAction.KeepRunning) }, onSave = {})
          Callout(
            tone = FeedbackTone.Error,
            body = cancelPrompt(key),
            actions = {
              SbButton(KEEP_RUNNING, { onAction(SpecsAction.KeepRunning) }, size = SbControlSize.Small, variant = SbButtonVariant.Ghost)
              SbButton(CANCEL_GOAL, { onAction(SpecsAction.ConfirmCancel) }, size = SbControlSize.Small, variant = SbButtonVariant.DangerSolid)
            },
          )
        } else if (control?.pauseRequested == true) Hint(PAUSE_NOTICE, FeedbackTone.Info)
        when (val load = toolbar.control) {
          is SpecControlState.Unavailable -> Hint(dev.skillbill.desktop.core.ui.feedback.readProblemText(load.problem), FeedbackTone.Error)
          else -> Unit
        }
        if (toolbar.purgeDialogOpen && key != null) PurgeConfirmationDialog(key, onAction)
      }
    }
  }
}

@Composable
private fun RunState(state: SpecRunState) {
  val kind = when (state) {
    SpecRunState.NotStarted -> StatusKind.Pending
    SpecRunState.Running -> StatusKind.Active
    SpecRunState.PauseRequested, SpecRunState.Paused -> StatusKind.Paused
    SpecRunState.Blocked, SpecRunState.Unavailable -> StatusKind.Blocked
    SpecRunState.Canceled -> StatusKind.Canceled
    SpecRunState.Complete -> StatusKind.Completed
  }
  val label = state.name.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }.replace('_', ' ').lowercase()
  BasicText(label, style = SkillBillTheme.type.tXs.copy(color = when (kind) {
    StatusKind.Active -> SkillBillTheme.colors.stActive
    StatusKind.Paused -> SkillBillTheme.colors.stPaused
    StatusKind.Blocked -> SkillBillTheme.colors.stBlocked
    StatusKind.Canceled -> SkillBillTheme.colors.stPending
    StatusKind.Completed -> SkillBillTheme.colors.stDone
    else -> SkillBillTheme.colors.fgFaint
  }))
}
