package dev.skillbill.tray.ui

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
import dev.skillbill.tray.application.DashboardModel
import dev.skillbill.tray.domain.Detail
import dev.skillbill.tray.domain.Freshness
import dev.skillbill.tray.domain.eligibleForControl

@Composable
internal fun RunActionButtons(detail: Detail, model: DashboardModel, busy: Boolean) {
  val item = detail.item
  val options = detail.runOptions
  var chooseAgent by remember(item.identity) { mutableStateOf(false) }
  var selectedAgent by remember(item.identity, options?.agentId) { mutableStateOf(options?.agentId) }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (item.freshness == Freshness.FRESH && item.eligibleForControl()) {
        DesignButton(if (item.pauseRequested) "Pause requested" else "Pause after subtask", enabled = !busy && !item.pauseRequested) {
          model.perform("pause", item.identity)
        }
        DesignButton("Stop goal", enabled = !busy, danger = true) { model.perform("stop", item.identity) }
      }
      if (options?.canLaunch == true) Box {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          DesignButton(if (busy) "Working..." else options.launchLabel, enabled = !busy) {
            if (selectedAgent == null) chooseAgent = true else model.perform("launch:$selectedAgent", item.identity)
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
        DesignButton(if (busy) "Working..." else "Cancel", enabled = !busy, danger = true) {
          model.perform("cancel", item.identity)
        }
      }
    }
    options?.unavailableReason?.let { Text(it, color = muted, fontSize = 11.sp) }
    if (options?.canLaunch == true) {
      Text("Starts the installed agent harness", color = muted, fontSize = 11.sp)
    }
  }
}
