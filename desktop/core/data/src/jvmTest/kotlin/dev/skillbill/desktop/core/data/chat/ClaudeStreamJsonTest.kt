package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeStreamJsonTest {
  // A dropped or duplicated line loses agent output or hides an error from the operator.
  @Test
  fun `stream lines decode to events in order without repeating text`() {
    val lines = listOf(
      """{"type":"system","subtype":"init"}""",
      """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hi"}}}""",
      """{"type":"assistant","message":{"content":[{"type":"text","text":"Hi"},{"type":"tool_use","name":"Read"}]}}""",
      """{"type":"result","is_error":false,"result":"Hi"}""",
    )
    assertEquals(
      listOf(ChatEvent.TextDelta("Hi"), ChatEvent.ToolUse("Read"), ChatEvent.Completed),
      lines.flatMap(::decodeClaudeLine),
    )
    assertEquals(
      listOf(ChatEvent.Failed(ChatFailure.ProcessFailed, "boom")),
      decodeClaudeLine("""{"type":"result","is_error":true,"result":"boom"}"""),
    )
    assertEquals(listOf(ChatEvent.Failed(ChatFailure.StreamMalformed, "not json")), decodeClaudeLine("not json"))
  }
}
