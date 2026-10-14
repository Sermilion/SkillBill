package skillbill.infrastructure.workflow.process

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.io.IOException
import java.nio.file.Path

internal const val GIT_TIMEOUT_SECONDS = 30L
internal const val GIT_HOOKED_COMMAND_TIMEOUT_SECONDS = 600L
internal const val GIT_DIFF_RENAME_LIMIT = "1000"

private val HOOKED_GIT_COMMANDS = setOf("commit", "push")

internal fun gitTimeoutSeconds(args: List<String>): Long {
  val command = args.firstOrNull { arg -> !arg.startsWith("-") } ?: return GIT_TIMEOUT_SECONDS
  return if (command in HOOKED_GIT_COMMANDS) GIT_HOOKED_COMMAND_TIMEOUT_SECONDS else GIT_TIMEOUT_SECONDS
}

internal fun gitTimedOutError(args: List<String>): String =
  "git ${args.joinToString(" ")} timed out after ${gitTimeoutSeconds(args)}s."

internal fun runGitCommand(
  repoRoot: Path,
  vararg args: String,
): WorkflowGitOperationResult = runGitCommand(repoRoot, args.toList())

internal fun runGitCommand(
  repoRoot: Path,
  env: Map<String, String>,
  vararg args: String,
): WorkflowGitOperationResult = runGitCommand(repoRoot, env, args.toList())

internal fun runGitCommand(
  repoRoot: Path,
  args: List<String>,
): WorkflowGitOperationResult = runGitCommand(repoRoot, emptyMap(), args)

internal fun runGitCommand(
  repoRoot: Path,
  env: Map<String, String>,
  args: List<String>,
): WorkflowGitOperationResult {
  val argList = args
  val result = runGitProcess(repoRoot, argList, stdin = null, extraEnvironment = env)
  return when {
    result.timedOut ->
      WorkflowGitOperationResult.Failed(
        error = gitTimedOutError(argList),
      )
    result.readFailure != null ->
      WorkflowGitOperationResult.Failed(
        error = result.readFailure.message.orEmpty(),
      )
    result.exitCode == 0 -> WorkflowGitOperationResult.Ok(value = result.output)
    else ->
      WorkflowGitOperationResult.Failed(
        error = "git ${argList.joinToString(" ")} failed with exit code ${result.exitCode}: ${result.output}",
      )
  }
}

internal fun runGitForActivity(
  repoRoot: Path,
  args: List<String>,
): WorkflowGitOperationResult {
  val result = runGitProcess(repoRoot, args)
  return when {
    result.timedOut ->
      WorkflowGitOperationResult.Failed(
        error = gitTimedOutError(args),
      )
    result.readFailure != null ->
      WorkflowGitOperationResult.Failed(
        error = result.readFailure.message.orEmpty(),
      )
    result.exitCode == 0 -> WorkflowGitOperationResult.Ok(value = result.output)
    else -> WorkflowGitOperationResult.Failed(error = result.output)
  }
}

internal fun runGitCommandWithStdin(
  repoRoot: Path,
  args: List<String>,
  stdin: ByteArray,
): WorkflowGitOperationResult {
  val result = runGitProcess(repoRoot, args, stdin)
  return when {
    result.timedOut ->
      WorkflowGitOperationResult.Failed(
        error = gitTimedOutError(args),
      )
    result.readFailure != null ->
      WorkflowGitOperationResult.Failed(
        error = result.readFailure.message.orEmpty(),
      )
    result.exitCode == 0 -> WorkflowGitOperationResult.Ok(value = result.output)
    else ->
      WorkflowGitOperationResult.Failed(
        error = "git ${args.joinToString(" ")} failed with exit code ${result.exitCode}: ${result.output}",
      )
  }
}

internal fun runGitProcess(
  repoRoot: Path,
  args: List<String>,
  stdin: ByteArray? = null,
  extraEnvironment: Map<String, String> = emptyMap(),
): GitProcessResult = invokeGitProcess(repoRoot, args, stdin, extraEnvironment)

internal data class GitProcessResult(
  val output: String,
  val readFailure: IOException?,
  val timedOut: Boolean = false,
  val exitCode: Int = -1,
)

internal fun WorkflowGitOperationResult.withValue(value: String): WorkflowGitOperationResult =
  when (this) {
    is WorkflowGitOperationResult.Ok -> copy(value = value)
    is WorkflowGitOperationResult.Failed -> this
  }
