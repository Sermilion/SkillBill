package skillbill.infrastructure.contracts.workflow.featuretask

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanAdmissionCode
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FeatureTaskRuntimeExecutionPlanRawBoundaryTest {
  private val validator = FeatureTaskRuntimeExecutionPlanSchemaValidator()

  @Test
  fun `raw byte bound rejects whitespace and multibyte payloads before parsing`() {
    val oversized =
      listOf(
        "{}" + " ".repeat(65535),
        "\"" + "é".repeat(32768) + "\"",
        "{" + " ".repeat(65536),
      )
    oversized.forEach { raw ->
      val error =
        assertFailsWith<SkillBillRuntimeException> {
          validator.read(raw.toByteArray(Charsets.UTF_8), "untrusted-source")
        }
      assertEquals(FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_PLAN_SCHEMA, error.code)
      assertEquals(
        "Invalid feature-task runtime execution plan: execution plan exceeds 65536 UTF-8 bytes",
        error.message,
      )
    }
    val boundary =
      assertFailsWith<SkillBillRuntimeException> {
        validator.read(("{}" + " ".repeat(65534)).toByteArray(), "untrusted-source")
      }
    assertEquals(FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_PLAN_SCHEMA, boundary.code)
    assertEquals("Invalid feature-task runtime execution plan: execution plan violates its schema", boundary.message)
  }

  @Test
  fun `raw reader refuses duplicate keys trailing documents wrong roots and invalid encoding`() {
    val key = FeatureTaskRuntimeExecutionPlanKeys.CONTRACT_VERSION
    val malformed =
      listOf(
        "{\"$key\":\"0.1\",\"$key\":\"0.2\"}",
        "{} {}",
        "{",
      )
    malformed.forEach { raw ->
      val error =
        assertFailsWith<SkillBillRuntimeException> {
          validator.read(raw.toByteArray(), "source")
        }
      assertEquals(FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_PLAN_SCHEMA, error.code)
      assertEquals("Invalid feature-task runtime execution plan: malformed or ambiguous JSON", error.message)
    }
    listOf("[]", "null", "true", "\"text\"").forEach { raw ->
      val error =
        assertFailsWith<SkillBillRuntimeException> {
          validator.read(raw.toByteArray(), "source")
        }
      assertEquals(FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_PLAN_SCHEMA, error.code)
      assertEquals("Invalid feature-task runtime execution plan: execution plan must be a JSON object", error.message)
    }
    val invalidEncoding =
      assertFailsWith<SkillBillRuntimeException> {
        validator.read(byteArrayOf(0xc3.toByte(), 0x28), "source")
      }
    assertEquals(FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_PLAN_SCHEMA, invalidEncoding.code)
    assertEquals("Invalid feature-task runtime execution plan: invalid UTF-8 encoding", invalidEncoding.message)
  }

  @Test
  fun `producer and reader distinguish unsupported contract versions without echoing the descriptor`() {
    val payload = mapOf(FeatureTaskRuntimeExecutionPlanKeys.CONTRACT_VERSION to "9.9")
    val encoded = JsonCodec.mapToJsonString(payload).toByteArray(Charsets.UTF_8)
    val readerFailure =
      assertFailsWith<SkillBillRuntimeException> {
        validator.read(encoded, "private-source")
      }
    val writerFailure =
      assertFailsWith<SkillBillRuntimeException> {
        validator.write(payload, "private-source")
      }
    assertEquals(FeatureTaskRuntimeExecutionPlanAdmissionCode.UNSUPPORTED_DESCRIPTOR, readerFailure.code)
    assertEquals(readerFailure.code, writerFailure.code)
    assertFalse(readerFailure.message.orEmpty().contains("9.9"))
    assertFalse(readerFailure.message.orEmpty().contains("private-source"))
  }

  @Test
  fun `producer and reader reject the same invalid schema with payload free diagnostics`() {
    val secret = "private-command-argument"
    val payload = mapOf(FeatureTaskRuntimeExecutionPlanKeys.CONTRACT_VERSION to secret)
    val readError =
      assertFailsWith<SkillBillRuntimeException> {
        validator.read(JsonCodec.mapToJsonString(payload).toByteArray(), secret.repeat(1000))
      }
    val writeError =
      assertFailsWith<SkillBillRuntimeException> {
        validator.write(payload, secret.repeat(1000))
      }
    assertEquals(FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_PLAN_SCHEMA, readError.code)
    assertEquals(readError.code, writeError.code)
    assertEquals(readError.message, writeError.message)
    assertFalse(readError.message.orEmpty().contains(secret))
    val unsupported =
      assertFailsWith<SkillBillRuntimeException> {
        validator.write(mapOf(FeatureTaskRuntimeExecutionPlanKeys.DEFINITION to Any()), "source")
      }
    assertEquals(FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_PLAN_SCHEMA, unsupported.code)
    assertEquals("Invalid feature-task runtime execution plan: unsupported JSON value", unsupported.message)
  }
}
