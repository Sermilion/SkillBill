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
  if (mode == ChatPermissionMode.ReadOnly) {
    appendLine()
    append(
      "The execution is running. You may only read, discuss and file issues. For anything that edits files or changes " +
        "runtime state, tell the operator to use \"Pause and act\" first.",
    )
  }
}
