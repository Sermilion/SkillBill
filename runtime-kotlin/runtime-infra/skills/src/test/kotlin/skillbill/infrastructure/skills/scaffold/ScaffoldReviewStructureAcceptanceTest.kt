package skillbill.infrastructure.skills.scaffold

import org.yaml.snakeyaml.Yaml
import skillbill.infrastructure.skills.scaffold.authoring.renderAuthoringTarget
import skillbill.infrastructure.skills.scaffold.rendering.canonicalSeverityCloser
import skillbill.infrastructure.skills.scaffold.rendering.defaultAreaFocus
import skillbill.infrastructure.skills.scaffold.runtime.service.scaffold
import skillbill.infrastructure.skills.scaffold.validation.review.ReviewSkillStructureValidator
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScaffoldReviewStructureAcceptanceTest {
  @Test
  fun `platform pack scaffolder emits the complete review structure`() =
    withReviewStructureUserHome {
      val repo = seedReviewStructureRepo()

      scaffold(reviewStructurePayload(repo, "platform-pack", "platform" to "java"))

      val pack = repo.resolve("platform-packs/java")
      assertSpecialistPointers(pack)
      assertRenderedSharedContract(repo)
      assertBaseline(pack)
      assertFalse(Files.exists(pack.resolve("quality-check")))
      assertFalse(Files.readString(pack.resolve("platform.yaml")).contains("declared_quality_check_file"))
      assertNativeAgents(pack)
      val violations = ReviewSkillStructureValidator.violations(pack)
      assertEquals(emptyList(), violations, violations.joinToString("\n"))
      APPROVED_CODE_REVIEW_AREAS.forEach { area ->
        val content =
          Files.readString(
            pack.resolve("code-review/bill-java-code-review-$area/content.md"),
          )
        assertContains(content, "internal-for: skill-bill")
        assertEquals(
          listOf("Focus", "Ignore", "Applicability", "Project-Specific Rules"),
          content.lineSequence().filter { it.startsWith("## ") }.map { it.removePrefix("## ") }.toList(),
        )
        assertContains(content, "### Review Rules")
        assertContains(content, "Verify `Java")
        assertContains(content, canonicalSeverityCloser(area))
        assertEquals(
          canonicalSeverityCloser(area),
          content.lineSequence().filter { it.startsWith("- ") }.last(),
        )
        assertRetiredHeadingsAbsent(content)
        if (area == "ui") assertUiDeferrals(content)
        if (area == "ux-accessibility") assertAccessibilityDeferrals(content)
      }
    }

  private fun assertRenderedSharedContract(repo: Path) {
    val contract =
      """
      |## Shared Contract For Every Specialist
      |- Evidence is mandatory: include `file:line` and a consequence.
      |- Severity: `Blocker | Major | Minor`.
      |- Include a minimal concrete fix for each finding.
      |
      |## Shared Delegation Contract
      |- Deduplicate overlapping findings without losing attribution.
      """.trimMargin()
    Files.writeString(repo.resolve("orchestration/review-orchestrator/PLAYBOOK.md"), contract)
    Files.writeString(repo.resolve("orchestration/review-orchestrator/specialist-contract.md"), contract)
    listOf("bill-java-code-review", "bill-java-code-review-architecture").forEach { skillName ->
      val rendered = renderAuthoringTarget(repo, skillName)
      val pointerName = if (skillName.endsWith("-architecture")) "specialist-contract.md" else "review-orchestrator.md"
      val pointer = rendered.blocks.single { it.header.endsWith("$pointerName =====") }
      val target = pointer.header.substringAfter("pointer: ").substringBeforeLast(" =====")
      val resolved = repo.resolve(target).parent.resolve(pointer.content.trim()).normalize()
      val shared = Files.readString(resolved)
      assertContains(shared, "Evidence is mandatory")
      assertContains(shared, "Severity: `Blocker | Major | Minor`")
      assertContains(shared, "Deduplicate overlapping findings")
      assertContains(shared, "minimal concrete fix")
    }
  }

  private fun assertBaseline(pack: Path) {
    val content = Files.readString(pack.resolve("code-review/bill-java-code-review/content.md"))
    assertContains(content, "internal-for: skill-bill")
    assertEquals(
      listOf("Classification Rules", "Diff-Signal Routing Table", "Mixed Diffs", "Finding Discipline"),
      headings(content),
    )
    assertContains(content, "Keep the baseline specialists for the whole review")
    assertContains(content, "lightweight file-level classification")
    assertContains(content, "generated, vendored, and non-stack-owned files")
    assertContains(
      content,
      "as subagents in this harness in a deterministic order and retain every selected specialist result",
    )
    assertContains(content, "limited to platform-specific finding preconditions")
    assertTrue(!content.contains("Calibrate severity and verify each finding's preconditions"))
    assertTrue(!content.contains("attributed to their specialist lane through merge"))
    assertTrue(!content.contains("Deduplicate overlapping findings without losing evidence"))
    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      assertContains(content, "-> `$area` specialist.")
    }
  }

  private fun assertNativeAgents(pack: Path) {
    val agentsFile = pack.resolve("code-review/bill-java-code-review/native-agents/agents.yaml")
    val agents = (Yaml().load<Any>(Files.readString(agentsFile)) as Map<*, *>)["agents"] as List<*>
    val descriptions = agents.filterIsInstance<Map<*, *>>().associate { it["name"] to it["description"] }
    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      val description = descriptions["bill-java-code-review-$area"] as String
      assertEquals(
        "Java ${area.replace('-', ' ')} specialist — " +
          "Java ${defaultAreaFocus(area)} across pom.xml, build.gradle, src/main/java signals.",
        description,
      )
    }
  }

  private fun assertSpecialistPointers(pack: Path) {
    val manifest = Yaml().load<Map<String, Any?>>(Files.readString(pack.resolve("platform.yaml")))
    val pointers = manifest.getValue("pointers") as Map<*, *>
    val expected =
      listOf(
        mapOf(
          "name" to "specialist-contract.md",
          "target" to "orchestration/review-orchestrator/specialist-contract.md",
        ),
      )
    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      assertEquals(expected, pointers["code-review/bill-java-code-review-$area"])
    }
  }

  private fun headings(content: String): List<String> =
    content.lineSequence()
      .filter { it.startsWith("## ") }
      .map { it.removePrefix("## ") }
      .toList()

  private fun assertUiDeferrals(content: String) {
    assertContains(content, "Defer accessibility concerns to the ux-accessibility specialist")
    assertContains(content, "security concerns to the security specialist")
  }

  private fun assertAccessibilityDeferrals(content: String) {
    assertContains(content, "Defer UI correctness concerns to the ui specialist")
    assertContains(content, "security concerns to the security specialist")
  }

  private fun assertRetiredHeadingsAbsent(content: String) {
    listOf("## Review Guidance", "## Checklist", "## Severity Guidance").forEach { heading ->
      assertFalse(heading in content, "Retired starter heading remains: $heading")
    }
  }
}
