package dev.skillbill.desktop.feature.specs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Avatar
import dev.skillbill.desktop.core.designsystem.component.Callout
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.IdTag
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecRunState
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.input.ShellEdit
import dev.skillbill.desktop.core.ui.input.ShellLayer

/** The toolbar section of the pane (`.sb-toolbar`): one hint, or the selected bundle's header, run buttons and their notices. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpecRunToolbar(state: SpecsUiState, onAction: (SpecsAction) -> Unit, modifier: Modifier = Modifier) {
  val toolbar = state.toolbar
  val control = (toolbar.control as? SpecControlState.Loaded)?.control
  val key = toolbar.bundleKey ?: control?.issueKey
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    when (val selection = toolbar.selection) {
      ToolbarSelection.None -> Hint(HINT_SELECT_FOLDER)
      is ToolbarSelection.FileInBundle -> Hint(fileHint(selection.key))
      is ToolbarSelection.Bundle -> {
        Row(Modifier.fillMaxWidth().heightIn(min = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          key?.let { IdTag(it) }
          BasicText(toolbar.bundleTitle.orEmpty(), Modifier.weight(1f), style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
          control?.let { RunState(it.runState) }
        }
        val canStart = control?.canStart == true && !toolbar.inFlight && !toolbar.reserved
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          SbButton(control?.startLabel ?: "Start", { onAction(SpecsAction.Start) }, size = SbControlSize.Small, variant = if (control?.canStart == true) SbButtonVariant.Primary else SbButtonVariant.Secondary, icon = LucideIcons.play, enabled = canStart)
          Box {
            SbButton(toolbar.chosenAgent?.let { "Agent: $it" } ?: "Choose agent", { onAction(SpecsAction.OpenAgentMenu) }, size = SbControlSize.Small, icon = LucideIcons.bot, enabled = canStart)
            if (toolbar.agentMenuOpen && control != null) {
              val dismiss = { onAction(SpecsAction.DismissAgentMenu) }
              ShellLayer(dismiss)
              SbPopover(expanded = true, onDismissRequest = dismiss) {
                SbMenu(
                  listOf(SbMenuEntry.Head(RUN_ON)) + control.agentChoices.map { agent ->
                    SbMenuEntry.Item(agent, { onAction(SpecsAction.ChooseAgent(agent)) }, lead = { Avatar(agent, small = true, tooltip = null) }, checked = agent == toolbar.chosenAgent)
                  },
                )
              }
            }
          }
          SbButton(if (control?.pauseRequested == true) "Pause requested" else "Pause", { onAction(SpecsAction.Pause) }, size = SbControlSize.Small, icon = LucideIcons.pause, enabled = control?.canPause == true && control?.pauseRequested == false && !toolbar.inFlight)
          SbButton("Cancel", { onAction(SpecsAction.RequestCancel) }, size = SbControlSize.Small, variant = SbButtonVariant.Danger, icon = LucideIcons.x, enabled = control?.canCancel == true && !toolbar.inFlight)
          if (control?.purgeVisible == true) {
            Spacer(Modifier.weight(1f))
            val enabled = control.canPurge && !toolbar.inFlight
            // screens.js renders this icon button at full strength in both states (`S.IconButton` has no disabled form); the
            // label, which is also its tooltip, carries the reason and the click does nothing while purging is unavailable.
            SbIconButton(LucideIcons.trash, if (enabled) purgeLabel(key ?: "spec") else PURGE_DISABLED_TOOLTIP, { if (enabled) onAction(SpecsAction.RequestPurge) }, size = SbControlSize.Small)
          }
        }
        control?.unavailableReason?.let { Hint(it, FeedbackTone.Error) }
        if (toolbar.confirmingCancel && key != null) {
          ShellEdit(onCancel = { onAction(SpecsAction.KeepRunning) }, onSave = {})
          Callout(
            tone = FeedbackTone.Error,
            body = cancelPrompt(key),
            actions = {
              SbButton(KEEP_RUNNING, { onAction(SpecsAction.KeepRunning) }, size = SbControlSize.Small)
              SbButton(CANCEL_GOAL, { onAction(SpecsAction.ConfirmCancel) }, size = SbControlSize.Small, variant = SbButtonVariant.DangerSolid)
            },
          )
        }
        if (control?.pauseRequested == true) Hint(PAUSE_NOTICE, FeedbackTone.Info)
        when (val load = toolbar.control) {
          is SpecControlState.Unavailable -> Hint(readProblemText(load.problem), FeedbackTone.Error)
          else -> Unit
        }
        if (toolbar.purgeDialogOpen && key != null) PurgeConfirmationDialog(key, onAction)
      }
    }
  }
}

/** The run state as a dot label (`S.Label`): amber while running, orange while paused, faint otherwise. */
@Composable
private fun RunState(state: SpecRunState) {
  val colors = SkillBillTheme.colors
  val dot = when (state) {
    SpecRunState.Running -> colors.stActive
    SpecRunState.Paused, SpecRunState.Blocked -> colors.stBlocked
    else -> colors.fgFaint
  }
  val label = state.name.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }.lowercase()
  SbLabel(label, SbLabelStyle.Dot(dot))
}
