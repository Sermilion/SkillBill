package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CodexSchemaAdmissionTest {
  @Test
  fun `required initialize response fields do not become outgoing request obligations`() {
    val response = schema("""{"properties":{"userAgent":{"type":"string"}},"required":["userAgent"]}""")

    assertNull(validate("v1/InitializeResponse.json", "userAgent", response))
    assertNotNull(validate("v1/InitializeResponse.json", "userAgent", schema(
      """{"properties":{"userAgent":{"type":"number"}},"required":["userAgent"]}""",
    )))
    assertNotNull(validate("v1/InitializeResponse.json", "userAgent", schema(
      """{"properties":{"userAgent":{"type":"string"}},"required":[]}""",
    )))
  }

  @Test
  fun `thread policy objects admit act and read only modes without confusing request enum tokens`() {
    val response = schema(
      """{"properties":{"thread":{"type":"object"},"cwd":{"type":"string"},"model":{"type":"string"},
        "sandbox":{"oneOf":[{"type":"object","properties":{"type":{"enum":["readOnly"]}},"required":["type"]}]}},
        "required":["thread","cwd","model","sandbox"]}""",
    )
    val fields = setOf("thread", "cwd", "model", "sandbox")
    for (mode in listOf(ChatPermissionMode.Act, ChatPermissionMode.ReadOnly)) {
      assertNull(codexProjectionCompatibility(
        "v2/ThreadStartResponse.json", fields, response, JsonObject(emptyMap()), chatRequest(mode = mode),
      ))
    }
    val writableResponse = schema(response.toString().replace("readOnly", "workspaceWrite"))
    assertNotNull(codexProjectionCompatibility(
      "v2/ThreadStartResponse.json", fields, writableResponse, JsonObject(emptyMap()),
      chatRequest(mode = ChatPermissionMode.ReadOnly),
    ))
  }

  @Test
  fun `unknown mandatory request fields still refuse admission`() {
    val params = schema(
      """{"properties":{"clientInfo":{"type":"object"}},"required":["clientInfo","newMandatoryField"]}""",
    )

    assertNotNull(validate("v1/InitializeParams.json", "clientInfo", params))
  }

  private fun validate(file: String, field: String, projection: JsonObject): String? = codexProjectionCompatibility(
    file, setOf(field), projection, JsonObject(emptyMap()), chatRequest(),
  )

  private fun schema(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
}
