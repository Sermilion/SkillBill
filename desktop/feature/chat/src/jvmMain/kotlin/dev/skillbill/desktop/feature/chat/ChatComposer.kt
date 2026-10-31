package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused

/**
 * The active chat's input. Enter sends and Shift+Enter starts a new line; "Pause and act" shows only while the execution runs.
 * The input stays editable while a turn runs so it keeps focus; both buttons are off while a turn runs, without a supported agent, or once the execution left the database.
 */
@Composable
internal fun ChatComposer(session: ChatSession, workspace: ChatWorkspace) {
  val busy by session.busy.collectAsState()
  val agent by session.agent.collectAsState()
  val supported by session.agentSupported.collectAsState()
  val gone by session.gone.collectAsState()
  val mode by session.mode.collectAsState()
  val id = session.item.id
  val text = workspace.drafts[id].orEmpty()
  val ready = !busy && agent != null && supported && !gone && text.isNotBlank()
  fun submit(action: (String, String) -> Unit) {
    if (!ready) return
    action(id, text)
    workspace.drafts.remove(id)
  }
  Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SbInput(
      text, { workspace.drafts[id] = it },
      modifier = Modifier.fillMaxWidth().gatesShortcutsWhileFocused(),
      placeholder = "Ask about this execution",
      enabled = !gone,
      onSubmit = { submit(workspace::send) },
      multiline = true,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      SbLabel(chipText(agent))
      Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
        if (mode == ChatPermissionMode.ReadOnly) SbButton("Pause and act", { submit(workspace::pauseAndAct) }, enabled = ready)
        SbButton("Send", { submit(workspace::send) }, variant = SbButtonVariant.Primary, enabled = ready)
      }
    }
  }
}

internal fun chipText(agent: ChatAgent?): String = when {
  agent == null -> "No agent"
  agent.model == null -> "${agent.agentId} · default"
  agent.effort == null -> "${agent.agentId} · ${agent.model}"
  else -> "${agent.agentId} · ${agent.model} · ${agent.effort}"
}
