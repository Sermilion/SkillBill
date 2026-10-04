package skillbill.infrastructure.skills.nativeagent

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionSchemaValidator
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeAgentCompositionSchemaViolationsTest {
  private val validBundleYaml: String =
    """
    agents:
      - name: "bill-agent-one"
        description: "A valid native-agent entry used as the violation-test baseline."
        compose: "governed-content"
    """.trimIndent()

  @Test
  fun `valid bundle yaml passes validation`() {
    NativeAgentCompositionSchemaValidator.validate(validBundleYaml, "valid-baseline")
  }

  @Test
  fun `missing required name fails validation`() {
    val yaml =
      """
      agents:
        - description: "Entry missing the required name."
          compose: "governed-content"
      """.trimIndent()

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        NativeAgentCompositionSchemaValidator.validate(yaml, "missing-name")
      }.also { assertEquals(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, it.code) }
    assertContains(error.message.orEmpty(), "name")
  }

  @Test
  fun `unknown compose reference shape fails validation`() {
    val yaml =
      """
      agents:
        - name: "bill-agent-bogus-compose"
          description: "Entry whose compose value is not a member of the enum."
          compose: "bogus-mode"
      """.trimIndent()

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        NativeAgentCompositionSchemaValidator.validate(yaml, "bogus-compose")
      }.also { assertEquals(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, it.code) }
    assertContains(error.message.orEmpty(), "compose")
  }

  @Test
  fun `unknown top-level property fails validation`() {
    val yaml =
      """
      agents:
        - name: "bill-agent-one"
          description: "A valid native-agent entry."
          compose: "governed-content"
      bogus_extra: true
      """.trimIndent()

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        NativeAgentCompositionSchemaValidator.validate(yaml, "unknown-top-level")
      }.also { assertEquals(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, it.code) }
    assertContains(error.message.orEmpty(), "bogus_extra")
  }

  @Test
  fun `wrong contract_version value fails validation`() {
    val yaml =
      """
      contract_version: "9.99-bogus"
      agents:
        - name: "bill-agent-one"
          description: "A valid native-agent entry."
          compose: "governed-content"
      """.trimIndent()

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        NativeAgentCompositionSchemaValidator.validate(yaml, "wrong-contract-version")
      }.also { assertEquals(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, it.code) }
    assertContains(error.message.orEmpty(), "contract_version")
  }

  @Test
  fun `malformed body wrong type fails validation`() {
    val yaml =
      """
      agents:
        - name: "bill-agent-one"
          description: "Entry with a malformed body (mapping instead of string)."
          body:
            unexpected_nested: "value"
      """.trimIndent()

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        NativeAgentCompositionSchemaValidator.validate(yaml, "malformed-body")
      }.also { assertEquals(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, it.code) }
    assertContains(error.message.orEmpty(), "body")
  }
}
