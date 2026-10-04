package skillbill.infrastructure.skills.agentaddon

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.AgentAddonFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentAddonSchemaValidatorTest {
  private val validator = AgentAddonSchemaValidator()

  @Test
  fun `canonical manifest shape is accepted`() {
    validator.validate(validManifest(), "fixture")
  }

  @Test
  fun `wrong version extra fields duplicate entries and malformed descriptions are rejected`() {
    listOf(
      validManifest() + ("contract_version" to "2.0"),
      validManifest() + ("unexpected" to true),
      validManifest() + ("agent_ids" to listOf("codex", "codex")),
      validManifest() + ("description" to ""),
      validManifest() + ("description" to "x".repeat(201)),
    ).forEach { manifest ->
      val error = assertFailsWith<SkillBillRuntimeException> { validator.validate(manifest, "fixture") }
      assertEquals(AgentAddonFailureCode.INVALID_SCHEMA, error.code)
    }
  }

  @Test
  fun `missing canonical schema resource is a typed failure`() {
    val validator = AgentAddonSchemaValidator(AgentAddonSchemaResourceLoader { error("missing") })
    val error = assertFailsWith<SkillBillRuntimeException> { validator.validate(validManifest(), "fixture") }
    assertEquals(AgentAddonFailureCode.INVALID_SCHEMA, error.code)
  }

  private fun validManifest(): Map<String, Any?> =
    mapOf(
      "contract_version" to "1.0",
      "slug" to "review-helper",
      "description" to "Provides review guidance.",
      "agent_ids" to listOf("codex"),
      "consumers" to listOf("skill-bill"),
    )
}
