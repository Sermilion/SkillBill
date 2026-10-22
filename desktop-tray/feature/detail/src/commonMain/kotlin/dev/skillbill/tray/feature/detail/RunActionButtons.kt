package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.DesignButton
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.eligibleForControl

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunActionButtons(detail: Detail, busy: Boolean, available: Boolean, onAction: (DetailAction) -> Unit) {
  val item = detail.item
  val options = detail.runOptions
  var chooseAgent by remember(item.identity) { mutableStateOf(false) }
  var selectedAgent by remember(item.identity, options?.agentId) { mutableStateOf(options?.agentId) }
  val idle = !busy && available
  val controllable = item.eligibleForControl()
  val canLaunch = options?.canLaunch == true
  val resumable = item.lifecycle != Lifecycle.ACTIVE && item.lifecycle != Lifecycle.TERMINAL
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      DesignButton(
        if (item.pauseRequested) "Pause requested" else "Pause after subtask",
        enabled = idle && controllable && item.pauseEligible,
      ) {
        onAction(DetailAction.Pause)
      }
      DesignButton("Pause", enabled = idle && controllable, danger = true) { onAction(DetailAction.Stop) }
      if (resumable) Box {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          DesignButton(if (busy) "Working..." else options?.launchLabel ?: "Resume", enabled = idle && canLaunch) {
            if (selectedAgent == null) chooseAgent = true else onAction(DetailAction.Launch(selectedAgent))
          }
          DesignButton(selectedAgent?.let { "Agent: $it" } ?: "Choose agent", enabled = idle && canLaunch) { chooseAgent = true }
        }
        DropdownMenu(expanded = chooseAgent && canLaunch, onDismissRequest = { chooseAgent = false }) {
          options?.agentChoices.orEmpty().forEach { agent ->
            DropdownMenuItem(text = { Text(agent) }, onClick = {
              chooseAgent = false
              selectedAgent = agent
            })
          }
        }
      }
      DesignButton(if (busy) "Working..." else "Cancel", enabled = idle && options?.canCancel == true, danger = true) {
        onAction(DetailAction.Cancel)
      }
    }
    if (resumable) {
      options?.unavailableReason?.let { Text(it, color = muted, fontSize = 11.sp) }
    }
    if (canLaunch && resumable) {
      Text("Starts the installed agent harness", color = muted, fontSize = 11.sp)
    }
  }
}
