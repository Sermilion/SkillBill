package skillbill.infrastructure.launcher.process.interaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaudeApprovalRequestsTest {
  @Test
  fun `AskUserQuestion is allowed when no operator channel exists`() {
    val frames = mutableListOf<JsonObject>()
    var stopped: String? = null
    val requests =
      ClaudeApprovalRequests(
        "/checkout",
        {
          frames += it
          true
        },
        {},
        { stopped = it },
        {},
        clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
      )
    val line =
      """{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool",""" +
        """"tool_name":"AskUserQuestion","input":{"questions":[]}}}"""
    requests.handle(Json.parseToJsonElement(line).jsonObject, line)

    assertNull(stopped)
    val body =
      frames.single()["response"]?.jsonObject?.get("response")?.jsonObject
        ?: error("missing allow body")
    assertEquals("allow", body["behavior"]?.jsonPrimitive?.content)
  }
}
