package dev.skillbill.desktop.app.host

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val PATH = "PATH"
private const val MARKER = "__SKILL_BILL_SHELL_PATH__"
private const val TIMEOUT_SECONDS = 3L
private const val READER_JOIN_MS = 500L
private const val MAX_OUTPUT_BYTES = 256 * 1024

/**
 * An app started from the Dock, Finder or a desktop launcher inherits the session's bare PATH, not the one the user's shell
 * builds in its rc files, so agents installed under `~/.local/bin`, nvm or Homebrew are missing from every process the app
 * starts. A start from a terminal already carries that PATH, and Windows keeps PATH in the registry; both are left as they are.
 * Otherwise the user's interactive login shell reports its PATH once, and its entries go ahead of the inherited ones. A shell
 * that fails or times out leaves the environment unchanged.
 */
fun withShellPath(
  environment: Map<String, String>,
  osName: String,
  readShell: (List<String>) -> String? = ::readShellOutput,
): Map<String, String> {
  if (osName.startsWith("Windows") || !environment["TERM"].isNullOrBlank()) return environment
  val shellPath = readShell(shellPathCommand(loginShell(environment, osName)))?.let(::parseShellPath) ?: return environment
  return environment + (PATH to mergedPath(shellPath, environment[PATH]))
}

internal fun loginShell(environment: Map<String, String>, osName: String): String =
  environment["SHELL"]?.takeIf(String::isNotBlank) ?: if (osName.startsWith("Mac")) "/bin/zsh" else "/bin/bash"

/** Interactive so `.zshrc` and `.bashrc` run; `echo`, `;` and the external `printenv` read the same in POSIX shells and fish. */
internal fun shellPathCommand(shell: String): List<String> = listOf(shell, "-i", "-l", "-c", "echo $MARKER; printenv $PATH")

/** rc files can print before the marker; the line after the last marker is the PATH. */
internal fun parseShellPath(output: String): String? {
  val lines = output.lines()
  val marker = lines.indexOfLast { it.trim() == MARKER }
  if (marker < 0) return null
  return lines.getOrNull(marker + 1)?.trim()?.takeIf { '/' in it }
}

/** The shell's entries first, then inherited ones the shell does not list. */
internal fun mergedPath(shellPath: String, inherited: String?): String =
  (shellPath.split(File.pathSeparatorChar) + inherited.orEmpty().split(File.pathSeparatorChar))
    .filter(String::isNotBlank)
    .distinct()
    .joinToString(File.pathSeparator)

/** Runs [command] with no stdin, so an interactive shell cannot wait for input, and returns its stdout, or null on failure. */
private fun readShellOutput(command: List<String>): String? {
  val process = try {
    ProcessBuilder(command)
      .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
  } catch (failure: IOException) {
    warn("could not start ${command.first()}: ${failure.message}")
    return null
  }
  val output = ByteArrayOutputStream()
  // A daemon an rc file starts can keep stdout open after the shell exits, so the read runs beside the wait.
  val reader = thread(isDaemon = true, name = "shell-path") {
    try {
      process.inputStream.use { stream ->
        val buffer = ByteArray(8192)
        while (true) {
          val read = stream.read(buffer)
          if (read < 0) break
          synchronized(output) { if (output.size() < MAX_OUTPUT_BYTES) output.write(buffer, 0, read) }
        }
      }
    } catch (_: IOException) {
      // The process was destroyed; what was read so far is all there is.
    }
  }
  return try {
    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      warn("${command.first()} did not report its PATH within $TIMEOUT_SECONDS seconds")
      null
    } else {
      reader.join(READER_JOIN_MS)
      synchronized(output) { output.toString(StandardCharsets.UTF_8) }
    }
  } catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    null
  } finally {
    process.toHandle().descendants().forEach { it.destroy() }
    process.destroy()
  }
}

private fun warn(message: String) {
  System.getLogger("dev.skillbill.desktop").log(System.Logger.Level.WARNING, "desktop:shell-path-unavailable $message")
}
