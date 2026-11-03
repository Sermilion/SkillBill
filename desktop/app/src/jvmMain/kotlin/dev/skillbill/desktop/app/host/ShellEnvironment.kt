package dev.skillbill.desktop.app.host

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val PATH = "PATH"
private const val MARKER = "__SKILL_BILL_SHELL_ENV__"
private const val TIMEOUT_SECONDS = 3L
private const val READER_JOIN_MS = 500L
private const val MAX_OUTPUT_BYTES = 1024 * 1024

/** Variables that describe the reporting shell itself, not the user's setup. */
private val shellSessionKeys = setOf("PWD", "OLDPWD", "SHLVL", "_", "TERM")

/**
 * An app started from the Dock, Finder or a desktop launcher inherits the session's bare environment, not the one the user's
 * shell builds in its rc files: agents installed under `~/.local/bin`, nvm or Homebrew are missing from PATH, and variables
 * such as `CLAUDE_CODE_USE_BEDROCK`, `AWS_PROFILE` or API keys exported in `.zshrc` are unset, in every process the app starts.
 * A start from a terminal already carries that environment, and Windows keeps it in the registry; both are left as they are.
 * Otherwise the user's interactive login shell reports its environment once: its variables replace the inherited ones, and its
 * PATH entries go ahead of the inherited ones. A shell that fails or times out leaves the environment unchanged.
 */
fun withShellEnvironment(
  environment: Map<String, String>,
  osName: String,
  readShell: (List<String>) -> String? = ::readShellOutput,
): Map<String, String> {
  if (osName.startsWith("Windows") || !environment["TERM"].isNullOrBlank()) return environment
  val shell = readShell(shellEnvironmentCommand(loginShell(environment, osName)))?.let(::parseShellEnvironment) ?: return environment
  val merged = environment + (shell - shellSessionKeys)
  val shellPath = shell[PATH] ?: return merged
  return merged + (PATH to mergedPath(shellPath, environment[PATH]))
}

internal fun loginShell(environment: Map<String, String>, osName: String): String =
  environment["SHELL"]?.takeIf(String::isNotBlank) ?: if (osName.startsWith("Mac")) "/bin/zsh" else "/bin/bash"

/** Interactive so `.zshrc` and `.bashrc` run; `echo`, `;` and the external `env` read the same in POSIX shells and fish. */
internal fun shellEnvironmentCommand(shell: String): List<String> = listOf(shell, "-i", "-l", "-c", "echo $MARKER; /usr/bin/env -0")

/** rc files can print before the marker; after the last marker come NUL-terminated `KEY=value` entries, so values may span lines. */
internal fun parseShellEnvironment(output: String): Map<String, String>? {
  val marker = output.lastIndexOf("$MARKER\n")
  if (marker < 0) return null
  val entries = output.substring(marker + MARKER.length + 1).split('\u0000').mapNotNull { entry ->
    val separator = entry.indexOf('=')
    if (separator <= 0) null else entry.substring(0, separator) to entry.substring(separator + 1)
  }
  return entries.toMap().takeIf { it.isNotEmpty() }
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
  val reader = thread(isDaemon = true, name = "shell-environment") {
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
      warn("${command.first()} did not report its environment within $TIMEOUT_SECONDS seconds")
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

/** Names the shell only; the environment it reports can hold secrets and is never logged. */
private fun warn(message: String) {
  System.getLogger("dev.skillbill.desktop").log(System.Logger.Level.WARNING, "desktop:shell-environment-unavailable $message")
}
