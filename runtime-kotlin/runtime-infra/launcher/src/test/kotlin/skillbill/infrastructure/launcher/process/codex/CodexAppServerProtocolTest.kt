package skillbill.infrastructure.launcher.process.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class CodexAppServerProtocolTest {
  @Test
  fun `new resumed and running threads explicitly bypass approvals and sandbox restrictions`() {
    val settings = CodexThreadSettings("/work", null, null)
    val openings =
      listOf(
        CodexAppServerProtocol.threadParams(settings, null),
        CodexAppServerProtocol.threadParams(settings, "saved-restricted-thread"),
      )
    for (params in openings) {
      assertEquals(JsonPrimitive("never"), params["approvalPolicy"])
      assertEquals(JsonPrimitive("danger-full-access"), params["sandbox"])
    }
    val turn = CodexAppServerProtocol.turnParams(settings, "thread", listOf(CodexAppServerProtocol.textInput("hi")))
    assertEquals(JsonPrimitive("never"), turn["approvalPolicy"])
    assertEquals(JsonPrimitive("dangerFullAccess"), turn["sandboxPolicy"]?.jsonObject?.get("type"))
    assertFalse("writableRoots" in turn.getValue("sandboxPolicy").jsonObject)
  }

  @Test
  fun `steering targets the active turn and accepts only its returned identity`() {
    val inputs =
      listOf(
        CodexAppServerProtocol.textInput("continue with the test"),
        CodexAppServerProtocol.localImageInput("/tmp/chat-image.png"),
      )
    val request = CodexAppServerProtocol.steerRequest(8, "thread-1", "turn-2", inputs)
    val params = request.getValue("params").jsonObject

    assertEquals(JsonPrimitive("turn/steer"), request["method"])
    assertEquals(JsonPrimitive("thread-1"), params["threadId"])
    assertEquals(JsonPrimitive("turn-2"), params["expectedTurnId"])
    assertEquals(inputs, params["input"]?.jsonArray?.map { it.jsonObject })
    assertFalse("model" in params)
    assertFalse("cwd" in params)
    assertFalse("sandboxPolicy" in params)
    assertEquals(
      "turn-2",
      CodexAppServerProtocol.steerTurnId(buildJsonObject { put("turnId", "turn-2") }, "turn-2"),
    )
    assertNull(CodexAppServerProtocol.steerTurnId(buildJsonObject { put("turnId", "turn-other") }, "turn-2"))
  }

  @Test
  fun `only a well formed steering error authorizes fallback`() {
    val rejection = Json.parseToJsonElement("""{"error":{"code":-32602,"message":"turn busy"}}""").jsonObject
    assertIs<CodexSteerResponse.Rejected>(CodexAppServerProtocol.steerResponse(rejection, "turn-2"))
    val malformedResponses =
      listOf(
        """{"error":{}}""",
        """{"error":{"code":"rejected","message":"turn busy"}}""",
        """{"error":{"code":-32602}}""",
        """{"error":{"code":-32602,"message":"turn busy"},"result":{"turnId":"turn-2"}}""",
      )
    malformedResponses.forEach { source ->
      val message = Json.parseToJsonElement(source).jsonObject
      assertIs<CodexSteerResponse.Uncertain>(CodexAppServerProtocol.steerResponse(message, "turn-2"))
    }
  }
}
