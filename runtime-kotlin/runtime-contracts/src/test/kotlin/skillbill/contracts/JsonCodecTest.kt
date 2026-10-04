package skillbill.contracts

import skillbill.error.core.JsonFailureCode
import skillbill.error.core.SkillBillRuntimeException
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JsonCodecTest {
  @Test
  fun `strict array parse accepts whitespace formatted empty arrays`() {
    assertEquals(emptyList(), JsonCodec.parseJsonArrayStrict("[]"))
    assertEquals(emptyList(), JsonCodec.parseJsonArrayStrict("[ ]"))
  }

  @Test
  fun `strict array parse rejects malformed text and wrong roots`() {
    assertEquals(
      JsonFailureCode.MALFORMED_TEXT,
      assertFailsWith<SkillBillRuntimeException> { JsonCodec.parseJsonArrayStrict("{") }.code,
    )
    assertEquals(
      JsonFailureCode.WRONG_ROOT_TYPE,
      assertFailsWith<SkillBillRuntimeException> { JsonCodec.parseJsonArrayStrict("{}") }.code,
    )
  }

  @Test
  fun `BigInteger encoding preserves integers above double precision`() {
    val value = BigInteger("9007199254740993")
    val encoded = JsonCodec.valueToJsonString(value)
    assertEquals("9007199254740993", encoded)
    assertEquals(value.toLong(), JsonCodec.parseValue(encoded))
  }

  @Test
  fun `decoding an integer above Long preserves the exact BigInteger`() {
    val value = BigInteger("9223372036854775808")

    assertEquals(value, JsonCodec.parseValue(value.toString()))
  }

  @Test
  fun `BigDecimal encoding avoids double rounding`() {
    val value = BigDecimal("1.234567890123456789")
    val encoded = JsonCodec.valueToJsonString(value)
    assertEquals("1.234567890123456789", encoded)
    assertEquals(value, JsonCodec.parseValue(encoded))
  }

  @Test
  fun `mixed key maps fail instead of dropping entries`() {
    val map: Map<*, *> = mapOf("ok" to 1, 2 to 3)
    assertEquals(
      JsonFailureCode.UNSUPPORTED_VALUE,
      assertFailsWith<SkillBillRuntimeException> {
        JsonCodec.valueToJsonString(map)
      }.code,
    )
    assertEquals(
      JsonFailureCode.UNSUPPORTED_VALUE,
      assertFailsWith<SkillBillRuntimeException> {
        JsonCodec.anyToStringAnyMap(map)
      }.code,
    )
  }

  @Test
  fun `unsupported value types fail instead of using toString`() {
    assertEquals(
      JsonFailureCode.UNSUPPORTED_VALUE,
      assertFailsWith<SkillBillRuntimeException> {
        JsonCodec.valueToJsonString(object {})
      }.code,
    )
  }

  @Test
  fun `non-finite floating point values fail instead of entering JSON`() {
    assertEquals(
      JsonFailureCode.UNSUPPORTED_VALUE,
      assertFailsWith<SkillBillRuntimeException> {
        JsonCodec.valueToJsonString(Double.NaN)
      }.code,
    )
    assertEquals(
      JsonFailureCode.UNSUPPORTED_VALUE,
      assertFailsWith<SkillBillRuntimeException> {
        JsonCodec.valueToJsonString(Float.POSITIVE_INFINITY)
      }.code,
    )
  }

  @Test
  fun `json strings that start with numbers remain strings`() {
    val parsed =
      requireNotNull(
        JsonCodec.parseObjectOrNull("""{"decisions":["1 fix","2 reject"],"count":2}"""),
      )
    val decoded = requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(parsed)))

    assertEquals(listOf("1 fix", "2 reject"), decoded["decisions"])
    assertEquals(2, decoded["count"])
  }

  @Test
  fun `parseObjectOrNull returns null for malformed text and for well-formed non-objects`() {
    assertNull(
      JsonCodec.parseObjectOrNull("{not json"),
      "Malformed JSON must surface as null rather than escaping as a serialization exception.",
    )
    assertNull(
      JsonCodec.parseObjectOrNull("[1]"),
      "A well-formed JSON array is not a payload object; callers branch on null instead of class-casting.",
    )
    assertNull(
      JsonCodec.parseObjectOrNull("42"),
      "A well-formed JSON scalar is not a payload object; callers branch on null instead of class-casting.",
    )
  }
}
