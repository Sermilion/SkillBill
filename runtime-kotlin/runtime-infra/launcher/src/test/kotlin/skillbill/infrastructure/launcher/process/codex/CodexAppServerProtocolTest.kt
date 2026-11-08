package skillbill.infrastructure.launcher.process.codex

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
