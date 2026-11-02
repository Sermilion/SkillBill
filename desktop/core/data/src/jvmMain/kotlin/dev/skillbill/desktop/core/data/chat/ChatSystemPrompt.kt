package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatScope

internal const val SKILL_BILL_REPOSITORY = "Sermilion/SkillBill"

/** Every agent runs a chat turn with the same access it has in an execution; this prompt names what the chat is about. */
internal fun scopeSystemPrompt(scope: ChatScope, mode: ChatPermissionMode): String =
  listOfNotNull(scopeSystemPrompt(scope), modeNote(mode)).joinToString("\n\n")

/** The prompt without the turn's mode, for an agent process that outlives a mode change. */
internal fun scopeSystemPrompt(scope: ChatScope): String = when (scope) {
  is ChatScope.Execution -> executionSystemPrompt(scope)
  is ChatScope.NewTask -> newTaskSystemPrompt(scope)
}

private fun executionSystemPrompt(scope: ChatScope.Execution): String = buildString {
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
  append(CHAT_ACTION_GUIDANCE.trimEnd())
}

private fun newTaskSystemPrompt(scope: ChatScope.NewTask): String = buildString {
  appendLine("You are chatting with the operator about new work. No skill-bill execution exists yet.")
  appendLine("Repository: ${scope.repositoryRoot}")
  scope.baseBranch?.let { appendLine("Base branch: $it") }
  appendLine("The operator is describing work to start in this repository. When the request does not say what done means or what is out of scope, ask.")
  appendLine(
    "You have the same access as any chat: any shell command, git, gh and file edits. Do not launch skill-bill yourself. " +
      "A start action becomes a button; the app starts the selected run when the operator clicks it.",
  )
  append(NEW_TASK_ACTION_GUIDANCE.trimEnd())
}

/** What the agent must know about the execution's state this turn; null when it may act. */
internal fun modeNote(mode: ChatPermissionMode): String? = when (mode) {
  ChatPermissionMode.ReadOnly ->
    "The execution is running in this worktree right now, so edits or runtime changes can collide with it. " +
      "The operator can use \"Pause and act\" to pause it for the length of one turn."
  ChatPermissionMode.Act -> null
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
When execution context includes detail.review.action.fix or detail.review.action.verify, use those exact scoped
instructions for the suggested actions "Fix findings" and "Verify findings", with mode "act" for both.
Fix findings repairs code. Verify findings checks existing code without editing source and records confirmed fixes.
The preparation command binds feedback to the original review. Never substitute a new review or a different database.
After fixing review findings for this execution, offer an action to verify those findings against the current worktree.
Identify the original execution and findings in the instruction. Do not claim that verification updates their status
unless a supported command actually records that result. Use mode "read" for an inspection with no runtime changes;
use mode "act" if the verification records finding statuses.
Do not execute a suggested action until the operator clicks its button or explicitly requests it.
Omit the block when no action is useful. Keep the visible reply concise; this block is hidden by the app.
"""

private const val NEW_TASK_ACTION_GUIDANCE = """
When you propose concrete next actions, append a structured action block at the very end of your reply.
Use this exact fenced block format, with a JSON array containing up to 3 actions:
```skillbill-actions
[{"label":"Start a goal run","instruction":"Implement the requested change in this repository.","mode":"act","start":"goal"}]
```
Each action needs a nonblank instruction and a mode of "read" or "act". Labels must be at most 60 characters, and instructions at most 2000 characters.
For an action that should start a run when clicked, set "start" to one of: "goal", "plan", "review", "validation", "qa" or "pr".
The app turns that action into a button and starts the selected run only after the operator clicks it. Do not launch skill-bill yourself.
For an action without "start", the app sends its instruction as the next chat turn when clicked.
Omit the block when no action is useful. Keep the visible reply concise; this block is hidden by the app.
"""
