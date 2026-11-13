package dev.skillbill.desktop.feature.specs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Avatar
import dev.skillbill.desktop.core.designsystem.component.agentDisplayName
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.IdTag
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbDialog
import dev.skillbill.desktop.core.designsystem.component.SbDialogText
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbPopoverPlacement
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecNode
import dev.skillbill.desktop.core.domain.model.SpecRunState
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.input.ShellLayer

@Composable
internal fun SpecSelectionBar(state: SpecsUiState, onAction: (SpecsAction) -> Unit, modifier: Modifier = Modifier) {
  val toolbar = state.toolbar
  val bundlePath = (toolbar.selection as? ToolbarSelection.Bundle)?.path ?: return
  val roots = (state.tree as? dev.skillbill.desktop.core.domain.model.SpecTreeState.Loaded)?.tree?.roots.orEmpty()
  val bundle = findNode(roots, bundlePath) as? SpecNode.Bundle ?: return
  val loaded = toolbar.control as? SpecControlState.Loaded
  val control = loaded?.control
  val canStart = control?.canStart == true
  val canPause = loaded?.let { it.control.canPause && it.liveGoal != null } == true
  val primaryEnabled = !toolbar.inFlight && !toolbar.reserved
  val key = toolbar.bundleKey ?: bundle.issueKey ?: bundle.name
  Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      IdTag(key)
      BasicText(toolbar.bundleTitle ?: bundle.title, Modifier.weight(1f), style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (toolbar.subtasksDone != null && toolbar.subtasksTotal != null) BasicText("${toolbar.subtasksDone}/${toolbar.subtasksTotal}", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
      SbIconButton(LucideIcons.star, if (toolbar.favorite) "Remove favorite" else "Add favorite", { onAction(SpecsAction.ToggleFavorite) }, selected = toolbar.favorite, enabled = toolbar.favoriteAvailable && !toolbar.favoriteInFlight)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
      control?.let { RunState(it.runState) }
      if (control == null && toolbar.control is SpecControlState.Unavailable) RunState(SpecRunState.Unavailable)
      when {
        canStart -> AgentControl(toolbar, control?.agentChoices.orEmpty(), onAction)
        toolbar.chosenAgent != null -> SbLabel("Agent: ${agentDisplayName(toolbar.chosenAgent)}")
      }
      androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
      Box {
        SbIconButton(LucideIcons.more, "$key actions", { onAction(SpecsAction.OpenBarMenu) })
        if (state.barMenuOpen) SpecActionsMenu(bundle, state, onAction, SbPopoverPlacement.Above, SpecsAction.DismissBarMenu)
      }
      when {
        canStart -> SbButton(requireNotNull(control).startLabel, { onAction(SpecsAction.Start) }, size = SbControlSize.Small, icon = LucideIcons.play, enabled = primaryEnabled)
        canPause -> SbButton("Pause", { onAction(SpecsAction.Pause) }, size = SbControlSize.Small, icon = LucideIcons.pause, enabled = primaryEnabled && control?.pauseRequested != true)
      }
    }
    control?.unavailableReason?.let { Hint(it, FeedbackTone.Error) }
    when (val load = toolbar.control) {
      is SpecControlState.Unavailable -> Hint(readProblemText(load.problem), FeedbackTone.Error)
      else -> Unit
    }
    if (control?.pauseRequested == true) Hint(PAUSE_NOTICE, FeedbackTone.Info)
    if (toolbar.confirmingCancel) CancelConfirmationDialog(bundle.issueKey ?: bundle.name, onAction)
    if (toolbar.purgeDialogOpen) PurgeConfirmationDialog(bundle.issueKey ?: bundle.name, onAction)
    if (toolbar.exportLiveDialogOpen) {
      ShellLayer { onAction(SpecsAction.DismissExportLive) }
      SbDialog(
        title = "Pause before exporting",
        onDismissRequest = { onAction(SpecsAction.DismissExportLive) },
        actions = {
          SbButton("Dismiss", { onAction(SpecsAction.DismissExportLive) }, variant = SbButtonVariant.Ghost)
          if (canPause && loaded?.control?.pauseRequested != true && !toolbar.inFlight) SbButton("Pause", { onAction(SpecsAction.Pause); onAction(SpecsAction.DismissExportLive) })
        },
      ) { SbDialogText("This goal must be paused before it can be exported.") }
    }
  }
}

@Composable
private fun AgentControl(toolbar: SpecToolbarState, choices: List<String>, onAction: (SpecsAction) -> Unit) {
  Box {
    SbButton(toolbar.chosenAgent?.let { "Agent: ${agentDisplayName(it)}" } ?: "Choose agent", { onAction(SpecsAction.OpenAgentMenu) }, size = SbControlSize.Small, icon = LucideIcons.bot, enabled = !toolbar.inFlight && !toolbar.reserved)
    if (toolbar.agentMenuOpen) {
      val dismiss = { onAction(SpecsAction.DismissAgentMenu) }
      ShellLayer(dismiss)
      SbPopover(true, dismiss) {
        SbMenu(listOf(SbMenuEntry.Head(RUN_ON)) + choices.map { agent ->
          SbMenuEntry.Item(agent, { onAction(SpecsAction.ChooseAgent(agent)) }, lead = { Avatar(agent, small = true, tooltip = null) }, checked = agent == toolbar.chosenAgent)
        })
      }
    }
  }
}

@Composable
private fun RunState(state: SpecRunState) {
  val label = when (state) {
    SpecRunState.NotStarted -> "Not started"
    SpecRunState.Running -> "Running"
    SpecRunState.PauseRequested -> "Pausing"
    SpecRunState.Paused -> "Paused"
    SpecRunState.Blocked -> "Blocked"
    SpecRunState.Canceled -> "Canceled"
    SpecRunState.Complete -> "Complete"
    SpecRunState.Unavailable -> "Unavailable"
  }
  val colors = SkillBillTheme.colors
  val dot = when (state) {
    SpecRunState.Running -> colors.stActive
    SpecRunState.Paused, SpecRunState.Blocked, SpecRunState.PauseRequested -> colors.stBlocked
    else -> colors.fgFaint
  }
  SbLabel(label, SbLabelStyle.Dot(dot))
}
