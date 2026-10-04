package skillbill.infrastructure.skills.scaffold

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.error.shellcontent.SkillStagingFailureCode
import skillbill.infrastructure.skills.scaffold.platformpack.loader.discoverPlatformPackManifests
import skillbill.infrastructure.skills.scaffold.platformpack.loader.validatePlatformPackFallbacks
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PlatformPackFallbackTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `loader exposes anchored fallback without custom field leakage`() {
    val packRoot = tempDir.resolve("custom-neutral").createDirectories()
    packRoot.resolve("platform.yaml").writeText(
      """
      platform: custom-neutral
      contract_version: "1.8"
      routing_signals:
        strong: [fallback-only]
      fallback_capabilities: [code-review]
      declared_code_review_areas: []
      declared_files:
        baseline: code-review/bill-custom-neutral-code-review/content.md
      """.trimIndent(),
    )
    Files.createDirectories(packRoot.resolve("code-review/bill-custom-neutral-code-review"))
    packRoot.resolve("code-review/bill-custom-neutral-code-review/content.md").writeText(
      """
      ---
      name: bill-custom-neutral-code-review
      description: Neutral review fallback used by the contract fixture.
      internal-for: skill-bill
      ---

      # Neutral Review

      ## Classification Rules

      Review without stack assumptions.
      """.trimIndent(),
    )

    val loaded = discoverPlatformPackManifests(tempDir).single()

    assertEquals(setOf("code-review"), loaded.fallbackCapabilities)
    assertFalse("fallback_capabilities" in loaded.customFields)
  }

  @Test
  fun `duplicate fallback owners fail with typed contract error`() {
    assertFailsWith<SkillBillRuntimeException> {
      validatePlatformPackFallbacks(listOf(pack("one", review = true), pack("two", review = true)))
    }.also { failure ->
      assertEquals(SkillStagingFailureCode.INVALID_FALLBACK_CAPABILITY, failure.code)
    }
  }

  @Test
  fun `review fallback without baseline fails with typed contract error`() {
    assertFailsWith<SkillBillRuntimeException> {
      validatePlatformPackFallbacks(listOf(pack("broken", review = false)))
    }.also { failure ->
      assertEquals(SkillStagingFailureCode.INVALID_FALLBACK_CAPABILITY, failure.code)
    }
  }

  @Test
  fun `schema rejects unsupported fallback capability`() {
    val packRoot = tempDir.resolve("malformed").createDirectories()
    packRoot.resolve("platform.yaml").writeText(
      """
      platform: malformed
      contract_version: "1.8"
      routing_signals:
        strong: [fallback-only]
      fallback_capabilities: [quality-check]
      declared_code_review_areas: []
      """.trimIndent(),
    )

    assertFailsWith<SkillBillRuntimeException> {
      discoverPlatformPackManifests(tempDir)
    }.also { failure ->
      assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
    }
  }

  private fun pack(
    slug: String,
    review: Boolean,
  ) = PlatformManifest(
    slug = slug,
    packRoot = tempDir.resolve(slug).toFileLocation(),
    contractVersion = "1.3",
    routingSignals = RoutingSignals(listOf("fallback-only"), emptyList()),
    declaredCodeReviewAreas = emptyList(),
    declaredFiles =
      DeclaredFiles(
        baseline =
          if (review) {
            tempDir.resolve(slug).resolve("code-review/bill-$slug-code-review/content.md").toFileLocation()
          } else {
            null
          },
        areas = emptyMap(),
      ),
    areaMetadata = emptyMap(),
    fallbackCapabilities = setOf("code-review"),
  )
}
