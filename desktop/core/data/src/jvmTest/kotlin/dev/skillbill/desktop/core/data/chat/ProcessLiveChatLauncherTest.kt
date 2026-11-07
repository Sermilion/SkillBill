package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessLiveChatLauncherTest {
  @Test
  fun `a failed process start completes even while the caller keeps input open`() = runTest {
    val directory = createTempDirectory("live-chat-missing")
    val input = Channel<String>(Channel.UNLIMITED)
    try {
      val command = ChatCommand(listOf("cat"), directory.resolve("missing").toString(), emptyMap(), stdin = "")

      val outputs = ProcessLiveChatLauncher(RecordingDiagnostics()).open(command, input).toList()

      assertIs<ChatProcessOutput.StartFailed>(outputs.single())
    } finally {
      input.cancel()
      directory.toFile().deleteRecursively()
    }
  }

  // An unflushed or closed stdin leaves claude waiting for the second message, so every later turn hangs.
  @Test
  fun `each input line reaches the live process as it is sent, and closing the input ends it`() = runBlocking {
    val input = Channel<String>(Channel.UNLIMITED)
    val command = ChatCommand(listOf("cat"), createTempDirectory("live-chat").toString(), System.getenv(), stdin = "")
    input.send("first")

    val outputs = ProcessLiveChatLauncher(RecordingDiagnostics()).open(command, input).transformWhile { output ->
      emit(output)
      if (output == ChatProcessOutput.Line("first")) input.send("second")
      if (output == ChatProcessOutput.Line("second")) input.close()
      output !is ChatProcessOutput.Exited
    }.toList()

    assertEquals(
      listOf(
        ChatProcessOutput.Line("first"),
        ChatProcessOutput.Line("second"),
        ChatProcessOutput.StdoutEnded(null, ""),
        ChatProcessOutput.Exited(0, ""),
      ),
      outputs,
    )
  }

  // Windows has no SIGTERM: destroy() terminates at once, and Git Bash $$ is not a Windows pid.
  @Test
  @DisabledOnOs(OS.WINDOWS)
  fun `a process that ignores SIGTERM is force killed when the collection is cancelled`() = runBlocking {
    val diagnostics = RecordingDiagnostics()
    val input = Channel<String>(Channel.UNLIMITED)
    val command = ChatCommand(
      listOf("sh", "-c", "trap '' TERM; echo $$; while :; do sleep 1; done"),
      createTempDirectory("live-chat").toString(),
      System.getenv(),
      stdin = "",
    )
    val pid = CompletableDeferred<Long>()
    val collection = launch(Dispatchers.IO) {
      ProcessLiveChatLauncher(diagnostics).open(command, input).collect { output ->
        if (output is ChatProcessOutput.Line) pid.complete(output.text.trim().toLong())
      }
    }
    val handle = ProcessHandle.of(pid.await()).orElseThrow()
    collection.cancelAndJoin()
    handle.onExit().join()
    assertTrue("chat.process_force_killed" in diagnostics.categories)
  }
}
