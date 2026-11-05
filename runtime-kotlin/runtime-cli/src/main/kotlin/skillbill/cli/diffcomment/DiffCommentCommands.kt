package skillbill.cli.diffcomment

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.application.review.diffcomment.DiffCommentAgentAccess
import skillbill.application.review.diffcomment.DiffCommentService
import skillbill.application.review.diffcomment.addAgentThread
import skillbill.application.review.diffcomment.model.DiffCommentAgentThreadInput
import skillbill.application.review.diffcomment.model.DiffCommentListResult
import skillbill.application.review.diffcomment.model.DiffCommentThreadResult
import skillbill.application.review.diffcomment.reply
import skillbill.application.review.diffcomment.setState
import skillbill.application.review.diffcomment.toDiffCommentThreadContract
import skillbill.application.review.diffcomment.toDiffCommentThreadListContract
import skillbill.application.review.diffcomment.toFailureContract
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.DocumentedNoOpCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliFormat
import skillbill.cli.model.CliRunInputs

@Inject
class DiffCommentCommand(
  list: DiffCommentListCommand,
  add: DiffCommentAddCommand,
  reply: DiffCommentReplyCommand,
  setState: DiffCommentSetStateCommand,
) : DocumentedNoOpCliCommand(
    "diff-comments",
    "List, add, reply to and change the state of local diff comments shown in the desktop Changes window. " +
      "These are not GitHub PR comments.",
  ) {
  init {
    subcommands(list, add, reply, setState)
  }
}

@Inject
class DiffCommentListCommand(
  private val service: DiffCommentService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("list", "List local diff comment threads for a repository branch.") {
  private val repo by option("--repo", help = "Repository root. Defaults to the current working directory.")
  private val branch by option("--branch", help = "Branch name. Defaults to the current branch.")
  private val threadState by option("--state", help = "Filter threads by state.")
    .choice(DiffCommentAgentAccess.states)
  private val format by formatOption()

  override fun run() {
    val repoRoot = resolveCliRepositoryRoot(repo, inputs)
    when (val result = service.list(repoRoot, branch)) {
      is DiffCommentListResult.Unavailable ->
        state.complete(result.toFailureContract()!!.toPayload(), format, exitCode = 1)
      is DiffCommentListResult.Listed -> {
        val threads = result.threads.filter { threadState == null || it.state.wireValue == threadState }
        state.complete(toDiffCommentThreadListContract(result.branch, threads).toPayload(), format)
      }
    }
  }
}

@Inject
class DiffCommentAddCommand(
  private val service: DiffCommentService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("add", "Add an agent diff comment thread.") {
  private val repo by option("--repo", help = "Repository root. Defaults to the current working directory.")
  private val body by option("--body", help = "Comment body.").required()
  private val path by option("--path", help = "Repository-relative file path.")
  private val line by option("--line", help = "Line number.").int()
  private val side by option("--side", help = "Diff side for a line anchor.")
    .choice(DiffCommentAgentAccess.sides)
  private val agentId by option("--agent-id", help = "Agent identifier.")
  private val format by formatOption()

  override fun run() {
    if (line != null && path == null) {
      throw UsageError("--path is required when --line is set.")
    }
    if (side != null && line == null) {
      throw UsageError("--line is required when --side is set.")
    }
    val repoRoot = resolveCliRepositoryRoot(repo, inputs)
    state.completeThreadResult(
      service.addAgentThread(
        repoRoot,
        DiffCommentAgentThreadInput(
          path = path,
          side = side,
          line = line,
          body = body,
          agentId = agentId,
        ),
      ),
      contextThreadId = "",
      format = format,
    )
  }
}

@Inject
class DiffCommentReplyCommand(
  private val service: DiffCommentService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("reply", "Reply to a diff comment thread as an agent.") {
  private val repo by option("--repo", help = "Repository root. Defaults to the current working directory.")
  private val threadId by option("--thread", help = "Thread id.").required()
  private val body by option("--body", help = "Reply body.").required()
  private val outcome by option("--outcome", help = "Agent outcome.")
    .choice(DiffCommentAgentAccess.outcomes)
  private val agentId by option("--agent-id", help = "Agent identifier.")
  private val format by formatOption()

  override fun run() {
    resolveCliRepositoryRoot(repo, inputs)
    state.completeThreadResult(
      service.reply(threadId, agentId, body, outcome),
      contextThreadId = threadId,
      format = format,
    )
  }
}

@Inject
class DiffCommentSetStateCommand(
  private val service: DiffCommentService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("set-state", "Change a diff comment thread state.") {
  private val repo by option("--repo", help = "Repository root. Defaults to the current working directory.")
  private val threadId by option("--thread", help = "Thread id.").required()
  private val threadState by option("--state", help = "New thread state.")
    .choice(DiffCommentAgentAccess.editableStates)
    .required()
  private val format by formatOption()

  override fun run() {
    resolveCliRepositoryRoot(repo, inputs)
    state.completeThreadResult(
      service.setState(threadId, threadState),
      contextThreadId = threadId,
      format = format,
    )
  }
}

private fun CliRunState.completeThreadResult(
  result: DiffCommentThreadResult,
  contextThreadId: String,
  format: CliFormat,
) {
  val failure = result.toFailureContract(contextThreadId)
  if (failure != null) {
    complete(failure.toPayload(), format, exitCode = 1)
    return
  }
  val saved = result as DiffCommentThreadResult.Saved
  complete(saved.thread.toDiffCommentThreadContract().toPayload(), format)
}
