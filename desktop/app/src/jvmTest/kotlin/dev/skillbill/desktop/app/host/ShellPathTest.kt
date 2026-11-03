package dev.skillbill.desktop.app.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.fail

class ShellPathTest {
  private val dock = mapOf("PATH" to "/usr/bin:/bin:/usr/sbin:/sbin", "SHELL" to "/bin/zsh", "HOME" to "/Users/dev")

  @Test
  fun `a Dock start takes the shell's PATH ahead of the inherited one`() {
    var ran: List<String>? = null
    val output = "Welcome back\n__SKILL_BILL_SHELL_PATH__\n/Users/dev/.local/bin:/opt/homebrew/bin:/usr/bin:/bin\n"

    val environment = withShellPath(dock, "Mac OS X") { command -> ran = command; output }

    assertEquals(listOf("/bin/zsh", "-i", "-l", "-c", "echo __SKILL_BILL_SHELL_PATH__; printenv PATH"), ran)
    assertEquals("/Users/dev/.local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin", environment["PATH"])
    assertEquals("/Users/dev", environment["HOME"])
  }

  @Test
  fun `a start from a terminal keeps its environment without running a shell`() {
    val terminal = dock + ("TERM" to "xterm-ghostty")

    val environment = withShellPath(terminal, "Mac OS X") { fail("the shell must not run") }

    assertSame(terminal, environment)
  }

  @Test
  fun `Windows keeps its environment without running a shell`() {
    val environment = withShellPath(dock, "Windows 11") { fail("the shell must not run") }

    assertSame(dock, environment)
  }

  @Test
  fun `a shell that fails or prints no marker leaves the environment unchanged`() {
    assertSame(dock, withShellPath(dock, "Mac OS X") { null })
    assertSame(dock, withShellPath(dock, "Mac OS X") { "zsh: no such option\n" })
    assertSame(dock, withShellPath(dock, "Mac OS X") { "__SKILL_BILL_SHELL_PATH__\n\n" })
  }

  @Test
  fun `without SHELL macOS asks zsh and Linux asks bash`() {
    assertEquals("/bin/zsh", loginShell(emptyMap(), "Mac OS X"))
    assertEquals("/bin/bash", loginShell(mapOf("SHELL" to " "), "Linux"))
    assertEquals("/usr/bin/fish", loginShell(mapOf("SHELL" to "/usr/bin/fish"), "Linux"))
  }

  @Test
  fun `the PATH is the line after the last marker`() {
    val output = "__SKILL_BILL_SHELL_PATH__\nnoise\n__SKILL_BILL_SHELL_PATH__\n /a/bin:/b/bin \ntrailing"

    assertEquals("/a/bin:/b/bin", parseShellPath(output))
    assertNull(parseShellPath("no marker here"))
  }

  @Test
  fun `merging drops blank and repeated entries`() {
    assertEquals("/a:/b:/c", mergedPath("/a::/b", "/b:/a:/c:"))
    assertEquals("/a", mergedPath("/a", null))
  }
}
