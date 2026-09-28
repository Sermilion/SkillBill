package skillbill.infrastructure.skills.scaffold.validation.review

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalBaselineCompositionStructureTest {
  @Test
  fun `kmp baseline checks read an external kotlin pack when the sibling directory is absent`(
    @TempDir root: Path,
  ) {
    val kmpBaseline =
      writeBaseline(
        root.resolve("repo/platform-packs/kmp"),
        "kmp",
        "Keep this section limited to platform-specific finding preconditions.",
        composeOnKotlin = true,
      )
    val externalRoot = root.resolve("external/kotlin")
    writeBaseline(
      externalRoot,
      "kotlin",
      "Attributed merge keeps every selected result. Deduplicate without losing evidence.",
      composeOnKotlin = false,
    )

    val sibling = composedBaselineSections(kmpBaseline, "Finding Discipline")
    val external =
      composedBaselineSections(
        kmpBaseline,
        "Finding Discipline",
        mapOf("kotlin" to externalRoot.toAbsolutePath().normalize()),
      )
    val withoutExternal = ReviewSkillStructureValidator.violations(kmpBaseline.parent.parent.parent)
    val withExternal =
      ReviewSkillStructureValidator.violations(
        kmpBaseline.parent.parent.parent,
        mapOf("kotlin" to externalRoot.toAbsolutePath().normalize()),
      )

    assertFalse(sibling.contains("Attributed"))
    assertTrue(external.contains("Attributed merge"))
    assertTrue(external.contains("Deduplicate without losing evidence"))
    assertTrue(withoutExternal.any { violation -> violation.rule == "attributed finding merge" })
    assertTrue(withoutExternal.any { violation -> violation.rule == "evidence-preserving deduplication" })
    assertFalse(withExternal.any { violation -> violation.rule == "attributed finding merge" })
    assertFalse(withExternal.any { violation -> violation.rule == "evidence-preserving deduplication" })
  }

  private fun writeBaseline(
    packRoot: Path,
    slug: String,
    discipline: String,
    composeOnKotlin: Boolean,
  ): Path {
    val baseline = packRoot.resolve("code-review/bill-$slug-code-review/content.md")
    Files.createDirectories(baseline.parent)
    Files.writeString(baseline, baselineContent(slug, discipline))
    Files.writeString(packRoot.resolve("platform.yaml"), platformManifest(slug, composeOnKotlin))
    return baseline
  }

  private fun baselineContent(
    slug: String,
    discipline: String,
  ): String =
    """
    ---
    name: bill-$slug-code-review
    description: $slug review
    internal-for: skill-bill
    ---

    # $slug

    ## Classification Rules

    If markers dominate, select this pack.
    Otherwise classify the remainder here.

    ## Diff-Signal Routing Table

    - Module boundaries -> `architecture` specialist.

    ## Mixed Diffs

    Keep the baseline specialists for the whole review and use lightweight file-level classification.
    Exclude generated and vendored non-stack files from specialist scope.
    Launch specialists in deterministic subagent order through the harness and retain every selected result.

    ## Finding Discipline

    $discipline
    """.trimIndent() + "\n"

  private fun platformManifest(
    slug: String,
    composeOnKotlin: Boolean,
  ): String {
    val composition =
      if (composeOnKotlin) {
        """
        code_review_composition:
          baseline_layers:
            - platform: kotlin
              skill: bill-kotlin-code-review
              scope: same-review-scope
              required: true
              mode: kmp-baseline
        """.trimIndent() + "\n"
      } else {
        ""
      }
    return """
      platform: $slug
      contract_version: "1.8"
      display_name: "$slug"
      routing_signals:
        strong:
          - ".$slug"
        tie_breakers: []
      declared_code_review_areas: []
      declared_files:
        baseline: "code-review/bill-$slug-code-review/content.md"
      """.trimIndent() + "\n" + composition
  }
}
