package skillbill.engine.operation.prreviewfix

import skillbill.application.review.diffcomment.DiffCommentService
import skillbill.application.review.diffcomment.model.DiffCommentThreadResult
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationStepResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.diffcomment.model.DiffCommentAuthor
import skillbill.review.diffcomment.model.DiffCommentOutcome
import skillbill.review.diffcomment.model.DiffCommentThread

internal class LocalReviewFixExecution(
  private val context: OperationContext,
  private val branch: String,
  private val selected: List<PrReviewFixSelectedThread>,
  private val threads: Map<String, DiffCommentThread>,
  private val matrix: String,
  private val diffComments: DiffCommentService,
  private val gitOperations: WorkflowGitOperations,
  private val runPhase: (PhaseRunRequest) -> PhaseRunResult,
  private val modelAssignment: FeatureTaskRuntimeModelAssignment,
  private val diagnostics: RuntimeDiagnostics,
) {
  private val fixes = mutableListOf<ThreadFix>()

  fun run(): OperationOutcome {
    applySelectedFixes()?.let { return it }
    val agentId = requireNotNull(context.invokedAgentId)
    val validationRequest =
      PhaseRunRequest(VALIDATION_DEFINITION, context.repoRoot, agentId, modelAssignment = modelAssignment)
    val gate =
      when (val validation = runPhase(validationRequest)) {
        is PhaseRunResult.Blocked ->
          return stopped(
            "validation (${validation.invocationId}) blocked at '${validation.stepId}': ${validation.reason}",
          )
        is PhaseRunResult.Completed ->
          "Quality gate: validation passed (${validation.invocationId}).\n${validation.value.orEmpty().trimEnd()}"
      }
    val replies = fixes.map(::reply)
    val commitLine = commitFixes()
    val report = report(replies.map(Reply::row), gate, commitLine)
    return if (replies.any(Reply::failed) || commitLine.startsWith("Commit: failed:")) {
      OperationOutcome.Failed(report)
    } else {
      OperationOutcome.Completed(report)
    }
  }

  private fun applySelectedFixes(): OperationOutcome? {
    val editing = context.copy(instructions = null)
    selected.forEach { thread ->
      val localThread =
        threads[thread.threadId]
          ?: return stopped("thread ${thread.ordinal} (${thread.threadId}) is no longer open")
      val directive = localThreadDirective(thread, localThread)
      when (val step = context.steps.runEditing(editing, THREAD_STEP, directive, mapOf(ANALYSIS_STEP to matrix))) {
        is OperationStepResult.Failed -> return stopped("thread ${thread.ordinal} failed: ${step.reason}")
        is OperationStepResult.Refused -> return stopped("thread ${thread.ordinal} was refused: ${step.refusal.reason}")
        is OperationStepResult.Settled -> fixes += ThreadFix(thread, step.value.trim(), step.changedPaths)
      }
    }
    return null
  }

  private fun reply(fix: ThreadFix): Reply {
    val (outcome, body) = parseLocalReplyOutcome(fix.reply, fix.changedPaths, diagnostics)
    if (body.isBlank()) {
      return Reply(fix, "| ${fix.thread.ordinal} | not sent: the step produced no reply | — |", true)
    }
    val row =
      when (
        val saved =
          diffComments.reply(
            fix.thread.threadId,
            DiffCommentAuthor.AGENT,
            context.invokedAgentId,
            body,
            outcome,
          )
      ) {
        is DiffCommentThreadResult.Saved ->
          "replied (${outcome.wireValue} -> ${saved.thread.state.wireValue})" to false
        DiffCommentThreadResult.NotFound -> "failed: thread not found" to true
        is DiffCommentThreadResult.Rejected -> "failed: ${saved.rejection.wireValue}" to true
        is DiffCommentThreadResult.Unavailable -> "failed: ${saved.reason}" to true
      }
    return Reply(
      fix,
      "| ${fix.thread.ordinal} | ${row.first} | ${body.lineSequence().firstOrNull().orEmpty()} |",
      row.second || outcome == DiffCommentOutcome.FAILED,
    )
  }

  private fun commitFixes(): String {
    val staged = gitOperations.stageAll(context.repoRoot)
    if (staged !is WorkflowGitOperationResult.Ok) return "Commit: failed: staging failed: ${staged.error}"
    val ordinals = selected.joinToString(", ") { it.ordinal }
    return when (
      val committed =
        gitOperations.createCommit(
          context.repoRoot,
          "Address local diff comments $ordinals on $branch",
        )
    ) {
      is WorkflowGitCommitResult.Committed -> "Commit: ${committed.commitSha} (not pushed)"
      WorkflowGitCommitResult.NothingToCommit -> "Commit: nothing new to commit"
      is WorkflowGitCommitResult.Failed -> "Commit: failed: ${committed.error}"
    }
  }

  private fun stopped(reason: String): OperationOutcome =
    OperationOutcome.Failed(
      report(
        replies = emptyList(),
        gate = "Quality gate: not passed; stopped because $reason. No reply was written.",
        commit = "Commit: not performed.",
      ),
    )

  private fun report(
    replies: List<String>,
    gate: String,
    commit: String,
  ): String {
    val changed = fixes.flatMap(ThreadFix::changedPaths).distinct()
    val fixRows =
      fixes.map { fix ->
        val files = fix.changedPaths.joinToString(", ").ifBlank { "—" }
        "| ${fix.thread.ordinal} (${fix.thread.threadId}) | $files | ${fix.thread.option} |"
      }
    return buildList {
      add("Local review fix for '$branch'")
      add("")
      add("Applied fixes:")
      addAll(listOf("| Thread | Files changed | Option |", "| --- | --- | --- |") + fixRows)
      add("")
      add("Replies:")
      addAll(listOf("| Thread | Reply | First line |", "| --- | --- | --- |") + replies)
      add("")
      add("Learnings recorded:")
      addAll(changed.filter { it.endsWith(HISTORY_FILE) }.map { "- $it" }.ifEmpty { listOf("(none)") })
      add("")
      add("Follow-up specs created:")
      addAll(
        changed.filter { it.startsWith(LegacyProjectStatePaths.FEATURE_SPECS_PREFIX) }
          .map { "- $it" }
          .ifEmpty { listOf("(none)") },
      )
      add("")
      add(gate)
      add("")
      add(commit)
    }.joinToString("\n", postfix = "\n")
  }

  private data class ThreadFix(val thread: PrReviewFixSelectedThread, val reply: String, val changedPaths: List<String>)

  private data class Reply(val fix: ThreadFix, val row: String, val failed: Boolean)
}

private const val VALIDATION_DEFINITION = "validation"
private const val HISTORY_FILE = "agent/history.md"
