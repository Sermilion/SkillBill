package dev.skillbill.tray.feature.specs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.DesignButton
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.border
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.orange
import dev.skillbill.tray.core.designsystem.raised
import dev.skillbill.tray.core.designsystem.red
import dev.skillbill.tray.core.designsystem.strongBorder

/**
 * Start, Pause, Cancel and Purge for the selected spec folder. The buttons never move; they only enable or disable, and every
 * flag is the runtime's verdict carried by the control. The line under them holds the cancel confirmation, a refusal, the pause
 * notice or a hint.
 */
@Composable
internal fun SpecRunToolbar(state: SpecToolbarState, onAction: (SpecsAction) -> Unit) {
  val selection = state.selection
  val control = (state.control as? ControlLoad.Ready)?.control?.takeIf { selection is ToolbarSelection.Bundle }
  val idle = !state.inFlight
  val key = control?.issueKey ?: (selection as? ToolbarSelection.Bundle)?.path?.substringAfterLast('/').orEmpty()
  val pauseRequested = control?.pauseRequested == true
  Column {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DesignButton(control?.startLabel ?: "Start", enabled = idle && control?.canStart == true) { onAction(SpecsAction.Start) }
            DesignButton(
              "▾",
              enabled = idle && control?.canStart == true,
              modifier = Modifier.semantics { contentDescription = state.chosenAgent?.let { "Agent: $it" } ?: "Choose agent" },
            ) { onAction(SpecsAction.OpenAgentMenu) }
          }
          DropdownMenu(expanded = state.agentMenuOpen && control != null, onDismissRequest = { onAction(SpecsAction.DismissAgentMenu) }) {
            control?.agentChoices.orEmpty().forEach { agent ->
              DropdownMenuItem(
                text = { Text(if (agent == state.chosenAgent) "✓ $agent" else "    $agent") },
                onClick = { onAction(SpecsAction.ChooseAgent(agent)) },
              )
            }
          }
        }
        DesignButton(if (pauseRequested) "Pause requested" else "Pause", enabled = idle && control?.canPause == true && !pauseRequested) {
          onAction(SpecsAction.Pause)
        }
        DesignButton("Cancel", enabled = idle && control?.canCancel == true, danger = true) { onAction(SpecsAction.RequestCancel) }
        Spacer(Modifier.weight(1f))
        if (control?.purgeVisible == true) PurgeButton(key, enabled = idle && control.canPurge) { onAction(SpecsAction.RequestPurge) }
      }
      StatusLine(state, control?.unavailableReason, pauseRequested, key, idle, onAction)
    }
    HorizontalDivider(color = border)
    if (state.purgeDialogOpen) {
      PurgeConfirmationDialog(key, onKeep = { onAction(SpecsAction.KeepRunHistory) }, onConfirm = { onAction(SpecsAction.ConfirmPurge) })
    }
  }
}

/** The first case that applies: cancel confirmation, inline refusal, pause notice, selection hint, then the runtime's reason. */
@Composable
private fun StatusLine(
  state: SpecToolbarState,
  unavailableReason: String?,
  pauseRequested: Boolean,
  key: String,
  idle: Boolean,
  onAction: (SpecsAction) -> Unit,
) {
  val failedLoad = (state.control as? ControlLoad.Failed)?.message
  when {
    state.confirmingCancel -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(cancelPrompt(key), color = ink, fontFamily = body, fontSize = 12.sp)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DesignButton("Keep running", enabled = idle) { onAction(SpecsAction.KeepRunning) }
        DesignButton("Cancel goal", enabled = idle, danger = true) { onAction(SpecsAction.ConfirmCancel) }
      }
    }
    state.message != null -> Text(state.message, color = orange, fontFamily = body, fontSize = 12.sp)
    pauseRequested -> Text(PAUSE_NOTICE, color = muted, fontFamily = body, fontSize = 11.sp)
    state.selection is ToolbarSelection.None -> Text(HINT_SELECT_FOLDER, color = muted, fontFamily = body, fontSize = 11.sp)
    state.selection is ToolbarSelection.FileInBundle -> Text(fileHint(state.selection.key), color = muted, fontFamily = body, fontSize = 11.sp)
    else -> (unavailableReason ?: failedLoad)?.let { Text(it, color = muted, fontFamily = body, fontSize = 11.sp) }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurgeButton(key: String, enabled: Boolean, onClick: () -> Unit) {
  val shape = RoundedCornerShape(8.dp)
  TooltipBox(
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
    tooltip = { PlainTooltip { Text(if (enabled) purgeLabel(key) else PURGE_DISABLED_TOOLTIP) } },
    state = rememberTooltipState(),
  ) {
    Box(
      Modifier.size(32.dp)
        .background(raised, shape)
        .border(1.dp, if (enabled) red.copy(alpha = .45f) else strongBorder, shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = purgeLabel(key) },
      contentAlignment = Alignment.Center,
    ) {
      EraserGlyph(red.copy(alpha = if (enabled) 1f else .42f))
    }
  }
}

/** A tilted rounded block with its left band filled: no icon library is on the classpath. */
@Composable
private fun EraserGlyph(color: Color) {
  Canvas(Modifier.size(16.dp)) {
    rotate(-45f) {
      val width = size.width * .95f
      val height = size.height * .5f
      val topLeft = Offset((size.width - width) / 2, (size.height - height) / 2)
      val stroke = 1.5.dp.toPx()
      drawRoundRect(color, topLeft, Size(width, height), CornerRadius(3.dp.toPx()), style = Stroke(stroke))
      drawRect(color.copy(alpha = color.alpha * .5f), topLeft, Size(width * .45f, height))
      drawLine(color, Offset(topLeft.x + width * .45f, topLeft.y), Offset(topLeft.x + width * .45f, topLeft.y + height), stroke)
    }
  }
}
