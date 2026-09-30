package skillbill.infrastructure.contracts.workflow.featuretask

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeExecutionPlanSchemaError
import skillbill.error.featuretask.UnsupportedFeatureTaskRuntimeExecutionPlanError
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
        assertFailsWith<InvalidFeatureTaskRuntimeExecutionPlanSchemaError> {
          validator.read(raw.toByteArray(Charsets.UTF_8), "untrusted-source")
        }
      assertEquals("execution plan exceeds 65536 UTF-8 bytes", error.reason)
    }
    val boundary =
      assertFailsWith<InvalidFeatureTaskRuntimeExecutionPlanSchemaError> {
        validator.read(("{}" + " ".repeat(65534)).toByteArray(), "untrusted-source")
      }
    assertEquals("execution plan violates its schema", boundary.reason)
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
        assertFailsWith<InvalidFeatureTaskRuntimeExecutionPlanSchemaError> {
          validator.read(raw.toByteArray(), "source")
        }
      assertEquals("malformed or ambiguous JSON", error.reason)
    }
    listOf("[]", "null", "true", "\"text\"").forEach { raw ->
      val error =
        assertFailsWith<InvalidFeatureTaskRuntimeExecutionPlanSchemaError> {
          validator.read(raw.toByteArray(), "source")
        }
      assertEquals("execution plan must be a JSON object", error.reason)
    }
    val invalidEncoding =
      assertFailsWith<InvalidFeatureTaskRuntimeExecutionPlanSchemaError> {
        validator.read(byteArrayOf(0xc3.toByte(), 0x28), "source")
      }
    assertEquals("invalid UTF-8 encoding", invalidEncoding.reason)
  }

  @Test
  fun `producer and reader distinguish unsupported contract versions without echoing the descriptor`() {
    val payload = mapOf(FeatureTaskRuntimeExecutionPlanKeys.CONTRACT_VERSION to "9.9")
    val encoded = JsonCodec.mapToJsonString(payload).toByteArray(Charsets.UTF_8)
    val readerFailure =
      assertFailsWith<UnsupportedFeatureTaskRuntimeExecutionPlanError> {
        validator.read(encoded, "private-source")
      }
    val writerFailure =
      assertFailsWith<UnsupportedFeatureTaskRuntimeExecutionPlanError> {
        validator.write(payload, "private-source")
      }
    assertEquals(readerFailure.reasonCode, writerFailure.reasonCode)
    assertFalse(readerFailure.message.orEmpty().contains("9.9"))
    assertFalse(readerFailure.message.orEmpty().contains("private-source"))
  }

  @Test
  fun `producer and reader reject the same invalid schema with payload free diagnostics`() {
    val secret = "private-command-argument"
    val payload = mapOf(FeatureTaskRuntimeExecutionPlanKeys.CONTRACT_VERSION to secret)
    val readError =
      assertFailsWith<InvalidFeatureTaskRuntimeExecutionPlanSchemaError> {
        validator.read(JsonCodec.mapToJsonString(payload).toByteArray(), secret.repeat(1000))
      }
    val writeError =
      assertFailsWith<InvalidFeatureTaskRuntimeExecutionPlanSchemaError> {
        validator.write(payload, secret.repeat(1000))
      }
    assertEquals(readError.reason, writeError.reason)
    assertFalse(readError.message.orEmpty().contains(secret))
    val unsupported =
      assertFailsWith<InvalidFeatureTaskRuntimeExecutionPlanSchemaError> {
        validator.write(mapOf(FeatureTaskRuntimeExecutionPlanKeys.DEFINITION to Any()), "source")
      }
    assertEquals("unsupported JSON value", unsupported.reason)
  }
}
