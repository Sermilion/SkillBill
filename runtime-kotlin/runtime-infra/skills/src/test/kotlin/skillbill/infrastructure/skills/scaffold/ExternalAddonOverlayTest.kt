
package skillbill.infrastructure.skills.scaffold

import org.junit.jupiter.api.io.TempDir
import skillbill.error.core.ExternalAddonFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.install.model.ExternalAddonSource
import skillbill.ports.install.addon.ExternalAddonOverlayPort
import skillbill.ports.install.addon.model.ExternalAddonOverlayRequest
import skillbill.ports.repository.toFileLocation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalAddonOverlayTest {
  @TempDir
  lateinit var work: Path

  private lateinit var platformPacksRoot: Path

  private val overlay: ExternalAddonOverlayPort = overlayPort()

  private fun request(sources: List<ExternalAddonSource>): ExternalAddonOverlayRequest =
    ExternalAddonOverlayRequest(platformPacksRoot = platformPacksRoot, sources = sources)

  @Test
  fun `valid merge copies md files and appends entries to installed platform yaml`() {
    seedIosPack(packOwnedAddon = "offline")
    val source = seedExternalSource("ios", "acme", listOf("acme-review.md"))

    val result = overlay.applyOverlay(request(listOf(source)))

    assertTrue(result.touched)
    assertEquals(1, result.appliedSources.size)
    assertEquals("ios", result.appliedSources[0].platform)
    assertTrue(Files.isRegularFile(platformPacksRoot.resolve("ios/addons/acme-review.md")))
    val manifest = Files.readString(platformPacksRoot.resolve("ios/platform.yaml"))
    assertTrue(manifest.contains("slug: acme"))
    assertTrue(manifest.contains("target: platform-packs/ios/addons/acme-review.md"))
  }

  @Test
  fun `merge preserves activation from the external fragment`() {
    seedIosPack(packOwnedAddon = "offline")
    val sourceDir = Files.createDirectories(work.resolve("ext/ios-acme"))
    Files.writeString(sourceDir.resolve("acme-review.md"), "# acme body\n")
    Files.writeString(
      sourceDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        code-review/bill-ios-code-review:
          - slug: acme
            entrypoint: acme-review.md
            activation:
              any_path:
                - Acme.xcodeproj
              any_of_all_content:
                - ["grdb", "sync"]
              exclude_content: ["pure in-memory"]
      pointers:
        code-review/bill-ios-code-review:
          - name: acme-review.md
            target: acme-review.md
      """.trimIndent() + "\n",
    )

    overlay.applyOverlay(request(listOf(ExternalAddonSource(sourceDir.toFileLocation(), "ios"))))

    val manifest = Files.readString(platformPacksRoot.resolve("ios/platform.yaml"))
    assertContains(manifest, "activation")
    assertContains(manifest, "any_path")
    assertContains(manifest, "Acme.xcodeproj")
    assertContains(manifest, "any_of_all_content")
    assertContains(manifest, "exclude_content")
    assertContains(manifest, "pure in-memory")
  }

  @Test
  fun `pointer target rewriting converts source-relative target to canonical form`() {
    seedIosPack(packOwnedAddon = null)
    val source =
      seedExternalSource(
        "ios",
        "acme",
        listOf("acme-review.md"),
        target = "acme-review.md",
      )

    overlay.applyOverlay(request(listOf(source)))

    val manifest = Files.readString(platformPacksRoot.resolve("ios/platform.yaml"))
    assertTrue(manifest.contains("target: platform-packs/ios/addons/acme-review.md"))
    assertFalse(manifest.contains("target: acme-review.md"))
  }

  @Test
  fun `external addon slug colliding with pack owned slug loud-fails`() {
    seedIosPack(packOwnedAddon = "shared")
    val source = seedExternalSource("ios", "shared", listOf("shared.md"))

    assertFailsWith<SkillBillRuntimeException> {
      overlay.applyOverlay(request(listOf(source)))
    }.also { assertEquals(ExternalAddonFailureCode.OVERLAY, it.code) }
  }

  @Test
  fun `external pointer name colliding with pack owned pointer loud-fails`() {
    seedIosPack(packOwnedAddon = "offline")
    val sourceDir = Files.createDirectories(work.resolve("ext/ios-acme"))
    Files.writeString(sourceDir.resolve("acme-review.md"), "# acme body\n")
    Files.writeString(
      sourceDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        code-review/bill-ios-code-review:
          - slug: acme
            entrypoint: offline-review.md
      pointers:
        code-review/bill-ios-code-review:
          - name: offline-review.md
            target: acme-review.md
      """.trimIndent() + "\n",
    )
    val source = ExternalAddonSource(sourceDir.toFileLocation(), "ios")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        overlay.applyOverlay(request(listOf(source)))
      }.also { assertEquals(ExternalAddonFailureCode.OVERLAY, it.code) }
    assertTrue(error.message.orEmpty().contains("collides"))
    val manifest = Files.readString(platformPacksRoot.resolve("ios/platform.yaml"))
    assertFalse(manifest.contains("acme"), "Atomicity: failing overlay must not mutate the installed manifest.")
  }

  @Test
  fun `external pointer target colliding with pack owned target basename loud-fails`() {
    seedIosPack(packOwnedAddon = "offline")
    val sourceDir = Files.createDirectories(work.resolve("ext/ios-acme"))
    Files.writeString(sourceDir.resolve("offline-review.md"), "# acme overwrite body\n")
    Files.writeString(
      sourceDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        code-review/bill-ios-code-review:
          - slug: acme
            entrypoint: acme-entry.md
      pointers:
        code-review/bill-ios-code-review:
          - name: acme-entry.md
            target: offline-review.md
      """.trimIndent() + "\n",
    )
    val source = ExternalAddonSource(sourceDir.toFileLocation(), "ios")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        overlay.applyOverlay(request(listOf(source)))
      }.also { assertEquals(ExternalAddonFailureCode.OVERLAY, it.code) }
    assertTrue(
      error.message.orEmpty().contains("silent overwrite refused"),
      "Expected target-basename collision message, got: ${error.message}",
    )
    val manifest = Files.readString(platformPacksRoot.resolve("ios/platform.yaml"))
    assertFalse(manifest.contains("acme"), "Atomicity: failing overlay must not mutate the installed manifest.")
  }

  @Test
  fun `external-vs-external collision across two sources loud-fails`() {
    seedIosPack(packOwnedAddon = null)
    val first = seedExternalSource("ios", "acme", listOf("acme-review.md"))
    val secondDir = Files.createDirectories(work.resolve("ext/ios-acme-2"))
    Files.writeString(secondDir.resolve("acme-alt.md"), "# alt body\n")
    Files.writeString(
      secondDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        code-review/bill-ios-code-review:
          - slug: acme
            entrypoint: acme-alt.md
      pointers:
        code-review/bill-ios-code-review:
          - name: acme-alt.md
            target: acme-alt.md
      """.trimIndent() + "\n",
    )
    val second = ExternalAddonSource(secondDir.toFileLocation(), "ios")

    assertFailsWith<SkillBillRuntimeException> {
      overlay.applyOverlay(request(listOf(first, second)))
    }.also { assertEquals(ExternalAddonFailureCode.OVERLAY, it.code) }
  }

  @Test
  fun `same skill-dir and pointer name across different platforms does not false-collide`() {
    platformPacksRoot = Files.createDirectories(work.resolve("platform-packs"))
    seedPackWithSharedDir("ios", ".swift")
    seedPackWithSharedDir("kotlin", ".kt")
    val iosSource = seedSharedDirExternalSource("ios", "alpha", "alpha-review.md")
    val kotlinSource = seedSharedDirExternalSource("kotlin", "beta", "beta-review.md")

    val result = overlay.applyOverlay(request(listOf(iosSource, kotlinSource)))

    assertEquals(listOf("ios", "kotlin"), result.appliedSources.map { it.platform })
    assertTrue(Files.isRegularFile(platformPacksRoot.resolve("ios/addons/alpha-review.md")))
    assertTrue(Files.isRegularFile(platformPacksRoot.resolve("kotlin/addons/beta-review.md")))
  }

  @Test
  fun `missing source md loud-fails`() {
    seedIosPack(packOwnedAddon = null)
    val sourceDir = Files.createDirectories(work.resolve("src/ios"))
    Files.writeString(
      sourceDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        code-review/bill-ios-code-review:
          - slug: acme
            entrypoint: acme-review.md
      pointers:
        code-review/bill-ios-code-review:
          - name: acme-review.md
            target: acme-review.md
      """.trimIndent() + "\n",
    )

    assertFailsWith<SkillBillRuntimeException> {
      overlay.applyOverlay(request(listOf(ExternalAddonSource(sourceDir.toFileLocation(), "ios"))))
    }.also { assertEquals(ExternalAddonFailureCode.OVERLAY, it.code) }
  }

  @Test
  fun `non-installed platform is skipped with a warning`() {
    seedIosPack(packOwnedAddon = null)
    val source = seedExternalSource("android", "acme", listOf("acme-review.md"))

    val result = overlay.applyOverlay(request(listOf(source)))

    assertFalse(result.touched)
    assertEquals(1, result.skippedSources.size)
    assertEquals("android", result.skippedSources[0].platform)
    assertContains(result.skippedSources[0].reason, "not installed")
  }

  @Test
  fun `multi-source ordering preserves declared config order`() {
    seedIosPack(packOwnedAddon = null)
    seedKotlinPack()
    val iosSource = seedExternalSource("ios", "acme", listOf("acme-review.md"))
    val kotlinSource = seedExternalSource("kotlin", "kmojo", listOf("kmojo-review.md"))

    val result = overlay.applyOverlay(request(listOf(iosSource, kotlinSource)))

    assertEquals(listOf("ios", "kotlin"), result.appliedSources.map { it.platform })
  }

  @Test
  fun `re-applying overlay is idempotent and preserves addon files`() {
    seedIosPack(packOwnedAddon = null)
    val source = seedExternalSource("ios", "acme", listOf("acme-review.md"))

    overlay.applyOverlay(request(listOf(source)))
    val firstManifest = Files.readString(platformPacksRoot.resolve("ios/platform.yaml"))

    val second = overlay.applyOverlay(request(listOf(source)))
    val secondManifest = Files.readString(platformPacksRoot.resolve("ios/platform.yaml"))

    assertTrue(Files.isRegularFile(platformPacksRoot.resolve("ios/addons/acme-review.md")))
    assertEquals(firstManifest, secondManifest, "Idempotent re-apply must not duplicate entries.")
    assertTrue(second.touched)
  }

  @Test
  fun `clean analog wipes addons then overlay reconstitutes them`() {
    seedIosPack(packOwnedAddon = null)
    val source = seedExternalSource("ios", "acme", listOf("acme-review.md"))
    overlay.applyOverlay(request(listOf(source)))

    val addonsDir = platformPacksRoot.resolve("ios/addons")
    Files.walk(addonsDir).use { stream ->
      stream.filter { it != addonsDir && Files.isRegularFile(it) }.forEach(Files::deleteIfExists)
    }
    assertFalse(Files.exists(addonsDir.resolve("acme-review.md")))

    overlay.applyOverlay(request(listOf(source)))

    assertTrue(Files.isRegularFile(addonsDir.resolve("acme-review.md")))
  }

  @Test
  fun `atomicity failing second source leaves first source not applied`() {
    seedIosPack(packOwnedAddon = null)
    val good = seedExternalSource("ios", "acme", listOf("acme-review.md"))
    val badDir = Files.createDirectories(work.resolve("bad/ios"))
    Files.writeString(
      badDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        code-review/bill-ios-code-review:
          - slug: broken
            entrypoint: broken.md
      pointers:
        code-review/bill-ios-code-review:
          - name: broken.md
            target: broken.md
      """.trimIndent() + "\n",
    )
    val bad = ExternalAddonSource(badDir.toFileLocation(), "ios")

    assertFailsWith<SkillBillRuntimeException> {
      overlay.applyOverlay(request(listOf(good, bad)))
    }.also { assertEquals(ExternalAddonFailureCode.OVERLAY, it.code) }

    assertFalse(Files.exists(platformPacksRoot.resolve("ios/addons/acme-review.md")))
    val manifest = Files.readString(platformPacksRoot.resolve("ios/platform.yaml"))
    assertFalse(manifest.contains("acme"))
  }

  @Test
  fun `empty config is a no-op`() {
    seedIosPack(packOwnedAddon = "offline")
    val before = Files.readString(platformPacksRoot.resolve("ios/platform.yaml"))

    val result = overlay.applyOverlay(request(emptyList()))

    assertFalse(result.touched)
    assertEquals(before, Files.readString(platformPacksRoot.resolve("ios/platform.yaml")))
  }

  @Test
  fun `nested addon target loud-fails`() {
    seedIosPack(packOwnedAddon = null)
    val sourceDir = Files.createDirectories(work.resolve("ext/ios-nested"))
    Files.writeString(sourceDir.resolve("nested.md"), "# nested body\n")
    Files.writeString(
      sourceDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        code-review/bill-ios-code-review:
          - slug: nested
            entrypoint: nested-pointer.md
      pointers:
        code-review/bill-ios-code-review:
          - name: nested-pointer.md
            target: platform-packs/ios/addons/sub/nested.md
      """.trimIndent() + "\n",
    )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        overlay.applyOverlay(request(listOf(ExternalAddonSource(sourceDir.toFileLocation(), "ios"))))
      }.also { assertEquals(ExternalAddonFailureCode.OVERLAY, it.code) }
    assertTrue(
      error.message.orEmpty().contains("flat file"),
      "Expected nested-target rejection, got: ${error.message}",
    )
  }

  @Test
  fun `fragment with unexpected pointer entry key loud-fails`() {
    seedIosPack(packOwnedAddon = null)
    val sourceDir = Files.createDirectories(work.resolve("ext/ios-extra"))
    Files.writeString(sourceDir.resolve("extra.md"), "# extra body\n")
    Files.writeString(
      sourceDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        code-review/bill-ios-code-review:
          - slug: extra
            entrypoint: extra.md
      pointers:
        code-review/bill-ios-code-review:
          - name: extra.md
            target: extra.md
            surprise: true
      """.trimIndent() + "\n",
    )

    assertFailsWith<SkillBillRuntimeException> {
      overlay.applyOverlay(request(listOf(ExternalAddonSource(sourceDir.toFileLocation(), "ios"))))
    }.also { assertEquals(ExternalAddonFailureCode.OVERLAY, it.code) }
  }

  @Test
  fun `fragment with malformed addon slug loud-fails`() {
    seedIosPack(packOwnedAddon = null)
    val sourceDir = Files.createDirectories(work.resolve("ext/ios-slug"))
    Files.writeString(sourceDir.resolve("bad-slug.md"), "# bad slug body\n")
    Files.writeString(
      sourceDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        code-review/bill-ios-code-review:
          - slug: BAD_SLUG
            entrypoint: bad-slug.md
      pointers:
        code-review/bill-ios-code-review:
          - name: bad-slug.md
            target: bad-slug.md
      """.trimIndent() + "\n",
    )

    assertFailsWith<SkillBillRuntimeException> {
      overlay.applyOverlay(request(listOf(ExternalAddonSource(sourceDir.toFileLocation(), "ios"))))
    }.also { assertEquals(ExternalAddonFailureCode.OVERLAY, it.code) }
  }

  private fun seedPackWithSharedDir(
    platform: String,
    strongSignal: String,
  ) {
    val packRoot = Files.createDirectories(platformPacksRoot.resolve(platform))
    val baseline = Files.createDirectories(packRoot.resolve("code-review/bill-shared-review"))
    Files.writeString(baseline.resolve("content.md"), "# $platform shared review\n")
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      platform: $platform
      contract_version: "1.8"
      display_name: "$platform"

      routing_signals:
        strong:
          - "$strongSignal"
        tie_breakers: []

      declared_code_review_areas: []

      declared_files:
        baseline: "code-review/bill-shared-review/content.md"
      """.trimIndent() + "\n",
    )
  }

  private fun seedSharedDirExternalSource(
    platform: String,
    slug: String,
    addonMd: String,
  ): ExternalAddonSource {
    val sourceDir = Files.createDirectories(work.resolve("ext/$platform-$slug"))
    Files.writeString(sourceDir.resolve(addonMd), "# $slug body\n")
    Files.writeString(
      sourceDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        code-review/bill-shared-review:
          - slug: $slug
            entrypoint: shared-review.md
      pointers:
        code-review/bill-shared-review:
          - name: shared-review.md
            target: $addonMd
      """.trimIndent() + "\n",
    )
    return ExternalAddonSource(sourceDir.toFileLocation(), platform)
  }

  private fun assertContains(
    actual: String,
    expected: String,
  ) {
    assertTrue(actual.contains(expected), "Expected '$actual' to contain '$expected'.")
  }

  private fun seedIosPack(packOwnedAddon: String?) {
    platformPacksRoot = Files.createDirectories(work.resolve("platform-packs"))
    val packRoot = Files.createDirectories(platformPacksRoot.resolve("ios"))
    val baseline = Files.createDirectories(packRoot.resolve("code-review/bill-ios-code-review"))
    Files.writeString(
      baseline.resolve("content.md"),
      """
      # iOS Code Review

      Review iOS Swift changes for correctness and framework usage.
      """.trimIndent() + "\n",
    )
    val manifest = StringBuilder()
    manifest.append(
      """
      platform: ios
      contract_version: "1.8"
      display_name: "iOS"

      routing_signals:
        strong:
          - ".swift"
        tie_breakers: []

      declared_code_review_areas: []

      declared_files:
        baseline: "code-review/bill-ios-code-review/content.md"
      """.trimIndent(),
    )
    if (packOwnedAddon != null) {
      Files.createDirectories(packRoot.resolve("addons"))
      Files.writeString(packRoot.resolve("addons/$packOwnedAddon-review.md"), "# $packOwnedAddon body\n")
      manifest.append(
        """

        addon_usage:
          code-review/bill-ios-code-review:
            - slug: $packOwnedAddon
              entrypoint: $packOwnedAddon-review.md

        pointers:
          code-review/bill-ios-code-review:
            - name: $packOwnedAddon-review.md
              target: platform-packs/ios/addons/$packOwnedAddon-review.md
        """.trimIndent(),
      )
    }
    Files.writeString(packRoot.resolve("platform.yaml"), manifest.toString() + "\n")
  }

  private fun seedKotlinPack() {
    val packRoot = Files.createDirectories(platformPacksRoot.resolve("kotlin"))
    val baseline = Files.createDirectories(packRoot.resolve("code-review/bill-kotlin-code-review"))
    Files.writeString(
      baseline.resolve("content.md"),
      """
      # Kotlin Code Review

      Review Kotlin changes for correctness.
      """.trimIndent() + "\n",
    )
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      platform: kotlin
      contract_version: "1.8"
      display_name: "Kotlin"

      routing_signals:
        strong:
          - ".kt"
        tie_breakers: []

      declared_code_review_areas: []

      declared_files:
        baseline: "code-review/bill-kotlin-code-review/content.md"
      """.trimIndent() + "\n",
    )
  }

  private fun seedExternalSource(
    platform: String,
    addonSlug: String,
    mdFiles: List<String>,
    target: String? = null,
    addonFiles: Map<String, String> = emptyMap(),
  ): ExternalAddonSource {
    val sourceDir = Files.createDirectories(work.resolve("ext/$platform-$addonSlug"))
    mdFiles.forEach { name ->
      Files.writeString(sourceDir.resolve(name), addonFiles[name] ?: "# $name body\n")
    }
    val entrypoint = mdFiles.first()
    val targetValue = target ?: entrypoint
    Files.writeString(
      sourceDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        code-review/bill-$platform-code-review:
          - slug: $addonSlug
            entrypoint: $entrypoint
      pointers:
        code-review/bill-$platform-code-review:
          - name: $entrypoint
            target: $targetValue
      """.trimIndent() + "\n",
    )
    return ExternalAddonSource(sourceDir.toFileLocation(), platform)
  }
}
