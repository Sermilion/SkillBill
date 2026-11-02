package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent

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
