package skillbill.infrastructure.workflow.process

import skillbill.infrastructure.host.process.BoundedExternalProcessOutput
import skillbill.infrastructure.host.process.BoundedExternalProcessRequest
import skillbill.infrastructure.host.process.BoundedExternalProcessResult
import skillbill.infrastructure.host.process.BoundedExternalProcessRunner
import java.io.IOException
import java.nio.file.Path

internal fun invokeGitProcess(
  repoRoot: Path,
  args: List<String>,
  stdin: ByteArray?,
  extraEnvironment: Map<String, String> = emptyMap(),
  deadlineSeconds: Long = gitTimeoutSeconds(args),
): GitProcessResult {
  val result =
    BoundedExternalProcessRunner.run(
      BoundedExternalProcessRequest(
        argv = gitArgv(repoRoot, args),
        mergeEnvironment = extraEnvironment,
        stdin = stdin,
        deadlineSeconds = deadlineSeconds,
        output = BoundedExternalProcessOutput.Captured(capBytes = null),
      ),
    )
  return GitProcessResult(
    output = result.output.trim(),
    readFailure = gitReadFailure(result),
    timedOut = result.timedOut,
    exitCode = if (result.timedOut) -1 else result.exitCode,
  )
}

internal data class GitProcessBoundedLinesResult(
  val timedOut: Boolean,
  val readFailure: IOException?,
  val exitCode: Int,
)

internal fun invokeGitProcessWithBoundedLines(
  repoRoot: Path,
  args: List<String>,
  readLineMaxBytes: Int,
  shouldStopReading: () -> Boolean,
  onLine: (BoundedDiffLine) -> Unit,
): GitProcessBoundedLinesResult {
  val result =
    BoundedExternalProcessRunner.run(
      BoundedExternalProcessRequest(
        argv = gitArgv(repoRoot, args),
        deadlineSeconds = gitTimeoutSeconds(args),
        output =
          BoundedExternalProcessOutput.Lines(
            maxLineBytes = readLineMaxBytes,
            shouldStop = shouldStopReading,
            onLine = { line -> onLine(BoundedDiffLine(text = line.text, truncated = line.truncated)) },
          ),
      ),
    )
  return GitProcessBoundedLinesResult(
    timedOut = result.timedOut,
    readFailure = gitReadFailure(result),
    exitCode = if (result.timedOut) -1 else result.exitCode,
  )
}

private fun gitArgv(
  repoRoot: Path,
  args: List<String>,
): List<String> =
  listOf(
    "git",
    "--no-pager",
    "-c",
    "diff.renameLimit=$GIT_DIFF_RENAME_LIMIT",
    "-C",
    repoRoot.toString(),
  ) + args

private fun gitReadFailure(result: BoundedExternalProcessResult): IOException? =
  result.readFailure ?: if (result.launchFailure) IOException(result.output) else null
