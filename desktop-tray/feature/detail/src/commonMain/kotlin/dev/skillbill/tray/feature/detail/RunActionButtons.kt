package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import dev.skillbill.tray.core.domain.model.eligibleForControl

@Composable
internal fun RunActionButtons(detail: Detail, busy: Boolean, onAction: (DetailAction) -> Unit) {
  val item = detail.item
  val options = detail.runOptions
  var chooseAgent by remember(item.identity) { mutableStateOf(false) }
  var selectedAgent by remember(item.identity, options?.agentId) { mutableStateOf(options?.agentId) }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (item.eligibleForControl()) {
        DesignButton(if (item.pauseRequested) "Pause requested" else "Pause after subtask", enabled = !busy && item.pauseEligible) {
          onAction(DetailAction.Pause)
        }
        DesignButton("Stop goal", enabled = !busy, danger = true) { onAction(DetailAction.Stop) }
      }
      if (options?.canLaunch == true) Box {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          DesignButton(if (busy) "Working..." else options.launchLabel, enabled = !busy) {
            if (selectedAgent == null) chooseAgent = true else onAction(DetailAction.Launch(selectedAgent))
          }
          DesignButton(selectedAgent?.let { "Agent: $it" } ?: "Choose agent", enabled = !busy) { chooseAgent = true }
        }
        DropdownMenu(expanded = chooseAgent, onDismissRequest = { chooseAgent = false }) {
          options.agentChoices.forEach { agent ->
            DropdownMenuItem(text = { Text(agent) }, onClick = {
              chooseAgent = false
              selectedAgent = agent
            })
          }
        }
      }
      if (options?.canCancel == true) {
        DesignButton(if (busy) "Working..." else "Cancel", enabled = !busy, danger = true) { onAction(DetailAction.Cancel) }
      }
    }
    options?.unavailableReason?.let { Text(it, color = muted, fontSize = 11.sp) }
    if (options?.canLaunch == true) {
      Text("Starts the installed agent harness", color = muted, fontSize = 11.sp)
    }
  }
}
