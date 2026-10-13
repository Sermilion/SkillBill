package skillbill.scaffold.policy.platformpack

import skillbill.model.FileLocation
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.policy.platformpack.model.PlatformPackManifestContentRenderRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformPackManifestPolicyTest {
  @Test
  fun `renderPlatformPackManifestContent emits the canonical platform yaml for a starter java pack`() {
    val packRoot = FileLocation("/repo/platform-packs/java")
    val baselineSkillPath = packRoot.resolve("code-review").resolve("bill-java-code-review")

    val rendered =
      renderPlatformPackManifestContent(
        PlatformPackManifestContentRenderRequest(
          platform = "java",
          displayName = "Java",
          routingSignals = listOf("pom.xml", "build.gradle"),
          tieBreakers = listOf("Prefer Java"),
          specialistAreas = emptyList(),
          specialistAreaMetadata = emptyMap(),
          baselineLayers = emptyList(),
          packRoot = packRoot,
          baselineSkillPath = baselineSkillPath,
          specialistSkillPaths = emptyMap(),
        ),
      )

    val expected =
      (
        listOf(
          "platform: \"java\"",
          "contract_version: \"1.8\"",
          "display_name: \"Java\"",
          "",
          "routing_signals:",
          "  strong:",
          "    - \"pom.xml\"",
          "    - \"build.gradle\"",
          "  tie_breakers:",
          "    - \"Prefer Java\"",
          "  path: [\"pom.xml\", \"build.gradle\"]",
          "",
          "declared_code_review_areas: []",
          "",
          "declared_files:",
          "  baseline: \"code-review/bill-java-code-review/content.md\"",
          "  areas: {}",
          "area_metadata: {}",
          "lane_conditions: {}",
        ) + expectedPointerLines()
      ).joinToString("\n") + "\n"

    assertEquals(expected, rendered)
  }

  @Test
  fun `renderPlatformPackManifestContent appends baseline layers when provided`() {
    val packRoot = FileLocation("/repo/platform-packs/java")
    val baselineSkillPath = packRoot.resolve("code-review").resolve("bill-java-code-review")

    val rendered =
      renderPlatformPackManifestContent(
        PlatformPackManifestContentRenderRequest(
          platform = "java",
          displayName = "Java",
          routingSignals = listOf("pom.xml", "build.gradle"),
          tieBreakers = emptyList(),
          specialistAreas = emptyList(),
          specialistAreaMetadata = emptyMap(),
          baselineLayers = sampleBaselineLayers(),
          packRoot = packRoot,
          baselineSkillPath = baselineSkillPath,
          specialistSkillPaths = emptyMap(),
        ),
      )

    assertEquals(expectedRenderingWithBaselineLayers(), rendered)
  }

  private fun sampleBaselineLayers(): List<CodeReviewBaselineLayer> =
    listOf(
      CodeReviewBaselineLayer(
        platform = "kmp",
        skill = "bill-kmp-code-review",
        scope = CodeReviewCompositionScope.SameReviewScope,
        required = true,
        mode = CodeReviewCompositionMode.KmpBaseline,
      ),
      CodeReviewBaselineLayer(
        platform = "java",
        skill = "bill-java-code-review",
        scope = CodeReviewCompositionScope.SameReviewScope,
        required = false,
        mode = CodeReviewCompositionMode.KmpBaseline,
      ),
    )

  private fun expectedRenderingWithBaselineLayers(): String =
    (
      listOf(
        "platform: \"java\"",
        "contract_version: \"1.8\"",
        "display_name: \"Java\"",
        "",
        "routing_signals:",
        "  strong:",
        "    - \"pom.xml\"",
        "    - \"build.gradle\"",
        "  tie_breakers: []",
        "  path: [\"pom.xml\", \"build.gradle\"]",
        "",
        "declared_code_review_areas: []",
        "",
        "declared_files:",
        "  baseline: \"code-review/bill-java-code-review/content.md\"",
        "  areas: {}",
        "area_metadata: {}",
        "lane_conditions: {}",
      ) + expectedPointerLines() +
        listOf(
          "",
          "code_review_composition:",
          "  baseline_layers:",
          "    - platform: \"kmp\"",
          "      skill: \"bill-kmp-code-review\"",
          "      scope: \"same-review-scope\"",
          "      required: true",
          "      mode: \"kmp-baseline\"",
          "    - platform: \"java\"",
          "      skill: \"bill-java-code-review\"",
          "      scope: \"same-review-scope\"",
          "      required: false",
          "      mode: \"kmp-baseline\"",
        )
    ).joinToString("\n") + "\n"

  private fun expectedPointerLines(): List<String> =
    listOf(
      "",
      "pointers:",
      "  code-review/bill-java-code-review:",
      "    - name: \"review-orchestrator.md\"",
      "      target: \"orchestration/review-orchestrator/PLAYBOOK.md\"",
      "    - name: \"review-delegation.md\"",
      "      target: \"orchestration/review-delegation/PLAYBOOK.md\"",
      "    - name: \"review-scope.md\"",
      "      target: \"orchestration/review-scope/PLAYBOOK.md\"",
      "    - name: \"shell-ceremony.md\"",
      "      target: \"orchestration/shell-content-contract/shell-ceremony.md\"",
      "    - name: \"specialist-contract.md\"",
      "      target: \"orchestration/review-orchestrator/specialist-contract.md\"",
      "    - name: \"stack-routing.md\"",
      "      target: \"orchestration/stack-routing/PLAYBOOK.md\"",
      "    - name: \"telemetry-contract.md\"",
      "      target: \"orchestration/telemetry-contract/PLAYBOOK.md\"",
    )
}
