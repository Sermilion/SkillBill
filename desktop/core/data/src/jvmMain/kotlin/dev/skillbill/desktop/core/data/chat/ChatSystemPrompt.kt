package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatScope

internal const val SKILL_BILL_REPOSITORY = "Sermilion/SkillBill"

/** Every agent runs a chat turn with the same access it has in an execution; this prompt only names the execution. */
internal fun scopeSystemPrompt(scope: ChatScope, mode: ChatPermissionMode): String = buildString {
  appendLine("You are chatting with the operator about one skill-bill execution.")
  appendLine("Execution id: ${scope.executionId}")
  appendLine("Kind: ${scope.kind?.name ?: "unknown"}")
  appendLine("Issue key: ${scope.issueKey ?: "none"}")
  appendLine("Title: ${scope.title}")
  appendLine("Worktree: ${scope.repositoryRoot}")
  append(
    "You have the same access as the agent that runs this execution: any shell command, the skill-bill CLI and MCP " +
      "tools, git, gh and file edits. Issues about skill-bill itself go to $SKILL_BILL_REPOSITORY.",
  )
  appendLine()
  appendLine(CHAT_ACTION_GUIDANCE)
  if (mode == ChatPermissionMode.ReadOnly) {
    appendLine()
    append(
      "The execution is running in this worktree right now, so edits or runtime changes can collide with it. " +
        "The operator can use \"Pause and act\" to pause it for the length of one turn.",
    )
  }
}

private const val CHAT_ACTION_GUIDANCE = """
When you propose concrete next actions, append a structured action block at the very end of your reply.
Use this exact fenced block format, with a JSON array containing up to 3 actions:
```skillbill-actions
[{"label":"Inspect CI failure","instruction":"Inspect the failed CI job for this execution and explain its cause without changing files or runtime state.","mode":"act"}]
```
Each label must be specific, at most 60 characters, and match an action offered in your reply.
Each instruction must be self-contained, concern only this execution, and be at most 2000 characters.
Use mode "read" for actions that only inspect, discuss or file issues.
Use mode "act" for file edits, pushes or runtime changes.
The app pauses a running execution before an act action, then resumes it when the turn ends.
Do not suggest actions to pause, resume or relaunch the execution through this mechanism.
Do not execute a suggested action until the operator clicks its button or explicitly requests it.
Omit the block when no action is useful. Keep the visible reply concise; this block is hidden by the app.
"""
