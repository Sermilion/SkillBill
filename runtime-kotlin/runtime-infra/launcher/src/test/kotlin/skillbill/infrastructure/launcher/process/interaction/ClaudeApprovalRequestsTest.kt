package skillbill.infrastructure.launcher.process.interaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import skillbill.contracts.approval.ApprovalState
import skillbill.infrastructure.launcher.process.ApprovalTestOwner
import skillbill.ports.approval.model.ApprovalDeliveryState
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeApprovalRequestsTest {
  @Test
  fun `owned commands wait for operator decisions and registration failure never allows`() {
    for (state in listOf(ApprovalState.ALLOWED, ApprovalState.DENIED, ApprovalState.EXPIRED, null)) {
      val frames = mutableListOf<JsonObject>()
      val owner = ApprovalTestOwner().apply { failRegistration = state == null }
      val requests =
        ClaudeApprovalRequests(
          "/checkout",
          {
            frames += it
            true
          },
          {},
          { error(it) },
          {},
          clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        )
      requests.bind(owner)
      val line =
        """{"request_id":"r1","request":{"subtype":"can_use_tool",""" +
          """"tool_name":"Bash","input":{"command":"git status"}}}"""
      requests.handle(Json.parseToJsonElement(line).jsonObject, line)
      if (state != null) {
        assertTrue(frames.isEmpty())
        assertEquals(
          ApprovalDeliveryState.WRITTEN,
          requests.deliver(owner.requests.single().request.copy(state = state)),
        )
      }
      val body = frames.single()["response"]?.jsonObject?.get("response")?.jsonObject
      assertEquals(
        if (state == ApprovalState.ALLOWED) "allow" else "deny",
        body?.get("behavior")?.jsonPrimitive?.content,
      )
    }
  }

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
