package dev.skillbill.desktop.app.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.fail

class ShellEnvironmentTest {
  private val dock = mapOf(
    "PATH" to "/usr/bin:/bin:/usr/sbin:/sbin",
    "SHELL" to "/bin/zsh",
    "HOME" to "/Users/dev",
    "SSH_AUTH_SOCK" to "/private/tmp/launchd/Listeners",
  )

  private fun report(vararg entries: Pair<String, String>, noise: String = "") =
    noise + "__SKILL_BILL_SHELL_ENV__\n" + entries.joinToString("") { (key, value) -> "$key=$value\u0000" }

  @Test
  fun `a Dock start takes the shell's variables and puts its PATH ahead of the inherited one`() {
    var ran: List<String>? = null
    val output = report(
      "PATH" to "/Users/dev/.local/bin:/opt/homebrew/bin:/usr/bin:/bin",
      "CLAUDE_CODE_USE_BEDROCK" to "1",
      "AWS_PROFILE" to "work",
      "SSH_AUTH_SOCK" to "/Users/dev/.1password/agent.sock",
      "HOME" to "/Users/dev",
      noise = "Welcome back\n",
    )

    val environment = withShellEnvironment(dock, "Mac OS X") { command -> ran = command; output }

    assertEquals(listOf("/bin/zsh", "-i", "-l", "-c", "echo __SKILL_BILL_SHELL_ENV__; /usr/bin/env -0"), ran)
    assertEquals("/Users/dev/.local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin", environment["PATH"])
    assertEquals("1", environment["CLAUDE_CODE_USE_BEDROCK"])
    assertEquals("work", environment["AWS_PROFILE"])
    assertEquals("/Users/dev/.1password/agent.sock", environment["SSH_AUTH_SOCK"])
  }

  @Test
  fun `the reporting shell's own session variables are not taken`() {
    val output = report("PATH" to "/usr/bin", "PWD" to "/", "OLDPWD" to "/tmp", "SHLVL" to "2", "_" to "/usr/bin/env", "TERM" to "dumb")

    val environment = withShellEnvironment(dock, "Mac OS X") { output }

    assertEquals(dock.keys, environment.keys)
  }

  @Test
  fun `a value that spans lines stays whole`() {
    val key = "-----BEGIN KEY-----\nabc\n-----END KEY-----"

    val environment = withShellEnvironment(dock, "Mac OS X") { report("SIGNING_KEY" to key, "EMPTY" to "", "EQUALS" to "a=b") }

    assertEquals(key, environment["SIGNING_KEY"])
    assertEquals("", environment["EMPTY"])
    assertEquals("a=b", environment["EQUALS"])
    assertEquals(dock["PATH"], environment["PATH"])
  }

  @Test
  fun `a start from a terminal keeps its environment without running a shell`() {
    val terminal = dock + ("TERM" to "xterm-ghostty")

    assertSame(terminal, withShellEnvironment(terminal, "Mac OS X") { fail("the shell must not run") })
  }

  @Test
  fun `Windows keeps its environment without running a shell`() {
    assertSame(dock, withShellEnvironment(dock, "Windows 11") { fail("the shell must not run") })
  }

  @Test
  fun `a shell that fails or reports nothing leaves the environment unchanged`() {
    assertSame(dock, withShellEnvironment(dock, "Mac OS X") { null })
    assertSame(dock, withShellEnvironment(dock, "Mac OS X") { "zsh: no such option\n" })
    assertSame(dock, withShellEnvironment(dock, "Mac OS X") { "__SKILL_BILL_SHELL_ENV__\n" })
  }

  @Test
  fun `without SHELL macOS asks zsh and Linux asks bash`() {
    assertEquals("/bin/zsh", loginShell(emptyMap(), "Mac OS X"))
    assertEquals("/bin/bash", loginShell(mapOf("SHELL" to " "), "Linux"))
    assertEquals("/usr/bin/fish", loginShell(mapOf("SHELL" to "/usr/bin/fish"), "Linux"))
  }

  @Test
  fun `the entries follow the last marker and malformed ones are skipped`() {
    val output = "__SKILL_BILL_SHELL_ENV__\nnoise\n__SKILL_BILL_SHELL_ENV__\nA=1\u0000=orphan\u0000no-separator\u0000B=2\u0000"

    assertEquals(mapOf("A" to "1", "B" to "2"), parseShellEnvironment(output))
    assertNull(parseShellEnvironment("no marker here"))
  }

  @Test
  fun `merging drops blank and repeated PATH entries`() {
    assertEquals("/a:/b:/c", mergedPath("/a::/b", "/b:/a:/c:"))
    assertEquals("/a", mergedPath("/a", null))
  }
}
