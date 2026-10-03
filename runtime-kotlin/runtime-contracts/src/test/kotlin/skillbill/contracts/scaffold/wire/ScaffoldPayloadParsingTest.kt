package skillbill.contracts.scaffold.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ScaffoldFailureCode

class ScaffoldPayloadParsingTest {
  @Test
  fun `present wrong type description fails before defaulting to empty`() {
    assertFailsWith<SkillBillRuntimeException> {
      requireStringOrDefault(mapOf("description" to 123), "description", "")
    }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
  }

  @Test
  fun `present wrong type optional string fails before treating as absent`() {
    assertFailsWith<SkillBillRuntimeException> {
      optionalString(mapOf("content_body" to 123), "content_body")
    }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
  }

  @Test
  fun `omitted null and blank optional strings retain their defaults`() {
    assertEquals("fallback", requireStringOrDefault(emptyMap(), "description", "fallback"))
    assertEquals("fallback", requireStringOrDefault(mapOf("description" to null), "description", "fallback"))
    assertEquals("fallback", requireStringOrDefault(mapOf("description" to " "), "description", "fallback"))
    assertEquals(null, optionalString(emptyMap(), "content_body"))
    assertEquals(null, optionalString(mapOf("content_body" to null), "content_body"))
    assertEquals(null, optionalString(mapOf("content_body" to " "), "content_body"))
  }
}
