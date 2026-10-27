package dev.skillbill.tray.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalLaunchTest {
  @Test
  fun `the user's shell runs as a login shell with the terminal type set and the rest of the environment kept`() {
    val launch = terminalLaunch(
      environment = mapOf("SHELL" to "/usr/local/bin/fish", "PATH" to "/usr/bin"),
      osName = "Linux",
      userHome = "/home/user",
      requestedDirectory = "/work/repo",
      isDirectory = { true },
    )

    assertEquals(listOf("/usr/local/bin/fish", "-l"), launch.command)
    assertEquals("/work/repo", launch.workingDirectory)
    assertEquals(mapOf("SHELL" to "/usr/local/bin/fish", "PATH" to "/usr/bin", "TERM" to "xterm-256color"), launch.environment)
  }

  @Test
  fun `a missing shell falls back to zsh on macOS`() {
    val launch = terminalLaunch(mapOf("PATH" to "/usr/bin"), "Mac OS X", "/Users/user", null) { true }

    assertEquals(listOf("/bin/zsh", "-l"), launch.command)
  }

  @Test
  fun `a null or missing directory falls back to the home directory`() {
    val environment = mapOf("SHELL" to "/bin/bash")

    assertEquals("/home/user", terminalLaunch(environment, "Linux", "/home/user", "/deleted/worktree") { false }.workingDirectory)
    assertEquals("/home/user", terminalLaunch(environment, "Linux", "/home/user", null) { true }.workingDirectory)
  }

  @Test
  fun `a tab is named after the checkout that holds its directory, a worktree after its main checkout`() {
    val gitDirectories = setOf("/code/skill-bill/.git")
    val gitFiles = mapOf("/code/skill-bill/.git/skill-bill/worktrees/w1/.git" to "gitdir: /code/skill-bill/.git/worktrees/w1\n")
    fun name(path: String) = terminalProjectName(path, "/home/user", { it in gitDirectories }) { gitFiles[it] }

    assertEquals("skill-bill", name("/code/skill-bill/desktop-tray/feature"))
    assertEquals("skill-bill", name("/code/skill-bill/.git/skill-bill/worktrees/w1"))
    assertEquals("notes", name("/tmp/notes/"), "A directory outside any checkout is named after itself.")
    assertEquals("~", name("/home/user/"))
  }
}
