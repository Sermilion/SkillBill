package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeBuildReceiptSchemaError
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeBuildReceiptSchemaPaths
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeBuildReceiptSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION`() {
    val schemaFile = repoRootFromTest().resolve(FeatureTaskRuntimeBuildReceiptSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical build-receipt schema is missing at $schemaFile.")
    val schema: JsonNode = YAMLMapper().readTree(Files.readString(schemaFile))
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")
    assertNotNull(
      contractVersionNode.takeIf { !it.isMissingNode && it.isTextual },
      "Schema must pin properties.contract_version.const as a string.",
    )
    assertEquals(
      FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION,
      contractVersionNode.asText(),
    )
  }

  @Test
  fun `schema id matches FeatureTaskRuntimeBuildReceiptSchemaPaths EXPECTED_SCHEMA_ID`() {
    val idNode = classpathSchema().path("\$id")
    assertTrue(!idNode.isMissingNode && idNode.isTextual, "Schema must declare a textual `\$id`.")
    assertEquals(
      FeatureTaskRuntimeBuildReceiptSchemaPaths.EXPECTED_SCHEMA_ID,
      idNode.asText(),
    )
  }

  @Test
  fun `schema resolves from the classpath resource path`() {
    assertNotNull(
      javaClass.classLoader.getResourceAsStream(FeatureTaskRuntimeBuildReceiptSchemaPaths.CLASSPATH_RESOURCE),
      "Canonical build-receipt schema is missing from the classpath at " +
        "'${FeatureTaskRuntimeBuildReceiptSchemaPaths.CLASSPATH_RESOURCE}'.",
    )
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream =
      FeatureTaskRuntimeBuildReceiptSchemaValidator::class.java.classLoader
        .getResourceAsStream(FeatureTaskRuntimeBuildReceiptSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical feature-task-runtime build-receipt schema is missing from the classpath at " +
        "'${FeatureTaskRuntimeBuildReceiptSchemaPaths.CLASSPATH_RESOURCE}'. " +
        "Ensure `copyFeatureTaskRuntimeBuildReceiptSchema` ran before this test.",
    )
    return YAMLMapper().readTree(resourceStream.use { it.readBytes().toString(Charsets.UTF_8) })
  }
}

class FeatureTaskRuntimeBuildReceiptSchemaValidatorTest {
  @Test
  fun `a representative build receipt payload validates`() {
    FeatureTaskRuntimeBuildReceiptSchemaValidator.validate(
      payload = representativeReceipt(),
      sourceLabel = "build#1",
    )
  }

  @Test
  fun `a missing gate_run_count fails validation`() {
    val payload = representativeReceipt().toMutableMap().apply { remove("gate_run_count") }
    assertFailsWith<InvalidFeatureTaskRuntimeBuildReceiptSchemaError> {
      FeatureTaskRuntimeBuildReceiptSchemaValidator.validate(payload, sourceLabel = "build#missing-count")
    }
  }

  @Test
  fun `a wrong contract_version fails validation`() {
    val payload = representativeReceipt().toMutableMap().apply { put("contract_version", "9.9") }
    assertFailsWith<InvalidFeatureTaskRuntimeBuildReceiptSchemaError> {
      FeatureTaskRuntimeBuildReceiptSchemaValidator.validate(payload, sourceLabel = "build#bad-version")
    }
  }

  @Test
  fun coherenceRejectsZeroRunsUnknownOrMissingFactsAndMismatchedAggregates() {
    val receipt = representativeReceipt()
    val run = (receipt.getValue("gate_runs") as List<*>).single() as Map<*, *>
    val missing =
      listOf("command", "exit_code", "repository_checkpoint", "executed_checks").map { key ->
        receipt + ("gate_runs" to listOf(run - key))
      }
    val invalid =
      missing +
        listOf(
          receipt + mapOf("gate_run_count" to 0, "gate_runs" to emptyList<Any>()),
          receipt + ("gate_run_count" to 2),
          receipt + ("checks" to listOf("invented")),
          receipt + ("gate_runs" to listOf(run + ("outcome" to "unknown"))),
          receipt +
            mapOf(
              "gate_run_count" to 2,
              "gate_runs" to
                listOf(run, run + mapOf("outcome" to "failed", "exit_code" to 1)),
            ),
        )
    invalid.forEach { payload ->
      assertFailsWith<InvalidFeatureTaskRuntimeBuildReceiptSchemaError> {
        FeatureTaskRuntimeBuildReceiptSchemaValidator.validate(payload, "coherence")
      }
    }
    FeatureTaskRuntimeBuildReceiptSchemaValidator.validate(
      receipt + ("gate_runs" to listOf(run + ("executed_work_units" to 0))),
      "cached",
    )
  }

  private fun representativeReceipt(): Map<String, Any?> =
    linkedMapOf(
      "contract_version" to FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION,
      "validation_status" to "passed",
      "checks" to emptyList<String>(),
      "repository_checkpoint" to mapOf("fingerprint" to "fp-abc"),
      "gate_run_count" to 1,
      "gate_runs" to
        listOf(
          mapOf(
            "duration_ms" to 1,
            "outcome" to "passed",
            "cache_mode" to "cache_eligible",
            "executed_work_units" to 1,
            "executed_checks" to emptyList<String>(),
            "command" to "./gradlew compileKotlin",
            "exit_code" to 0,
            "repository_checkpoint" to "fp-abc",
          ),
        ),
    )
}
