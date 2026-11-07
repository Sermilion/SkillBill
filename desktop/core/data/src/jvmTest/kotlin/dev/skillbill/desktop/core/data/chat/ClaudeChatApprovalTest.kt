package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaudeChatApprovalTest {
  private val diagnostics = RecordingDiagnostics()

  // An unanswered control request leaves the provider waiting, so a failed answer must end the process.
  @Test
  fun `an unsupported control request that cannot be answered stops the process`() {
    var stops = 0
    val approval = approval(writeLine = { false }, stopProcess = { stops++ })

    approval.handle(parse(UNSUPPORTED_REQUEST), UNSUPPORTED_REQUEST)

    assertEquals(1, stops)
    assertEquals(listOf("chat.approval_channel_stopped"), diagnostics.categories)
  }

  @Test
  fun `an unsupported control request is answered with an error and keeps the process`() {
    val written = mutableListOf<String>()
    var stops = 0
    val approval = approval(writeLine = { written += it; true }, stopProcess = { stops++ })

    approval.handle(parse(UNSUPPORTED_REQUEST), UNSUPPORTED_REQUEST)

    assertEquals(0, stops)
    val response = parse(written.single()).getValue("response").jsonObject
    assertEquals("error", response["subtype"]?.jsonPrimitive?.content)
    assertEquals("req-9", response["request_id"]?.jsonPrimitive?.content)
  }

  // Without a bound owner generation no card can answer, so the request is denied rather than left pending.
  @Test
  fun `a permission request without an owner generation is denied`() {
    val written = mutableListOf<String>()
    val approval = approval(writeLine = { written += it; true }, stopProcess = {})
    val line =
      """{"type":"control_request","request_id":"req-1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"}}}"""

    approval.handle(parse(line), line)

    val body = parse(written.single()).getValue("response").jsonObject.getValue("response").jsonObject
    assertEquals("deny", body["behavior"]?.jsonPrimitive?.content)
    assertTrue(body["message"]?.jsonPrimitive?.content.orEmpty().startsWith("Operator approval is unavailable"))
  }

  private fun approval(writeLine: (String) -> Boolean, stopProcess: () -> Unit) =
    ClaudeChatApproval("/work/app", writeLine, stopProcess, diagnostics, onCard = {})

  private fun parse(line: String) = Json.parseToJsonElement(line).jsonObject

  private companion object {
    const val UNSUPPORTED_REQUEST =
      """{"type":"control_request","request_id":"req-9","request":{"subtype":"hook_callback"}}"""
  }
}
