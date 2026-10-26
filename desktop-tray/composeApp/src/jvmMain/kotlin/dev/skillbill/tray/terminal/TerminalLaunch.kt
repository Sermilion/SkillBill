package dev.skillbill.tray.terminal

/** What to start in the terminal pane: a login shell in [workingDirectory] with [environment]. */
data class TerminalLaunch(
  val command: List<String>,
  val workingDirectory: String,
  val environment: Map<String, String>,
)

/** The directory a tab starts in and its default [name], the project that directory belongs to. */
data class ResolvedTerminalDirectory(val path: String, val name: String)

/**
 * A missing, blank or non-directory [requested] directory resolves to [userHome]. The name comes from [terminalProjectName];
 * [readGitFile] returns the text of a `.git` path that is a file (a linked worktree's pointer), null otherwise.
 */
fun resolveTerminalDirectory(
  userHome: String,
  requested: String?,
  isDirectory: (String) -> Boolean,
  readGitFile: (String) -> String? = { null },
): ResolvedTerminalDirectory {
  val path = requested?.takeIf { it.isNotBlank() && isDirectory(it) } ?: userHome
  return ResolvedTerminalDirectory(path, terminalProjectName(path, userHome, isDirectory, readGitFile))
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

/**
 * The launch rules, free of I/O. The shell is the user's `SHELL` (else the platform default) run as a login shell so its PATH
 * matches a normal terminal; a missing or non-directory [requestedDirectory] falls back to [userHome].
 */
fun terminalLaunch(
  environment: Map<String, String>,
  osName: String,
  userHome: String,
  requestedDirectory: String?,
  isDirectory: (String) -> Boolean,
): TerminalLaunch {
  val shell = environment["SHELL"]?.takeIf { it.isNotBlank() } ?: if (osName.startsWith("Mac")) "/bin/zsh" else "/bin/bash"
  return TerminalLaunch(
    command = listOf(shell, "-l"),
    workingDirectory = resolveTerminalDirectory(userHome, requestedDirectory, isDirectory).path,
    environment = environment + ("TERM" to "xterm-256color"),
  )
}
