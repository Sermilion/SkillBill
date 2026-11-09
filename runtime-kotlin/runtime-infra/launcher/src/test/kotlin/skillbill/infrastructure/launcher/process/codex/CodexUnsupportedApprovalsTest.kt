package skillbill.infrastructure.launcher.process.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodexUnsupportedApprovalsTest {
  @Test
  fun `an unsupported request without an operator channel is accepted and does not stop`() {
    val frames = mutableListOf<JsonObject>()
    var stopped: String? = null
    val approvals =
      CodexApprovalRequests(
        "/checkout",
        {
          frames += it
          true
        },
        {},
        { stopped = it },
        {},
      )
    approvals.bindTurn("thread", "turn")
    approvals.request(
      Json.parseToJsonElement(
        """{"id":7,"method":"mcpServer/elicitation/request",""" +
          """"params":{"threadId":"thread","turnId":"turn"}}""",
      ).jsonObject,
    )

    assertNull(stopped)
    assertEquals("accept", frames.single()["result"]?.jsonObject?.get("action")?.jsonPrimitive?.content)
  }
}
