package dev.skillbill.desktop.core.data.chat

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatTurnRunnerTest {
  @Test
  fun `POSIX alias lookup launches the available executable once with literal arguments`() {
    assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "Requires the POSIX login shell under test")
    val directory = Files.createTempDirectory("chat-alias")
    try {
      val executable = directory.resolve("available agent")
      Files.writeString(executable, "#!/bin/sh\nprintf '%s\\n' \"\$@\"\n")
      assertTrue(executable.toFile().setExecutable(true))
      val arguments = listOf("--acp", "literal spaces and \$(not-a-command)")
      val command = agentCommand(
        listOf(directory.resolve("missing-agent").toString(), executable.toString()),
        arguments,
        "/bin/sh",
      )

      val process = ProcessBuilder(command).redirectErrorStream(true).start()
      val output = process.inputStream.bufferedReader().use { it.readLines() }

      assertEquals(0, process.waitFor(), output.joinToString("\n"))
      assertEquals(arguments, output)
    } finally {
      directory.toFile().deleteRecursively()
    }
  }
}
