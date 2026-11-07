package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle

/** The pill above a chat's input that says what the agent is doing now. */
@Composable
internal fun ChatActivityPill(thread: ChatThread, gone: Boolean = false) {
  val busy by thread.busy.collectAsState()
  val agent by thread.agent.collectAsState()
  val transcript by thread.transcript.collectAsState()
  Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
    SbLabel(
      chatActivityStatus(busy, agent, gone, transcript),
      style = if (busy) SbLabelStyle.Dot(SkillBillTheme.colors.fg2) else SbLabelStyle.Plain,
    )
  }
}

internal fun chatActivityStatus(busy: Boolean, agent: ChatAgent?, gone: Boolean, transcript: List<ChatMessage>): String {
  if (gone) return "Execution unavailable"
  if (!busy) return "Ready"
  if (agent == null) return "Preparing chat..."
  val reply = (transcript.lastOrNull() as? ChatMessage.Agent)?.takeIf { it.streaming } ?: return "Thinking..."
  val active = reply.tools.lastOrNull { it.result == null }
  if (active != null) return toolActivity(active.call.name).label
  val changedFiles = reply.tools.any { toolActivity(it.call.name) == ToolActivity.Editing && it.result?.isError == false }
  if (changedFiles) return "Working on changes..."
  return if (reply.text.isNotEmpty()) "Responding..." else "Thinking..."
}

private enum class ToolActivity(val label: String) {
  Editing("Editing files..."),
  Reading("Reading files..."),
  Searching("Searching..."),
  Running("Running command..."),
  Delegating("Agent working..."),
  Other("Using tools..."),
}

private fun toolActivity(name: String): ToolActivity = when (name.lowercase().substringAfterLast("__").substringAfterLast('.')) {
  "edit", "multiedit", "write", "notebookedit", "apply_patch", "str_replace", "delete", "file_change" -> ToolActivity.Editing
  "read", "read_file", "notebookread" -> ToolActivity.Reading
  "grep", "glob", "search", "ls", "webfetch", "websearch", "web_search" -> ToolActivity.Searching
  "bash", "shell", "exec", "exec_command", "run", "command_execution" -> ToolActivity.Running
  "task", "agent", "spawn_agent" -> ToolActivity.Delegating
  else -> ToolActivity.Other
}
