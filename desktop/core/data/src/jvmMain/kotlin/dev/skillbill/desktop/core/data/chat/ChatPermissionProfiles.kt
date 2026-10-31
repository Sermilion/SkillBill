package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatScope

internal const val SKILL_BILL_REPOSITORY = "Sermilion/SkillBill"

private const val ISSUE_RULE = "Bash(gh issue create --repo $SKILL_BILL_REPOSITORY *)"

private val readOnlyCommands = listOf(
  "skill-bill goal status",
  "skill-bill work list",
  "skill-bill work status",
  "skill-bill work board",
  "skill-bill work detail",
  "skill-bill feature-task status",
  "skill-bill feature-task lookup",
)

private val readOnlyMcpTools = listOf(
  "mcp__skill-bill__doctor",
  "mcp__skill-bill__goal_stats",
  "mcp__skill-bill__review_stats",
  "mcp__skill-bill__feature_verify_stats",
  "mcp__skill-bill__feature_verify_workflow_get",
  "mcp__skill-bill__feature_verify_workflow_latest",
  "mcp__skill-bill__feature_verify_workflow_list",
)

/** The built-in tools a turn may load ([tools]) and the rules that run without a prompt ([allowedTools]). */
internal data class ChatPermissionProfile(val tools: String, val allowedTools: List<String>)

internal fun permissionProfile(mode: ChatPermissionMode): ChatPermissionProfile = when (mode) {
  ChatPermissionMode.Act -> ChatPermissionProfile(
    tools = "Read,Grep,Glob,Bash,Edit,Write",
    allowedTools = listOf("Read", "Grep", "Glob", "Edit", "Write", "Bash", "mcp__skill-bill"),
  )
  ChatPermissionMode.ReadOnly -> ChatPermissionProfile(
    tools = "Read,Grep,Glob,Bash",
    allowedTools = listOf("Read", "Grep", "Glob") +
      readOnlyCommands.flatMap { listOf("Bash($it)", "Bash($it *)") } +
      readOnlyMcpTools +
      ISSUE_RULE,
  )
}

internal fun scopeSystemPrompt(scope: ChatScope, mode: ChatPermissionMode): String = buildString {
  appendLine("You are chatting with the operator about one skill-bill execution.")
  appendLine("Execution id: ${scope.executionId}")
  appendLine("Kind: ${scope.kind?.name ?: "unknown"}")
  appendLine("Issue key: ${scope.issueKey ?: "none"}")
  appendLine("Title: ${scope.title}")
  appendLine("Worktree: ${scope.repositoryRoot}")
  appendLine(
    "You may answer block decisions, unblock and resume this execution, run shell commands including skill-bill CLI commands, use skill-bill MCP tools, edit files " +
      "inside the worktree to unblock or finish this execution, and file issues against $SKILL_BILL_REPOSITORY.",
  )
  append(
    "Decline anything unrelated to this execution, and tell the operator to do that outside this chat.",
  )
  appendLine()
  appendLine(CHAT_ACTION_GUIDANCE)
  if (mode == ChatPermissionMode.ReadOnly) {
    appendLine()
    append(
      "The execution is running. You may only read, discuss and file issues. For anything that edits files or changes " +
        "runtime state, tell the operator to use \"Pause and act\" first.",
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
Use mode "read" only for actions allowed by the read-only tool profile, including filing an issue.
Use mode "act" for shell commands outside the read-only allowlist, file edits, pushes or runtime changes.
The app pauses a running execution before an act action, then resumes it when the turn ends.
Do not suggest actions to pause, resume or relaunch the execution through this mechanism.
Do not execute a suggested action until the operator clicks its button or explicitly requests it.
Omit the block when no action is useful. Keep the visible reply concise; this block is hidden by the app.
"""
