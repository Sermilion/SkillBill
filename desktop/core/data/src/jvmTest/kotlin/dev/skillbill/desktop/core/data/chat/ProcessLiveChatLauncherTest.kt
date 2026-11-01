package dev.skillbill.desktop.core.data.chat

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessLiveChatLauncherTest {
  // An unflushed or closed stdin leaves claude waiting for the second message, so every later turn hangs.
  @Test
  fun `each input line reaches the live process as it is sent, and closing the input ends it`() = runBlocking {
    val input = Channel<String>(Channel.UNLIMITED)
    val command = ChatCommand(listOf("cat"), createTempDirectory("live-chat").toString(), System.getenv(), stdin = "")
    input.send("first")

    val outputs = ProcessLiveChatLauncher().open(command, input).transformWhile { output ->
      emit(output)
      if (output == ChatProcessOutput.Line("first")) input.send("second")
      if (output == ChatProcessOutput.Line("second")) input.close()
      output !is ChatProcessOutput.Exited
    }.toList()

    assertEquals(listOf(ChatProcessOutput.Line("first"), ChatProcessOutput.Line("second"), ChatProcessOutput.Exited(0, "")), outputs)
  }
}
