package skillbill.infrastructure.launcher.process.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CodexSchemaAdmissionTest {
  @Test
  fun `pinned steering schemas admit the requests emitted by the adapter`() {
    assertEquals(CodexSteeringAdmission.Available, CodexSchemaAdmission.inspectPinnedSteering())
    assertEquals(
      CodexSteeringAdmission.Available,
      CodexSchemaAdmission.inspectSteering(params(), response(), clientRequest()),
    )
  }

  @Test
  fun `missing steering schemas or method binding leave steering unavailable`() {
    assertIs<CodexSteeringAdmission.Unavailable>(CodexSchemaAdmission.inspectSteering(null, null, null))
    val unbound = JsonObject(clientRequest() + (CodexRpcKeys.SCHEMA_ONE_OF to JsonArray(emptyList())))
    assertIs<CodexSteeringAdmission.Unavailable>(
      CodexSchemaAdmission.inspectSteering(params(), response(), unbound),
    )
  }

  @Test
  fun `a response that cannot confirm a string turn identity leaves steering unavailable`() {
    val turnId = JsonObject(mapOf(CodexRpcKeys.TYPE to JsonPrimitive("integer")))
    val properties = JsonObject(mapOf(CodexRpcKeys.TURN_ID to turnId))
    val incompatible = JsonObject(response() + (CodexRpcKeys.SCHEMA_PROPERTIES to properties))
    assertIs<CodexSteeringAdmission.Unavailable>(
      CodexSchemaAdmission.inspectSteering(params(), incompatible, clientRequest()),
    )
  }

  private fun params() = schema("v2/TurnSteerParams.json")

  private fun response() = schema("v2/TurnSteerResponse.json")

  private fun clientRequest() = schema("ClientRequest.json")

  private fun schema(name: String): JsonObject =
    requireNotNull(javaClass.getResourceAsStream("schema/$CODEX_PROTOCOL_VERSION/$name")).use {
      Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
    }
}
