package skillbill.infrastructure.launcher.process.interaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeHostFramesTest {
  @Test
  fun `allow answers with the original input and no remembered permissions`() {
    val input = buildJsonObject { put("command", "git status") }

    val frame = decode(ClaudeHostFrames.allow(JsonPrimitive("req-1"), input))

    assertEquals("control_response", frame["type"]?.jsonPrimitive?.content)
    val response = frame.getValue("response").jsonObject
    assertEquals("success", response["subtype"]?.jsonPrimitive?.content)
    assertEquals("req-1", response["request_id"]?.jsonPrimitive?.content)
    val body = response.getValue("response").jsonObject
    assertEquals("allow", body["behavior"]?.jsonPrimitive?.content)
    assertEquals(input, body["updatedInput"])
    assertFalse("updatedPermissions" in body)
  }

  @Test
  fun `deny and unsupported keep an integer request id unquoted`() {
    val requestId = JsonPrimitive(42)

    val denied = decode(ClaudeHostFrames.deny(requestId, "No.")).getValue("response").jsonObject
    val unsupported = decode(ClaudeHostFrames.unsupported(requestId, "Unsupported.")).getValue("response").jsonObject

    assertEquals(requestId, denied["request_id"])
    assertEquals("deny", denied.getValue("response").jsonObject["behavior"]?.jsonPrimitive?.content)
    assertEquals(requestId, unsupported["request_id"])
    assertEquals("error", unsupported["subtype"]?.jsonPrimitive?.content)
    assertEquals("Unsupported.", unsupported["error"]?.jsonPrimitive?.content)
  }

  @Test
  fun `original input is restored only from a complete control request frame`() {
    val stored = """{"type":"control_request","request":{"subtype":"can_use_tool","input":{"file_path":"a.kt"}}}"""

    assertEquals(buildJsonObject { put("file_path", "a.kt") }, originalInput(stored))
    assertNull(originalInput("""{"type":"control_request","request":{"subtype":"can_use_tool"}}"""))
    assertNull(originalInput("not json"))
  }

  @Test
  fun `the line splitter drops one over-long line whole and keeps the following line`() {
    val splitter = JsonLineSplitter(maxLineChars = 8)

    assertEquals(listOf("short"), splitter.feed("short\n"))
    assertTrue(splitter.feed("far-too-long").isEmpty())
    assertEquals(listOf("next"), splitter.feed("-still\nnext\n"))
    assertTrue(splitter.feed("part").isEmpty())
    assertEquals(listOf("partial"), splitter.feed("ial\n"))
  }

  private fun decode(bytes: ByteArray): JsonObject {
    val text = bytes.toString(Charsets.UTF_8)
    assertTrue(text.endsWith("\n"))
    return Json.parseToJsonElement(text).jsonObject
  }
}
