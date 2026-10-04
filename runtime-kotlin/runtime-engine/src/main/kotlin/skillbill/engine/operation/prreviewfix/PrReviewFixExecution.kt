package skillbill.engine.operation.prreviewfix

import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationStepResult
import skillbill.ports.review.pullrequest.PullRequestReviewThreadOperations
import skillbill.ports.review.pullrequest.model.ReviewPullRequest
import skillbill.ports.review.pullrequest.model.ReviewThread
import skillbill.ports.review.pullrequest.model.ReviewThreadReplyResult
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult

internal class PrReviewFixExecution(
  private val context: OperationContext,
  private val pullRequest: ReviewPullRequest,
  private val selected: List<PrReviewFixSelectedThread>,
  private val threads: Map<String, ReviewThread>,
  private val matrix: String,
  private val reviewThreads: PullRequestReviewThreadOperations,
  private val gitOperations: WorkflowGitOperations,
  private val runPhase: (PhaseRunRequest) -> PhaseRunResult,
) {
  private val fixes = mutableListOf<ThreadFix>()

  fun run(): OperationOutcome {
    applySelectedFixes()?.let { return it }
    val agentId = requireNotNull(context.invokedAgentId)
    val gate =
      when (val validation = runPhase(PhaseRunRequest(VALIDATION_DEFINITION, context.repoRoot, agentId))) {
        is PhaseRunResult.Blocked ->
          return stopped(
            "validation (${validation.invocationId}) blocked at '${validation.stepId}': ${validation.reason}",
          )
        is PhaseRunResult.Completed ->
          "Quality gate: validation passed (${validation.invocationId}).\n${validation.value.orEmpty().trimEnd()}"
      }
    val replies = fixes.map(::reply)
    val push = push()
    val report = report(replies.map(Reply::row), gate, push.line, drafts(replies))
    return if (replies.any(Reply::failed) || push.failed) {
      OperationOutcome.Failed(report)
    } else {
      OperationOutcome.Completed(report)
    }
  }

  private fun applySelectedFixes(): OperationOutcome? {
    val editing = context.copy(instructions = null)
    selected.forEach { thread ->
      val reviewThread =
        threads[thread.threadId]
          ?: return stopped("thread ${thread.ordinal} (${thread.threadId}) is no longer on the pull request")
      val directive = threadDirective(pullRequest, thread, reviewThread)
      when (val step = context.steps.runEditing(editing, THREAD_STEP, directive, mapOf(ANALYSIS_STEP to matrix))) {
        is OperationStepResult.Failed -> return stopped("thread ${thread.ordinal} failed: ${step.reason}")
        is OperationStepResult.Refused -> return step.refusal
        is OperationStepResult.Settled -> fixes += ThreadFix(thread, step.value.trim(), step.changedPaths)
      }
    }
    return null
  }

  private fun reply(fix: ThreadFix): Reply {
    val firstLine = fix.reply.lineSequence().firstOrNull().orEmpty()
    val (status, failed) =
      when {
        fix.reply.isBlank() -> "not sent: the step produced no reply" to true
        context.arguments.replies == REPLIES_DRAFT -> "drafted" to false
        else ->
          when (val posted = reviewThreads.replyToThread(context.repoRoot, fix.thread.threadId, fix.reply)) {
            is ReviewThreadReplyResult.Posted -> "posted ${posted.commentUrl}" to false
            is ReviewThreadReplyResult.Failed -> "failed: ${posted.reason}" to true
          }
      }
    return Reply(fix, "| ${fix.thread.ordinal} | $status | $firstLine |", failed)
  }

  private fun drafts(replies: List<Reply>): String? =
    replies
      .takeIf { context.arguments.replies == REPLIES_DRAFT }
      ?.joinToString("\n\n") { reply ->
        "### ${reply.fix.thread.ordinal} (${reply.fix.thread.threadId})\n\n${reply.fix.reply}"
      }

  private fun push(): PushResult {
    if (context.arguments.push != PUSH_ON) return PushResult("Push: off; the fixes stay uncommitted in the worktree.")
    val repoRoot = context.repoRoot
    val branch = pullRequest.headRefName
    val commit = commitFixes().getOrElse { error -> return PushResult("Push: ${error.message}", failed = true) }
    val pushed = gitOperations.pushBranch(repoRoot, branch)
    return if (pushed is WorkflowGitOperationResult.Ok) {
      PushResult("Push: pushed '$branch'$commit.")
    } else {
      PushResult("Push: failed: ${pushed.error}", failed = true)
    }
  }

  private fun commitFixes(): Result<String> {
    val staged = gitOperations.stageAll(context.repoRoot)
    if (staged !is WorkflowGitOperationResult.Ok) {
      return Result.failure(IllegalStateException("staging failed: ${staged.error}"))
    }
    val message = "Address review threads ${selected.joinToString(", ") { it.ordinal }} on PR #${pullRequest.number}"
    return when (val committed = gitOperations.createCommit(context.repoRoot, message)) {
      is WorkflowGitCommitResult.Committed -> Result.success(" with commit ${committed.commitSha}")
      WorkflowGitCommitResult.NothingToCommit -> Result.success(" (nothing new to commit)")
      is WorkflowGitCommitResult.Failed -> Result.failure(IllegalStateException("commit failed: ${committed.error}"))
    }
  }

  private fun stopped(reason: String): OperationOutcome =
    OperationOutcome.Failed(
      report(
        replies = emptyList(),
        gate = "Quality gate: not passed; stopped because $reason. No reply was posted.",
        push = "Push: not performed.",
        drafts = null,
      ),
    )

  private fun report(
    replies: List<String>,
    gate: String,
    push: String,
    drafts: String?,
  ): String {
    val changed = fixes.flatMap(ThreadFix::changedPaths).distinct()
    val fixRows =
      fixes.map { fix ->
        val files = fix.changedPaths.joinToString(", ").ifBlank { "—" }
        "| ${fix.thread.ordinal} (${fix.thread.threadId}) | $files | ${fix.thread.option} |"
      }
    val lines =
      buildList {
        add("PR review fix for ${pullRequest.describe()}")
        add("")
        add("Applied fixes:")
        addAll(listOf("| Thread | Files changed | Option |", "| --- | --- | --- |") + fixRows)
        add("")
        add("Replies:")
        addAll(listOf("| Thread | Reply | First line |", "| --- | --- | --- |") + replies)
        add("")
        add("Learnings recorded:")
        addAll(changed.filter { path -> path.endsWith(HISTORY_FILE) }.map { "- $it" }.ifEmpty { listOf("(none)") })
        add("")
        add("Follow-up specs created:")
        addAll(changed.filter { path -> path.startsWith(SPEC_ROOT) }.map { "- $it" }.ifEmpty { listOf("(none)") })
        add("")
        add(gate)
        add("")
        add(push)
        drafts?.let { text -> addAll(listOf("", "Drafted replies:", "", text)) }
      }
    return lines.joinToString("\n", postfix = "\n")
  }

  private data class ThreadFix(
    val thread: PrReviewFixSelectedThread,
    val reply: String,
    val changedPaths: List<String>,
  )

  private data class Reply(
    val fix: ThreadFix,
    val row: String,
    val failed: Boolean,
  )

  private data class PushResult(
    val line: String,
    val failed: Boolean = false,
  )
}

private const val VALIDATION_DEFINITION = "validation"
private const val HISTORY_FILE = "agent/history.md"
private const val SPEC_ROOT = ".feature-specs/"
