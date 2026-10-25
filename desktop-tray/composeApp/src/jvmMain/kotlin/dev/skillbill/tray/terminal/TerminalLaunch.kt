package dev.skillbill.tray.terminal

/** What to start in the terminal pane: a login shell in [workingDirectory] with [environment]. */
data class TerminalLaunch(
  val command: List<String>,
  val workingDirectory: String,
  val environment: Map<String, String>,
)

/** The directory a tab starts in: [isHome] when it is the user's home, which also stands in for a missing or unusable request. */
data class ResolvedTerminalDirectory(val path: String, val isHome: Boolean)

/** A missing, blank or non-directory [requested] directory resolves to [userHome]. */
fun resolveTerminalDirectory(userHome: String, requested: String?, isDirectory: (String) -> Boolean): ResolvedTerminalDirectory {
  val path = requested?.takeIf { it.isNotBlank() && isDirectory(it) } ?: userHome
  return ResolvedTerminalDirectory(path, isHome = path == userHome)
}

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
