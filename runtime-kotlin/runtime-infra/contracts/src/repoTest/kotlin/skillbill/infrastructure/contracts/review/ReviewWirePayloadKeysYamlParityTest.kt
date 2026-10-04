package skillbill.infrastructure.contracts.review

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.ReviewAccountingPayloadKeys
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.contracts.review.ReviewFinishedTelemetryPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.infrastructure.contracts.locator.ReviewContextSchemaPaths
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewWirePayloadKeysYamlParityTest {
  private val yaml = YAMLMapper()

  @Test
  fun `ReviewAccountingPayloadKeys owns every accounting_summary schema field`() {
    val schema =
      yaml.readTree(
        Files.readString(repoRootFromTest().resolve(ReviewContextSchemaPaths.REPO_RELATIVE_PATH)),
      )
    val kotlinKeys =
      payloadKeyValues(ReviewAccountingPayloadKeys::class.java) -
        ReviewAccountingPayloadKeys.ACCOUNTING_SUMMARY_KIND
    val schemaKeys =
      referencedSchemaProperties(
        schema.path("\$defs").path("accounting_summary"),
        schema.path("\$defs"),
      )
    val drift = schemaFieldOwnerDrift(schemaKeys, kotlinKeys)
    assertTrue(drift.isEmpty(), "Accounting schema/key-owner drift: $drift")
  }

  @Test
  fun `ReviewFinishedTelemetryPayloadKeys owns every skillbill_review_finished property`() {
    val schema =
      yaml.readTree(
        Files.readString(repoRootFromTest().resolve("orchestration/contracts/telemetry-event-schema.yaml")),
      )
    val kotlinKeys =
      (
        payloadKeyValues(ReviewFinishedTelemetryPayloadKeys::class.java) -
          unconstrainedReviewFinishedDetailKeys
      ) +
        setOf(ReviewVerificationSignalKeys.REVIEW_RUN_ID) +
        setOf(
          ReviewFindingPayloadKeys.CLAIM_VERDICT,
          ReviewFindingPayloadKeys.SCOPE_DISPOSITION,
        ) +
        setOf(
          LifecycleTelemetryPayloadKeys.ROUTED_SKILL,
          LifecycleTelemetryPayloadKeys.DETECTED_STACK,
          LifecycleTelemetryPayloadKeys.FALLBACK,
          LifecycleTelemetryPayloadKeys.FALLBACK_REASON,
          LifecycleTelemetryPayloadKeys.SCOPE_TYPE,
        )
    val yamlKeys =
      referencedSchemaProperties(
        schema.path("\$defs").path("skillbillReviewFinishedEvent"),
        schema.path("\$defs"),
      ) - setOf(LifecycleTelemetryPayloadKeys.EVENT_NAME, SharedPayloadKeys.CONTRACT_VERSION)
    val drift = schemaFieldOwnerDrift(yamlKeys, kotlinKeys)
    assertTrue(drift.isEmpty(), "Review-finished schema/key-owner drift: $drift")
  }

  @Test
  fun `key parity detects synthetic missing and extra owners`() {
    assertEquals(
      listOf("extra:unexpected"),
      schemaFieldOwnerDrift(
        schemaPropertyKeys = setOf("required"),
        declaredKeyValues = setOf("required", "unexpected"),
      ),
    )
    assertEquals(
      listOf("missing:required"),
      schemaFieldOwnerDrift(
        schemaPropertyKeys = setOf("required"),
        declaredKeyValues = emptySet(),
      ),
    )
  }

  private fun schemaFieldOwnerDrift(
    schemaPropertyKeys: Set<String>,
    declaredKeyValues: Set<String>,
  ): List<String> =
    buildList {
      (schemaPropertyKeys - declaredKeyValues).sorted().forEach { field ->
        add("missing:$field")
      }
      (declaredKeyValues - schemaPropertyKeys).sorted().forEach { field ->
        add("extra:$field")
      }
    }

  private fun payloadKeyValues(owner: Class<*>): Set<String> =
    owner.declaredFields
      .filter { field -> field.type == String::class.java }
      .map { field ->
        field.isAccessible = true
        field.get(null) as String
      }
      .toSet()

  private fun referencedSchemaProperties(
    node: JsonNode,
    definitions: JsonNode,
  ): Set<String> {
    val visitedDefinitions = mutableSetOf<String>()

    fun collect(current: JsonNode): Set<String> {
      if (current.isMissingNode) return emptySet()
      val reference = current.path("\$ref").asText()
      if (reference.startsWith("#/\$defs/")) {
        val definitionName = reference.removePrefix("#/\$defs/")
        if (!visitedDefinitions.add(definitionName)) return emptySet()
        return collect(definitions.path(definitionName))
      }
      val properties = current.path("properties")
      val propertyNames =
        if (properties.isObject) {
          properties.fields().asSequence().flatMap { entry ->
            sequenceOf(entry.key) + collect(entry.value).asSequence()
          }.toSet()
        } else {
          emptySet()
        }
      val nestedSchemaNodes =
        buildList {
          if (current.has("items")) add(current.path("items"))
          listOf("oneOf", "anyOf", "allOf").forEach { keyword ->
            current.path(keyword).takeIf(JsonNode::isArray)?.forEach(::add)
          }
        }
      return propertyNames + nestedSchemaNodes.flatMap(::collect)
    }

    return collect(node)
  }

  private val unconstrainedReviewFinishedDetailKeys =
    setOf(
      ReviewFindingPayloadKeys.FINDING_ID,
      ReviewFindingPayloadKeys.ISSUE_CATEGORY,
      ReviewFinishedTelemetryPayloadKeys.SEVERITY,
      ReviewFinishedTelemetryPayloadKeys.CONFIDENCE,
      ReviewFinishedTelemetryPayloadKeys.OUTCOME_TYPE,
      ReviewFinishedTelemetryPayloadKeys.LOCATION,
      ReviewFinishedTelemetryPayloadKeys.DESCRIPTION,
      ReviewFinishedTelemetryPayloadKeys.NOTE,
      ReviewFinishedTelemetryPayloadKeys.APPLIED_COUNT,
      ReviewFinishedTelemetryPayloadKeys.APPLIED_REFERENCES,
      ReviewFinishedTelemetryPayloadKeys.APPLIED_SUMMARY,
      ReviewFinishedTelemetryPayloadKeys.SCOPE_COUNTS,
      ReviewFinishedTelemetryPayloadKeys.ENTRIES,
      ReviewFinishedTelemetryPayloadKeys.REFERENCE,
      ReviewFinishedTelemetryPayloadKeys.SCOPE,
      ReviewFinishedTelemetryPayloadKeys.TITLE,
      ReviewFinishedTelemetryPayloadKeys.RULE_TEXT,
    )
}
