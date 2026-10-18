package skillbill.infrastructure.skills.scaffold

import skillbill.infrastructure.skills.scaffold.platformpack.manifest.anchoredTopLevelFieldNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformPackSchemaAnchoredBijectionTest {
  private val expectedAnchoredFields: Set<String> =
    setOf(
      "platform",
      "contract_version",
      "display_name",
      "notes",
      "routing_signals",
      "declared_code_review_areas",
      "declared_files",
      "area_metadata",
      "declared_quality_check_file",
      "validation_gate",
      "qa_capability",
      "code_review_composition",
      "fallback_capabilities",
      "pointers",
      "addon_usage",
      "feature_addon_usage",
      "lane_conditions",
      "required_rubric_companions",
      "validation_gate",
    )

  @Test
  fun `schema anchored set matches Kotlin runtime-consumed top-level fields`() {
    val schemaSide: Set<String> = anchoredTopLevelFieldNames()

    val missingFromSchema = expectedAnchoredFields - schemaSide
    val extraInSchema = schemaSide - expectedAnchoredFields

    assertTrue(
      missingFromSchema.isEmpty(),
      "SKILL-48 anchored bijection: Kotlin runtime consumes top-level fields that are NOT marked " +
        "`x-runtime-anchored: true` in the canonical schema: $missingFromSchema. " +
        "Add the marker in orchestration/contracts/platform-pack-schema.yaml.",
    )
    assertTrue(
      extraInSchema.isEmpty(),
      "SKILL-48 anchored bijection: schema marks top-level fields `x-runtime-anchored: true` that " +
        "the Kotlin runtime does NOT consume by name: $extraInSchema. " +
        "Either drop the marker (so the field flows through PlatformManifest.customFields) " +
        "or thread the field into ShellContentLoader.buildPack + PlatformManifest.",
    )

    assertEquals(expectedAnchoredFields, schemaSide)
  }
}
