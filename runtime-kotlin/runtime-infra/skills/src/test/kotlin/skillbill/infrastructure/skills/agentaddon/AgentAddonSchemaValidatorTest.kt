package skillbill.infrastructure.skills.agentaddon

import skillbill.error.shellcontent.InvalidAgentAddonSchemaError
import kotlin.test.Test
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
      assertFailsWith<InvalidAgentAddonSchemaError> { validator.validate(manifest, "fixture") }
    }
  }

  @Test
  fun `missing canonical schema resource is a typed failure`() {
    val validator = AgentAddonSchemaValidator(AgentAddonSchemaResourceLoader { error("missing") })
    assertFailsWith<InvalidAgentAddonSchemaError> { validator.validate(validManifest(), "fixture") }
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
