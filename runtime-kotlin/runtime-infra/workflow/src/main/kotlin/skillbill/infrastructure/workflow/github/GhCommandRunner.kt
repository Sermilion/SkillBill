package skillbill.infrastructure.workflow.github

import skillbill.error.core.failureCodeLabel
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.process.BoundedExternalProcessOutput
import skillbill.infrastructure.host.process.BoundedExternalProcessRequest
import skillbill.infrastructure.host.process.BoundedExternalProcessRunner
import java.nio.file.Files
import java.nio.file.Path

/** Runs one GitHub CLI invocation in [root] and reports its exit code and captured output. */
internal fun interface GhCommandRunner {
  fun run(
    root: Path,
    args: List<String>,
  ): GhCommandResult
}

internal data class GhCommandResult(
  val exitCode: Int,
  val stdout: String,
)

internal class ProcessGhCommandRunner(
  private val ghExecutableResolver: (Path) -> Path? = ::resolveGhExecutable,
  private val maxOutputBytes: Long = MAX_OUTPUT_BYTES,
) : GhCommandRunner {
  override fun run(
    root: Path,
    args: List<String>,
  ): GhCommandResult =
    runCatching {
      val executable =
        ghExecutableResolver(root)
          ?: return GhCommandResult(exitCode = 1, stdout = "GitHub CLI executable was not found on PATH.")
      val result =
        BoundedExternalProcessRunner.run(
          BoundedExternalProcessRequest(
            argv = listOf(executable.toString()) + args,
            workingDirectory = root,
            mergeEnvironment = mapOf("GIT_TERMINAL_PROMPT" to "0"),
            deadlineSeconds = COMMAND_TIMEOUT_SECONDS,
            output = BoundedExternalProcessOutput.Captured(maxOutputBytes),
          ),
        )
      if (result.timedOut) {
        GhCommandResult(exitCode = 124, stdout = "GitHub CLI timed out.")
      } else if (result.launchFailure) {
        GhCommandResult(exitCode = 1, stdout = result.output)
      } else {
        GhCommandResult(exitCode = result.exitCode, stdout = result.output)
      }
    }.getOrElse { error ->
      val errorName = error.failureCodeLabel() ?: error::class.simpleName
      GhCommandResult(
        exitCode = 1,
        stdout = error.message?.let { "$errorName: $it" } ?: (errorName ?: "Error"),
      )
    }
}

internal fun GhCommandResult.describeFailure(): String {
  val output =
    stdout.trim().replace(Regex("(?i)(https?://)([^\\s/@]+)@")) { match ->
      "${match.groupValues[1]}<redacted>@"
    }
  return if (output.isBlank()) "GitHub provider exited with code $exitCode." else output
}

private fun resolveGhExecutable(root: Path): Path? {
  val names = executableNames("gh")
  val host = JdkHostPlatformPort
  return host.resolveEnvironment()["PATH"]
    .orEmpty()
    .split(host.pathSeparator)
    .asSequence()
    .mapNotNull { raw -> raw.takeIf(String::isNotBlank)?.let(Path::of) }
    .flatMap { directory -> names.asSequence().map(directory::resolve) }
    .firstOrNull { candidate -> Files.isRegularFile(candidate) && Files.isExecutable(candidate) }
    ?: names.asSequence()
      .map(root::resolve)
      .firstOrNull { candidate -> Files.isRegularFile(candidate) && Files.isExecutable(candidate) }
}

private fun executableNames(base: String): List<String> =
  if (JdkHostPlatformPort.osName.contains("windows", ignoreCase = true)) {
    listOf("$base.exe", "$base.cmd", "$base.bat", base)
  } else {
    listOf(base)
  }

private const val COMMAND_TIMEOUT_SECONDS: Long = 30
private const val MAX_OUTPUT_BYTES: Long = 64 * 1024
