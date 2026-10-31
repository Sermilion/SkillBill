package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeChatTransportRunTest {
  // A bash profile running `eval "$(ssh-agent)"` prints "Agent pid" to stdout, which would otherwise fail every turn as malformed.
  @Test
  fun `login shell output before the stream is skipped and the result ends the turn`() = runTest {
    var command: ChatCommand? = null
    val launcher = ChatProcessLauncher {
      command = it
      flowOf(
        ChatProcessOutput.Line("Agent pid 4242"),
        ChatProcessOutput.Line("""{"type":"system","subtype":"init"}"""),
        ChatProcessOutput.Line("""{"type":"stream_event","event":{"delta":{"type":"text_delta","text":"Hi"}}}"""),
        ChatProcessOutput.Line("""{"type":"result","is_error":false,"result":"Hi"}"""),
        ChatProcessOutput.Exited(0, ""),
      )
    }
    val diagnostics = RecordingDiagnostics()
    val host = HostEnvironment(mapOf("SHELL" to "/bin/bash"), "/home/operator", "")
    val transport = ClaudeChatTransport(host, FakeAppSettingsRepository(), launcher, diagnostics)

    val events = transport.run(chatRequest()).toList()

    assertEquals(listOf(ChatEvent.TextDelta("Hi"), ChatEvent.Completed), events)
    assertEquals(listOf("chat.login_output_skipped"), diagnostics.categories)
    assertEquals("operator prompt", command?.stdin)
    assertEquals("/work/skill-bill", command?.workingDirectory)
  }

  // Through a login shell a missing agent binary exits 127; a process that never started points at the worktree or the shell.
  @Test
  fun `a process that never started fails the turn as a process failure, not a missing binary`() = runTest {
    val launcher = ChatProcessLauncher { flowOf(ChatProcessOutput.StartFailed("The working directory /gone does not exist.")) }
    val host = HostEnvironment(mapOf("SHELL" to "/bin/bash"), "/home/operator", "")
    val transport = ClaudeChatTransport(host, FakeAppSettingsRepository(), launcher, RecordingDiagnostics())

    val events = transport.run(chatRequest()).toList()

    assertEquals(listOf(ChatEvent.Failed(ChatFailure.ProcessFailed, "The working directory /gone does not exist.")), events)
  }
}
