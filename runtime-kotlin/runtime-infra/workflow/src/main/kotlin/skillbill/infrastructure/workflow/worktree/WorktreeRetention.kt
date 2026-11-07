package skillbill.infrastructure.workflow.worktree

import skillbill.infrastructure.workflow.process.runGitProcess
import skillbill.ports.monitor.model.MonitorRetentionReason
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal data class WorktreeRetention(val reason: MonitorRetentionReason, val detail: String)

internal fun worktreePathProblem(
  path: Path,
  ownedRoot: Path,
  rootLabel: String,
): String? =
  try {
    when {
      Files.isSymbolicLink(path) -> "the worktree path is a symbolic link"
      !path.toRealPath().startsWith(ownedRoot.toRealPath()) -> "the worktree path left $rootLabel"
      else -> null
    }
  } catch (failure: IOException) {
    "the worktree path could not be verified: ${failure.message}"
  }

internal fun worktreeDirtyRetention(path: Path): WorktreeRetention? {
  val result = worktreeGit(path, "status", "--porcelain", "--untracked-files=all")
  val lines = result.output.lines().filter(String::isNotBlank)
  val reason =
    when {
      !result.ok -> MonitorRetentionReason.PUBLICATION_UNKNOWN
      lines.any { !it.startsWith("??") } -> MonitorRetentionReason.DIRTY_WORKTREE
      lines.isNotEmpty() -> MonitorRetentionReason.UNTRACKED_FILES
      else -> null
    }
  return reason?.let {
    WorktreeRetention(
      it,
      if (result.ok) "the worktree has ${lines.size} changed path(s)" else "git status failed: ${result.output}",
    )
  }
}

internal data class WorktreeGitResult(val ok: Boolean, val output: String, val timedOut: Boolean = false)

internal fun worktreeGit(
  root: Path,
  vararg args: String,
): WorktreeGitResult {
  val result = runGitProcess(root, args.toList(), extraEnvironment = mapOf("GIT_TERMINAL_PROMPT" to "0"))
  val ok = !result.timedOut && result.readFailure == null && result.exitCode == 0
  return WorktreeGitResult(
    ok,
    result.readFailure?.message ?: if (result.timedOut) "git timed out" else result.output,
    result.timedOut,
  )
}

internal fun parseWorktreeRecords(output: String): List<List<String>> {
  val records = mutableListOf<MutableList<String>>()
  output.split('\u0000').filter(String::isNotEmpty).forEach { field ->
    if (field.startsWith("worktree ")) records += mutableListOf(field) else records.lastOrNull()?.add(field)
  }
  return records
}
