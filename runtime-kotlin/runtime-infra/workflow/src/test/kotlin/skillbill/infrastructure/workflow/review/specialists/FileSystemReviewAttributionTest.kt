package skillbill.infrastructure.workflow.review.specialists

import skillbill.infrastructure.skills.scaffold.platformpack.loader.discoverPlatformPackManifests
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class FileSystemReviewAttributionTest {
  @Test
  fun `platform review attribution mappings are derived from discovered manifests`() {
    val repoRoot = Files.createTempDirectory("skillbill-review-attribution")
    val packRoot = repoRoot.resolve("platform-packs/ruby")
    val baseline = packRoot.resolve("code-review/bill-ruby-code-review/content.md")
    val security = packRoot.resolve("code-review/bill-ruby-code-review-security/content.md")
    Files.createDirectories(baseline.parent)
    Files.createDirectories(security.parent)
    Files.writeString(packRoot.resolve("platform.yaml"), platformManifest())
    Files.writeString(baseline, content("bill-ruby-code-review"))
    Files.writeString(security, content("bill-ruby-code-review-security"))

    val mappings = platformReviewAttributionMappings(repoRoot.resolve("platform-packs"))

    assertEquals("ruby", mappings["bill-ruby-code-review"])
    assertEquals("ruby", mappings["bill-ruby-code-review-security"])
    assertEquals(setOf("bill-ruby-code-review", "bill-ruby-code-review-security"), mappings.keys)
  }

  @Test
  fun `composed launch plan carries baseline sourced lanes with their owning pack and depth`() {
    val repoRoot = composedPackFixture()

    val plan = FileSystemReviewAttribution(installedCatalog(repoRoot), NoopRuntimeDiagnostics).composedLaunchPlan("kmp")

    assertEquals(
      listOf("kmp" to "architecture", "kotlin" to "testing"),
      plan.lanes.map { it.packSlug to it.area },
    )
    assertEquals(listOf(0, 1), plan.lanes.map { it.depth })
    assertEquals(
      listOf("bill-kmp-code-review-architecture", "bill-kotlin-code-review-testing"),
      plan.lanes.map { it.skillName },
    )
    assertEquals(listOf(listOf("kmp"), listOf("kmp", "kotlin")), plan.lanes.map { it.originLayerChain })
  }

  @Test
  fun `an unknown routed pack slug yields an empty plan rather than failing`() {
    val repoRoot = composedPackFixture()

    val plan =
      FileSystemReviewAttribution(installedCatalog(repoRoot), NoopRuntimeDiagnostics).composedLaunchPlan("nowhere")

    assertEquals("nowhere", plan.routedPackSlug)
    assertEquals(emptyList(), plan.lanes)
  }

  private fun installedCatalog(repoRoot: Path) =
    InstalledPlatformPackCatalogPort {
      discoverPlatformPackManifests(repoRoot.resolve("platform-packs"))
    }

  private fun composedPackFixture(): Path {
    val repoRoot = Files.createTempDirectory("skillbill-review-composition")
    writePack(repoRoot, slug = "kotlin", area = "testing", composesKotlin = false)
    writePack(repoRoot, slug = "kmp", area = "architecture", composesKotlin = true)
    return repoRoot
  }

  private fun writePack(
    repoRoot: Path,
    slug: String,
    area: String,
    composesKotlin: Boolean,
  ) {
    val packRoot = repoRoot.resolve("platform-packs/$slug")
    val baseline = packRoot.resolve("code-review/bill-$slug-code-review/content.md")
    val areaFile = packRoot.resolve("code-review/bill-$slug-code-review-$area/content.md")
    Files.createDirectories(baseline.parent)
    Files.createDirectories(areaFile.parent)
    Files.writeString(baseline, content("bill-$slug-code-review"))
    Files.writeString(areaFile, content("bill-$slug-code-review-$area"))
    Files.writeString(packRoot.resolve("platform.yaml"), composedManifest(slug, area, composesKotlin))
  }

  private fun composedManifest(
    slug: String,
    area: String,
    composesKotlin: Boolean,
  ): String {
    val composition =
      if (composesKotlin) {
        """
      |code_review_composition:
      |  baseline_layers:
      |    - platform: "kotlin"
      |      skill: "bill-kotlin-code-review"
      |      scope: "same-review-scope"
      |      required: true
      |      mode: "kmp-baseline"
        """.trimMargin()
      } else {
        ""
      }
    return """
      |platform: $slug
      |contract_version: "1.8"
      |display_name: $slug
      |routing_signals:
      |  strong:
      |    - build.gradle.kts
      |  tie_breakers: []
      |declared_code_review_areas:
      |  - $area
      |declared_files:
      |  baseline: code-review/bill-$slug-code-review/content.md
      |  areas:
      |    $area: code-review/bill-$slug-code-review-$area/content.md
      |area_metadata:
      |  $area:
      |    focus: $slug $area review.
      |$composition
      |
      """.trimMargin()
  }

  private fun platformManifest(): String =
    """
    |platform: ruby
    |contract_version: "1.8"
    |display_name: Ruby
    |routing_signals:
    |  strong:
    |    - Gemfile
    |  tie_breakers: []
    |declared_code_review_areas:
    |  - security
    |declared_files:
    |  baseline: code-review/bill-ruby-code-review/content.md
    |  areas:
    |    security: code-review/bill-ruby-code-review-security/content.md
    |area_metadata:
    |  security:
    |    focus: Ruby security review.
    |
    """.trimMargin()

  private fun content(name: String): String =
    """
    |---
    |name: $name
    |description: Review Ruby code.
    |---
    |
    |Review Ruby changes.
    |
    """.trimMargin()
}
