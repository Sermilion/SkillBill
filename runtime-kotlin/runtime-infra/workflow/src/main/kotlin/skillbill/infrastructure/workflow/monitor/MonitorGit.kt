package skillbill.infrastructure.workflow.monitor

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.workflow.process.runGitProcess
import java.nio.file.Path

internal val NO_PROMPT_ENVIRONMENT = mapOf("GIT_TERMINAL_PROMPT" to "0")

internal data class GitRun(
  val ok: Boolean,
  val exitCode: Int,
  val output: String,
)

internal fun monitorGit(
  root: Path,
  vararg args: String,
): GitRun {
  val result = runGitProcess(root, args.toList(), extraEnvironment = NO_PROMPT_ENVIRONMENT)
  val ok = !result.timedOut && result.readFailure == null && result.exitCode == 0
  val detail = result.readFailure?.message ?: if (result.timedOut) "git timed out" else result.output
  return GitRun(ok, result.exitCode, if (ok) result.output else detail)
}

internal fun monitorFetchBranch(
  primary: Path,
  url: String,
  sourceRef: String,
  destination: String,
): GitRun =
  monitorGit(primary, "fetch", "--no-tags", "--no-write-fetch-head", url, "+refs/heads/$sourceRef:$destination")

internal fun GitRun.valueOrThrow(description: String): String =
  if (ok) {
    output
  } else {
    throw SkillBillRuntimeException(
      StandaloneMonitorFailureCode.GIT_COMMAND_FAILED,
      "Standalone monitor could not $description: $output",
    )
  }
