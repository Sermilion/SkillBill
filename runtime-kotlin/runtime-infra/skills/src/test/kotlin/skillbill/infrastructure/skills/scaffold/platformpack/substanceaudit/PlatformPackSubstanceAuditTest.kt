
package skillbill.infrastructure.skills.scaffold.platformpack.substanceaudit

import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.testing.seedConformingPlatformPack
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformPackSubstanceAuditTest {
  @Test
  fun `normalization is stable and unique five-token shingles ignore repetition`() {
    val text =
      "---\nname: bill-demo-code-review\n---\n[Verify](https://example.test) `DemoApi` must reject invalid state."
    val tokens = PlatformPackSubstanceAudit.normalize(text, listOf("demo", "bill-demo-code-review"))
    assertTrue("https" !in tokens)
    assertTrue(tokens.none { it.contains("https") })
    assertTrue("undergo" in PlatformPackSubstanceAudit.normalize("undergo go gopher", listOf("go")))
    assertEquals(1, PlatformPackSubstanceAudit.shingles("a b c d e a b c d e".split(" ")).count { it == "a b c d e" })
  }

  @Test
  fun `duplication thresholds are inclusive pass boundaries`() {
    assertTrue(Fraction(35, 100) <= SubstancePolicy().maximumSharedShingles)
    assertTrue(Fraction(36, 100) > SubstancePolicy().maximumSharedShingles)
    assertTrue(Fraction(65, 100) <= SubstancePolicy().maximumPairSimilarity)
    assertTrue(Fraction(66, 100) > SubstancePolicy().maximumPairSimilarity)
  }

  @Test
  fun `production audit passes exact duplication measurements and rejects the next rational below`() {
    val root = Files.createTempDirectory("substance-threshold-boundary")
    seedConformingPlatformPack(root, "alpha")
    seedConformingPlatformPack(root, "beta")
    val unrestricted =
      PlatformPackSubstanceAudit.audit(
        root,
        SubstancePolicy(maximumSharedShingles = Fraction(1, 1), maximumPairSimilarity = Fraction(1, 1)),
      )
    val alpha = unrestricted.packs.single { it.pack == "alpha" }
    val pair = alpha.highestCorrespondingSimilarity!!.similarity
    assertTrue(alpha.sharedShingles.numerator > 0)
    assertTrue(pair.numerator > 0)

    val exact =
      PlatformPackSubstanceAudit.audit(
        root,
        SubstancePolicy(maximumSharedShingles = alpha.sharedShingles, maximumPairSimilarity = pair),
      )
    assertFalse(exact.violations.any { it.pack == "alpha" && it.areaOrRole == "shared-shingles" })
    assertFalse(exact.violations.any { it.pack == "alpha" && it.areaOrRole.startsWith("pair:") })

    val sharedBelow = immediatelyBelow(alpha.sharedShingles)
    val pairBelow = immediatelyBelow(pair)
    val above =
      PlatformPackSubstanceAudit.audit(
        root,
        SubstancePolicy(maximumSharedShingles = sharedBelow, maximumPairSimilarity = pairBelow),
      )
    assertTrue(above.violations.any { it.pack == "alpha" && it.areaOrRole == "shared-shingles" })
    assertTrue(above.violations.any { it.pack == "alpha" && it.areaOrRole.startsWith("pair:") && it.files.size == 2 })
  }

  @Test
  fun `visible pack directory without manifest fails loudly while hidden directory is ignored`() {
    val root = Files.createTempDirectory("substance-missing-manifest")
    seedConformingPlatformPack(root, "valid")
    Files.createDirectories(root.resolve("platform-packs/missing"))
    Files.createDirectories(root.resolve("platform-packs/.hidden"))

    val report = PlatformPackSubstanceAudit.audit(root)

    assertEquals(listOf("valid"), report.packs.map { it.pack })
    assertEquals(
      listOf("platform-packs/missing/platform.yaml: maintained platform pack manifest is missing"),
      report.auditErrors,
    )
  }

  @Test
  fun `audit rejects thin placeholder shallow and duplicated authored content`() {
    val root = Files.createTempDirectory("substance-rejections")
    seedConformingPlatformPack(root, "alpha")
    seedConformingPlatformPack(root, "beta")
    seedConformingPlatformPack(root, "gamma")
    val alphaArea = root.resolve("platform-packs/alpha/code-review/bill-alpha-code-review-architecture/content.md")
    Files.writeString(
      alphaArea,
      """
      |---
      |name: bill-alpha-code-review-architecture
      |description: Thin placeholder fixture.
      |internal-for: skill-bill
      |---
      |## Project-Specific Rules
      |### Review Rules
      |- TODO: replace this content with a generic API example.
      |
      """.trimMargin(),
    )
    val report = PlatformPackSubstanceAudit.audit(root)
    val alphaRules = report.violations.filter { it.pack == "alpha" }.map { it.areaOrRole }
    assertTrue("architecture:rules" in alphaRules)
    assertTrue("architecture:placeholders" in alphaRules)
    assertFalse("quality-check" in alphaRules)
    assertTrue(report.violations.any { it.areaOrRole.startsWith("pair:") && it.files.size == 2 })
  }

  @Test
  fun `invalid required composition supplies no inherited coverage`() {
    val root = Files.createTempDirectory("substance-composition")
    seedConformingPlatformPack(root, "overlay")
    Files.writeString(
      root.resolve("platform-packs/overlay/platform.yaml"),
      """
      |code_review_composition:
      |  baseline_layers:
      |    - platform: missing
      |      skill: bill-missing-code-review
      |      scope: same-review-scope
      |      required: true
      |      mode: kmp-baseline
      |
      """.trimMargin(),
      StandardOpenOption.APPEND,
    )
    val report = PlatformPackSubstanceAudit.audit(root)
    assertTrue(report.packs.single().inheritedAreas.isEmpty())
    assertTrue(
      report.violations.any { it.areaOrRole == "composition:missing" && it.measured == "missing-pack:missing" },
    )
  }

  @Test
  fun `malformed manifests are reported without aborting the audit`() {
    val root = Files.createTempDirectory("substance-malformed")
    seedConformingPlatformPack(root, "valid")
    seedConformingPlatformPack(root, "broken")
    Files.writeString(root.resolve("platform-packs/broken/platform.yaml"), "platform: [")

    val report = PlatformPackSubstanceAudit.audit(root)

    assertEquals(listOf("valid"), report.packs.map { it.pack })
    assertTrue(report.auditErrors.single().startsWith("platform-packs/broken/platform.yaml:"))
  }

  @Test
  fun `missing declared authored content is reported without aborting the audit`() {
    val root = Files.createTempDirectory("substance-missing-content")
    seedConformingPlatformPack(root, "valid")
    seedConformingPlatformPack(root, "broken")
    val missing = root.resolve("platform-packs/broken/code-review/bill-broken-code-review-architecture/content.md")
    Files.delete(missing)

    val report = PlatformPackSubstanceAudit.audit(root)

    assertEquals(listOf("valid"), report.packs.map { it.pack })
    assertTrue(
      report.auditErrors.single().startsWith(
        "platform-packs/broken/code-review/bill-broken-code-review-architecture/content.md:",
      ),
    )
  }

  @Test
  fun `audit rejects missing effective review areas independently`() {
    val root = Files.createTempDirectory("substance-under-coverage")
    seedConformingPlatformPack(root, "partial")

    val violation =
      PlatformPackSubstanceAudit.audit(root).violations.single {
        it.areaOrRole == "effective-area-coverage"
      }

    assertTrue("security" in violation.measured)
    assertEquals("all-approved-areas", violation.target)
  }

  @Test
  fun `cyclic required composition contributes no inherited branch coverage`() {
    val root = Files.createTempDirectory("substance-cycle")
    seedConformingPlatformPack(root, "first")
    seedConformingPlatformPack(root, "second", listOf("security"))
    appendComposition(root, "first", "second")
    appendComposition(root, "second", "first")

    val report = PlatformPackSubstanceAudit.audit(root)

    assertTrue(report.packs.all { it.inheritedAreas.isEmpty() })
    assertEquals(2, report.violations.count { it.measured == "cyclic-required-composition" })
  }

  @Test
  fun `valid composition supplies inherited coverage without composition findings`() {
    val root = Files.createTempDirectory("substance-overlay")
    seedConformingPlatformPack(root, "base", APPROVED_CODE_REVIEW_AREAS.toList())
    seedConformingPlatformPack(root, "overlay", listOf("ui"))
    appendComposition(root, "overlay", "base")

    val report =
      PlatformPackSubstanceAudit.audit(
        root,
        SubstancePolicy(
          minimumRules = 0,
          minimumClusters = 0,
          maximumSharedShingles = Fraction(1, 1),
          maximumPairSimilarity = Fraction(1, 1),
        ),
      )

    val overlay = report.packs.single { it.pack == "overlay" }
    assertEquals((APPROVED_CODE_REVIEW_AREAS - "ui").sorted(), overlay.inheritedAreas)
    assertFalse(report.violations.any { it.pack == "overlay" && it.areaOrRole.startsWith("composition:") })
  }

  @Test
  fun `pack substance audit does not require a quality-check sidecar`() {
    val root = Files.createTempDirectory("substance-no-quality-check")
    seedConformingPlatformPack(root, "base", APPROVED_CODE_REVIEW_AREAS.toList())
    seedConformingPlatformPack(root, "overlay", listOf("ui"))
    appendComposition(root, "overlay", "base")

    val report =
      PlatformPackSubstanceAudit.audit(
        root,
        SubstancePolicy(
          minimumRules = 0,
          minimumClusters = 0,
          maximumSharedShingles = Fraction(1, 1),
          maximumPairSimilarity = Fraction(1, 1),
        ),
      )

    assertTrue(report.packs.all { it.qualityCheckFile == null })
    assertFalse(report.violations.any { it.areaOrRole == "quality-check" })
  }

  @Test
  fun `mechanically stripped scaffold prompts cannot pass promotion audit`() {
    val root = Files.createTempDirectory("substance-scaffold-promotion")
    seedConformingPlatformPack(root, "starter", APPROVED_CODE_REVIEW_AREAS.toList())
    Files.walk(root.resolve("platform-packs/starter")).use { paths ->
      paths.filter { it.fileName.toString() == "content.md" }.forEach { content ->
        val stripped = Files.readString(content).lineSequence().filterNot { "TODO" in it }.joinToString("\n")
        Files.writeString(content, stripped)
      }
    }

    val report = PlatformPackSubstanceAudit.audit(root)

    assertTrue(report.violations.any { it.areaOrRole.endsWith(":rules") })
    assertFalse(report.violations.any { it.areaOrRole == "quality-check" })
  }

  private fun appendComposition(
    root: Path,
    pack: String,
    target: String,
  ) {
    Files.writeString(
      root.resolve("platform-packs/$pack/platform.yaml"),
      """
      |code_review_composition:
      |  baseline_layers:
      |    - platform: $target
      |      skill: bill-$target-code-review
      |      scope: same-review-scope
      |      required: true
      |      mode: kmp-baseline
      |
      """.trimMargin(),
      StandardOpenOption.APPEND,
    )
  }

  private fun immediatelyBelow(value: Fraction): Fraction =
    Fraction(value.numerator * value.denominator - 1, value.denominator * value.denominator)
}
