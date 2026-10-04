package skillbill.scaffold.policy

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ScaffoldFailureCode
import skillbill.scaffold.model.SkillKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScaffoldPayloadPolicyTest {
  @Test
  fun `skill kind wire values round trip and reject unknown values`() {
    SkillKind.entries.forEach { kind ->
      assertEquals(kind, SkillKind.fromWire(kind.wireValue))
    }
    assertFailsWith<SkillBillRuntimeException> {
      SkillKind.fromWire("not-a-skill-kind")
    }.also { assertEquals(ScaffoldFailureCode.UNKNOWN_SKILL_KIND, it.code) }
  }

  @Test
  fun `active creation kinds exclude retired partial scaffold kinds`() {
    assertEquals(
      setOf(SKILL_KIND_HORIZONTAL, SKILL_KIND_PLATFORM_PACK, SKILL_KIND_ADD_ON, SKILL_KIND_AGENT_ADDON),
      ACTIVE_CREATION_SKILL_KINDS,
    )
    assertEquals(true, SKILL_KIND_PLATFORM_OVERRIDE_PILOTED !in ACTIVE_CREATION_SKILL_KINDS)
    assertEquals(true, SKILL_KIND_CODE_REVIEW_AREA !in ACTIVE_CREATION_SKILL_KINDS)
  }

  @Test
  fun `retired partial scaffold kind error recommends full pack or edit remove`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        rejectRetiredPartialScaffoldKind(SKILL_KIND_CODE_REVIEW_AREA)
      }.also { assertEquals(ScaffoldFailureCode.RETIRED_KIND, it.code) }
    val message = error.message.orEmpty()
    assertEquals(true, message.contains("platform-pack"))
    assertEquals(true, message.contains("edit/remove existing platform-pack content"))
  }

  @Test
  fun `parseBaselineLayerPayload accepts a well-formed object`() {
    val raw =
      mapOf(
        "platform" to "kmp",
        "skill" to "bill-kmp-code-review",
        "scope" to "same-review-scope",
        "required" to true,
        "mode" to "kmp-baseline",
      )

    val layer = parseBaselineLayerPayload(0, raw)

    assertEquals("kmp", layer.platform)
    assertEquals("bill-kmp-code-review", layer.skill)
    assertEquals(true, layer.required)
    assertEquals("same-review-scope", layer.scope.wireValue)
    assertEquals("kmp-baseline", layer.mode.wireValue)
  }

  @Test
  fun `parseBaselineLayerPayload throws when raw is not an object`() {
    assertFailsWith<SkillBillRuntimeException> {
      parseBaselineLayerPayload(2, "not-an-object")
    }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
  }

  @Test
  fun `parseBaselineLayerPayload throws when scope wire value is unsupported`() {
    val raw =
      mapOf(
        "platform" to "kmp",
        "skill" to "bill-kmp-code-review",
        "scope" to "bogus-scope",
        "required" to true,
        "mode" to "kmp-baseline",
      )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseBaselineLayerPayload(0, raw)
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    val message = error.message
    requireNotNull(message)
    assertEquals(true, message.contains("bogus-scope"))
    assertEquals(true, message.contains("baseline_layers[0].scope"))
  }

  @Test
  fun `parseBaselineLayerPayload throws when required is missing`() {
    val raw =
      mapOf(
        "platform" to "kmp",
        "skill" to "bill-kmp-code-review",
        "scope" to "same-review-scope",
        "mode" to "kmp-baseline",
      )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseBaselineLayerPayload(1, raw)
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    val message = error.message
    requireNotNull(message)
    assertEquals(true, message.contains("baseline_layers[1].required"))
  }

  @Test
  fun `parseBaselineLayerPayload throws when platform or skill is blank`() {
    val blankPlatform =
      mapOf(
        "platform" to "",
        "skill" to "bill-kmp-code-review",
        "scope" to "same-review-scope",
        "required" to true,
        "mode" to "kmp-baseline",
      )
    val blankPlatformError =
      assertFailsWith<SkillBillRuntimeException> {
        parseBaselineLayerPayload(0, blankPlatform)
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    val blankPlatformMessage = blankPlatformError.message
    requireNotNull(blankPlatformMessage)
    assertEquals(true, blankPlatformMessage.contains("baseline_layers[0].platform"))

    val blankSkill =
      mapOf(
        "platform" to "kmp",
        "skill" to "   ",
        "scope" to "same-review-scope",
        "required" to true,
        "mode" to "kmp-baseline",
      )
    val blankSkillError =
      assertFailsWith<SkillBillRuntimeException> {
        parseBaselineLayerPayload(2, blankSkill)
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    val blankSkillMessage = blankSkillError.message
    requireNotNull(blankSkillMessage)
    assertEquals(true, blankSkillMessage.contains("baseline_layers[2].skill"))
  }
}
