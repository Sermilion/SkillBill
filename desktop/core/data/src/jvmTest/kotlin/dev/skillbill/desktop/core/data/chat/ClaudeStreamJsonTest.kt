package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeStreamJsonTest {
  // A dropped or duplicated line loses agent output or hides an error from the operator.
  @Test
  fun `stream lines decode to events in order without repeating text`() {
    val lines = listOf(
      """{"type":"system","subtype":"init"}""",
      """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hi"}}}""",
      """{"type":"assistant","message":{"content":[{"type":"text","text":"Hi"},{"type":"tool_use","id":"tool-1","name":"Read","input":{}}]}}""",
      """{"type":"result","is_error":false,"result":"Hi"}""",
    )
    assertEquals(
      listOf(ChatEvent.TextDelta("Hi"), ChatEvent.ToolUse(ChatToolCall("tool-1", "Read", "{}", null)), ChatEvent.Completed),
      lines.flatMap(::decodeClaudeLine),
    )
    assertEquals(
      listOf(ChatEvent.Failed(ChatFailure.ProcessFailed, "boom")),
      decodeClaudeLine("""{"type":"result","is_error":true,"result":"boom"}"""),
    )
    assertEquals(listOf(ChatEvent.Failed(ChatFailure.StreamMalformed, "not json")), decodeClaudeLine("not json"))
  }

  @Test
  fun `shell calls retain their command and match multiline results by tool id`() {
    val call = decodeClaudeLine(
      """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"bash-1","name":"Bash","input":{"command":"git status --short","description":"Check branch changes"}}]}}""",
    ).single() as ChatEvent.ToolUse
    assertEquals("bash-1", call.call.id)
    assertEquals("Check branch changes", call.call.summary)
    assertEquals("git status --short", Json.parseToJsonElement(call.call.input).jsonObject["command"]?.jsonPrimitive?.content)
    assertEquals(
      listOf(ChatEvent.ToolResult("bash-1", "fatal: not a git repository\nTry the worktree.", true)),
      decodeClaudeLine(
        """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"bash-1","is_error":true,"content":[{"type":"text","text":"fatal: not a git repository"},{"type":"text","text":"Try the worktree."}]}]}}""",
      ),
    )
    assertEquals(
      listOf(ChatEvent.ToolResult("bash-1", "clean", false)),
      decodeClaudeLine("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"bash-1","content":"clean"}]}}"""),
    )
  }
}
