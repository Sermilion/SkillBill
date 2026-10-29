package dev.skillbill.desktop.feature.terminal

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** What to start in a tab: a login shell in [workingDirectory] with [environment]. */
data class TerminalLaunch(val command: List<String>, val workingDirectory: Path, val environment: Map<String, String>)

/**
 * The launch rules, free of I/O. The shell is the user's `SHELL`, else `/bin/zsh` on macOS and `/bin/bash` elsewhere, run as a
 * login shell so its PATH matches a normal terminal.
 */
fun terminalLaunch(environment: Map<String, String>, osName: String, workingDirectory: Path): TerminalLaunch {
  val shell = environment["SHELL"]?.takeIf { it.isNotBlank() } ?: if (osName.startsWith("Mac")) "/bin/zsh" else "/bin/bash"
  return TerminalLaunch(listOf(shell, "-l"), workingDirectory, environment + ("TERM" to "xterm-256color"))
}

/** A requested directory is kept even when it is gone, so its start fails visibly; no request means home. */
fun tabDirectory(requested: Path?, userHome: Path): Path = requested ?: userHome

/** The tab's default name: the project [directory] belongs to, or `~` for home. */
fun tabName(directory: Path, userHome: Path): String =
  terminalProjectName(directory.toString(), userHome.toString(), { Files.isDirectory(Path.of(it)) }) { git ->
    val path = Path.of(git)
    if (!Files.isRegularFile(path)) null else try { Files.readString(path) } catch (_: IOException) { null }
  }

/**
 * The project [path] belongs to: the name of the checkout that holds it, found at the nearest `.git` above it. A linked worktree's
 * `.git` file points into its main checkout's `.git/worktrees`, so a worktree is named after that main checkout, not its own
 * folder. A directory outside any checkout is named after itself, and the home directory is `~`.
 */
fun terminalProjectName(path: String, userHome: String, isDirectory: (String) -> Boolean, readGitFile: (String) -> String?): String {
  if (path.trimEnd('/') == userHome.trimEnd('/')) return "~"
  var directory = path.trimEnd('/')
  while (directory.isNotEmpty()) {
    val git = "$directory/.git"
    if (isDirectory(git)) return lastSegment(directory)
    val pointer = readGitFile(git)
    if (pointer != null) return worktreeMainCheckout(pointer) ?: lastSegment(directory)
    directory = directory.substringBeforeLast('/', "")
  }
  return lastSegment(path)
}

private const val WORKTREES_MARKER = "/.git/worktrees/"

private fun worktreeMainCheckout(pointer: String): String? {
  val gitDir = pointer.lineSequence().firstOrNull { it.startsWith("gitdir:") }?.removePrefix("gitdir:")?.trim() ?: return null
  val marker = gitDir.indexOf(WORKTREES_MARKER)
  return if (marker > 0) lastSegment(gitDir.substring(0, marker)) else null
}

private fun lastSegment(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
