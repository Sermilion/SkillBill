package skillbill.infrastructure.workflow.process

import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.process.BoundedExternalProcessOutput
import skillbill.infrastructure.host.process.BoundedExternalProcessRequest
import skillbill.infrastructure.host.process.BoundedExternalProcessRunner
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val gitStdoutLog: Logger = Logger.getLogger("skillbill.infrastructure.workflow.process.GitStdoutCapture")

internal sealed interface GitStdoutCapture {
  data class Captured(val text: String) : GitStdoutCapture

  data object TooLarge : GitStdoutCapture

  data class Failed(val message: String) : GitStdoutCapture
}

internal fun captureGitStdout(
  repoRoot: Path,
  args: List<String>,
  maxBytes: Long,
  acceptedExitCodes: Set<Int>,
): GitStdoutCapture {
  val outputFile =
    try {
      Files.createTempFile(JdkHostPlatformPort.resolveTemporaryDirectory(), "skillbill-git", ".out")
    } catch (error: IOException) {
      return GitStdoutCapture.Failed("git output file could not be created: ${error.message}")
    }
  return try {
    val result =
      BoundedExternalProcessRunner.run(
        BoundedExternalProcessRequest(
          argv = gitArgv(repoRoot, args),
          mergeStderr = false,
          deadlineSeconds = GIT_TIMEOUT_SECONDS,
          output = BoundedExternalProcessOutput.RedirectToFile(outputFile),
        ),
      )
    val readFailure = result.readFailure
    when {
      result.timedOut -> GitStdoutCapture.Failed(gitTimedOutError(args))
      result.launchFailure -> GitStdoutCapture.Failed("git could not be launched: ${result.output}")
      readFailure != null -> GitStdoutCapture.Failed("git output could not be read: ${readFailure.message}")
      result.exitCode !in acceptedExitCodes ->
        GitStdoutCapture.Failed("git ${args.joinToString(" ")} exited with code ${result.exitCode}")
      Files.size(outputFile) > maxBytes -> GitStdoutCapture.TooLarge
      else -> GitStdoutCapture.Captured(result.output)
    }
  } catch (error: IOException) {
    GitStdoutCapture.Failed("git output could not be read: ${error.message}")
  } catch (error: InterruptedException) {
    Thread.currentThread().interrupt()
    throw error
  } finally {
    try {
      Files.deleteIfExists(outputFile)
    } catch (error: IOException) {
      gitStdoutLog.log(
        Level.WARNING,
        "seam=git_stdout_capture value_used=leaked_temp_file value_expected=deleted_temp_file file=$outputFile",
        error,
      )
    }
  }
}
