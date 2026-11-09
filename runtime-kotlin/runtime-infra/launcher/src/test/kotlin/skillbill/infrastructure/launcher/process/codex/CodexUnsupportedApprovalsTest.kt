package skillbill.infrastructure.launcher.process.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import skillbill.contracts.approval.ApprovalState
import skillbill.infrastructure.launcher.process.ApprovalTestOwner
import skillbill.ports.approval.model.ApprovalDeliveryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexUnsupportedApprovalsTest {
  @Test
  fun `bypass handles ordinary and extended permissions without an operator or stopping`() {
    val cases =
      listOf(
        "item/commandExecution/requestApproval" to """{"decision":"accept"}""",
        "item/fileChange/requestApproval" to """{"decision":"accept"}""",
        "item/permissions/requestApproval" to """{"permissions":{"network":{"enabled":true}},"scope":"turn"}""",
        "mcpServer/elicitation/request" to """{"action":"cancel"}""",
        "item/tool/requestUserInput" to """{"answers":{}}""",
      )
    for ((method, expected) in cases) {
      val frames = mutableListOf<JsonObject>()
      val approvals =
        CodexApprovalRequests("/checkout", {
          frames += it
          true
        }, {}, { error(it) }, {})
      approvals.bindTurn("thread", "turn")
      approvals.request(message(method))
      assertEquals(Json.parseToJsonElement(expected), frames.single()["result"])
    }
  }

  @Test
  fun `unknown bypass extensions return a protocol error without stopping the worker`() {
    val frames = mutableListOf<JsonObject>()
    val approvals =
      CodexApprovalRequests("/checkout", {
        frames += it
        true
      }, {}, { error(it) }, {})
    approvals.bindTurn("thread", "turn")
    approvals.request(message("future/request"))
    assertEquals(Json.parseToJsonElement("-32601"), frames.single()["error"]?.jsonObject?.get("code"))
    assertNull(approvals.pendingWait())
  }

  @Test
  fun `owned unsupported requests are never preapproved and terminal delivery is not duplicated`() {
    for (writeSucceeds in listOf(true, false)) {
      val frames = mutableListOf<JsonObject>()
      val owner = ApprovalTestOwner()
      val approvals =
        CodexApprovalRequests("/checkout", {
          frames += it
          writeSucceeds
        }, {}, { error(it) }, {})
      approvals.bind(owner)
      approvals.bindTurn("thread", "turn")
      owner.onResolve = { settled ->
        assertTrue(frames.isEmpty())
        val expected = if (writeSucceeds) ApprovalDeliveryState.WRITTEN else ApprovalDeliveryState.UNCERTAIN
        assertEquals(expected, approvals.deliver(settled))
        assertEquals(
          if (writeSucceeds) ApprovalDeliveryState.NOT_REQUIRED else ApprovalDeliveryState.UNCERTAIN,
          approvals.deliver(settled),
        )
      }
      approvals.request(message("mcpServer/elicitation/request"))
      assertEquals(Json.parseToJsonElement("""{"action":"cancel"}"""), frames.single()["result"])
    }
  }

  @Test
  fun `stale owned interactions are answered without granting or registering them`() {
    val frames = mutableListOf<JsonObject>()
    val owner = ApprovalTestOwner()
    val approvals =
      CodexApprovalRequests("/checkout", {
        frames += it
        true
      }, {}, { error(it) }, {})
    approvals.bind(owner)
    approvals.bindTurn("thread", "new-turn")
    approvals.request(message("mcpServer/elicitation/request"))
    assertTrue(owner.requests.isEmpty())
    assertEquals(Json.parseToJsonElement("""{"action":"cancel"}"""), frames.single()["result"])
  }

  @Test
  fun `ordinary owned commands wait for allow deny or expiry`() {
    for ((state, expected) in listOf(
      ApprovalState.ALLOWED to "accept",
      ApprovalState.DENIED to "decline",
      ApprovalState.EXPIRED to "cancel",
    )) {
      val frames = mutableListOf<JsonObject>()
      val owner = ApprovalTestOwner()
      val approvals =
        CodexApprovalRequests("/checkout", {
          frames += it
          true
        }, {}, { error(it) }, {})
      approvals.bind(owner)
      approvals.bindTurn("thread", "turn")
      approvals.request(message("item/commandExecution/requestApproval"))
      assertTrue(frames.isEmpty())
      val request = owner.requests.single().request
      assertEquals(ApprovalDeliveryState.WRITTEN, approvals.deliver(request.copy(state = state)))
      assertEquals(Json.parseToJsonElement("""{"decision":"$expected"}"""), frames.single()["result"])
    }
  }

  private fun message(method: String): JsonObject =
    Json.parseToJsonElement(
      """{"id":7,"method":"$method","params":{"threadId":"thread","turnId":"turn","itemId":"item",
"command":"git status","cwd":"/checkout","permissions":{"network":{"enabled":true}}}}""",
    ).jsonObject
}
