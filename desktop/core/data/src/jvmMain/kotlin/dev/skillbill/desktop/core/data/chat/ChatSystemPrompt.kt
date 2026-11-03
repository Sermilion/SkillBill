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

private const val SHARED_START_ACTION_GUIDANCE = """
For an action that should start a skill-bill run when clicked, set "start" to exactly one of: "goal", "plan", "review", "validation", "qa", "pr", "monitor", "verify", or "pr-review-fix".
Never set "start" to "release".
Treat "update-check", "unit-test-value-check", "feature-guard", and "feature-guard-cleanup" as ordinary chat turns: use an instruction only, with no "start" field.
Do not launch skill-bill yourself. The app starts the run only after the operator clicks the button.
Map operator phrasing to "start" as follows: "code review", "review this branch", and "review my branch" -> "review"; watching a named PR or "monitor this PR" -> "monitor"; acceptance or spec verify (not Verify findings) -> "verify"; "qa" or "QA this branch" -> "qa"; "run the checks" or validation -> "validation"; opening or creating a PR -> "pr"; planning -> "plan"; new work the operator asked you to implement -> "goal"; fixing PR review comments -> "pr-review-fix".
When the operator's request matches one of those mappings, the first action in the block must use label "Run phase:<id>" for phase starts (goal, plan, review, validation, qa, pr, monitor) or "Run operation:<id>" for verify and pr-review-fix.
The app launches from JSON "start" and optional "target", not from the label text.
Optional JSON field "target" is a string: for "monitor", the PR URL or issue key when the user named one, omitted otherwise; for "verify", the required intake (free text or "spec:<path>"); for "pr-review-fix", an optional PR; for review, validation, qa, and pr omit "target" unless the user named one.
Do not put CLI flags such as "target:", "mode:", or "qa:" into "target".
A click starts the first invocation. "verify" and "pr-review-fix" then park at awaiting_confirmation for a later operator confirm; that confirm is not a second start kind.
"""

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
"Fix findings" and "Verify findings" are not "start" "verify" and must not set "start":"verify".
Do not execute a suggested action until the operator clicks its button or explicitly requests it.
$SHARED_START_ACTION_GUIDANCE
This prompt may emit "start" on an action. A "goal" or "plan" start is only for new work the operator asked for, not for work already covered by this execution.
A start waits until this execution is not running.
Omit the block when no action is useful. Keep the visible reply concise; this block is hidden by the app.
"""

private const val NEW_TASK_ACTION_GUIDANCE = """
When you propose concrete next actions, append a structured action block at the very end of your reply.
Use this exact fenced block format, with a JSON array containing up to 3 actions:
```skillbill-actions
[{"label":"Run phase:goal","instruction":"Implement the requested change in this repository.","mode":"act","start":"goal","title":"Add dark mode setting"}]
```
Each action needs a nonblank instruction and a mode of "read" or "act". Labels must be at most 60 characters, and instructions at most 2000 characters.
Every "goal" and "plan" start action needs a "title": 2 to 6 words naming the change, with no issue key, file path or trailing punctuation.
Offer a start action once the request is clear.
For an action without "start", the app sends its instruction as the next chat turn when clicked.
$SHARED_START_ACTION_GUIDANCE
Omit the block when no action is useful. Keep the visible reply concise; this block is hidden by the app.
"""
