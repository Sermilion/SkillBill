package skillbill.infrastructure.launcher.process.codex

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodexAppServerProtocolTest {
  @Test
  fun `auto-review settings route thread and turn approvals to the reviewer and plain settings leave them alone`() {
    val reviewed = CodexThreadSettings("/work", null, null, readOnly = false, autoReview = true)
    val plain = reviewed.copy(autoReview = false)
    val input = listOf(CodexAppServerProtocol.textInput("hi"))

    for (params in listOf(
      CodexAppServerProtocol.threadParams(reviewed, null),
      CodexAppServerProtocol.turnParams(reviewed, "thread-1", input),
    )) {
      assertEquals(JsonPrimitive("on-request"), params["approvalPolicy"])
      assertEquals(JsonPrimitive("auto_review"), params["approvalsReviewer"])
    }
    for (params in listOf(
      CodexAppServerProtocol.threadParams(plain, null),
      CodexAppServerProtocol.turnParams(plain, "thread-1", input),
    )) {
      assertNull(params["approvalPolicy"])
      assertNull(params["approvalsReviewer"])
    }
  }
}
