package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.data.chat.ChatCommand
import dev.skillbill.desktop.core.data.chat.ChatProcessLauncher
import dev.skillbill.desktop.core.data.chat.ChatProcessOutput
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidQaCommandRunnerTest {
  @Test
  fun `command arguments and host environment reach the launcher and stdout stays separate from stderr`() = runTest {
    val commands = mutableListOf<ChatCommand>()
    val diagnostics = RecordingDiagnostics()
    val host = HostEnvironment(mapOf("ANDROID_HOME" to "/sdk"), "", "")
    val launcher = ChatProcessLauncher { command ->
      commands += command
      flow {
        emit(ChatProcessOutput.Line("Pixel 8"))
        emit(ChatProcessOutput.Exited(0, "diagnostic output"))
      }
    }
    val runner = AndroidQaCommandRunner(launcher, host, diagnostics, StandardTestDispatcher(testScheduler))
    val executable = Path.of("sdk", "platform-tools", "adb").toAbsolutePath()
    val arguments = listOf("-s", "host:5555", "shell", "getprop", "ro.build.version.release")

    assertEquals(QaCommandResult(0, "Pixel 8\n"), runner.run(executable, arguments))
    assertEquals(listOf(ChatCommand(listOf(executable.toString()) + arguments, executable.parent.toString(), host.variables, "")), commands)
    assertEquals(listOf("desktop_qa_command_stderr_observed"), diagnostics.categories)
  }

  @Test
  fun `timeout and caller cancellation release the active launcher collection`() = runTest {
    var releases = 0
    val launcher = ChatProcessLauncher {
      flow<ChatProcessOutput> {
        try {
          awaitCancellation()
        } finally {
          releases++
        }
      }
    }
    val runner = AndroidQaCommandRunner(
      launcher, HostEnvironment(emptyMap(), "", ""), RecordingDiagnostics(), StandardTestDispatcher(testScheduler),
    )
    val timed = async { runner.run(Path.of("adb"), listOf("devices", "-l")) }

    assertEquals(QaCommandResult(null, "", "command_timed_out"), timed.await())
    assertEquals(1, releases)
    val cancelled = launch { runner.run(Path.of("adb"), listOf("devices", "-l")) }
    runCurrent()
    cancelled.cancelAndJoin()
    assertTrue(cancelled.isCancelled)
    assertEquals(2, releases)
  }

  @Test
  fun `oversized stdout fails discovery and releases the launcher before accepting an exit code`() = runTest {
    var released = false
    val launcher = ChatProcessLauncher {
      flow {
        try {
          emit(ChatProcessOutput.Line("x".repeat(256 * 1024)))
          emit(ChatProcessOutput.Exited(0, ""))
        } finally {
          released = true
        }
      }
    }
    val diagnostics = RecordingDiagnostics()
    val runner = AndroidQaCommandRunner(
      launcher, HostEnvironment(emptyMap(), "", ""), diagnostics, StandardTestDispatcher(testScheduler),
    )

    assertEquals(QaCommandResult(null, "", "command_output_truncated"), runner.run(Path.of("adb"), listOf("devices", "-l")))
    assertTrue(released)
    assertEquals(listOf("desktop_qa_command_output_truncated"), diagnostics.categories)
  }
}
