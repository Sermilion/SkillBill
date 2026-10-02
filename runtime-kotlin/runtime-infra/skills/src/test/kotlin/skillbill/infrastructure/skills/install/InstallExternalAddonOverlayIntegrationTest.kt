
package skillbill.infrastructure.skills.install

import skillbill.infrastructure.skills.install.nativeagent.installNativeAgentCompositionContext
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderRequest
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentOperations
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.scaffold.overlayPort
import skillbill.install.model.ExternalAddonSource
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.install.model.SupportedAgent
import skillbill.model.toPath
import skillbill.ports.install.addon.ExternalAddonOverlayPort
import skillbill.ports.install.addon.model.ExternalAddonOverlayRequest
import skillbill.ports.repository.toFileLocation
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallExternalAddonOverlayIntegrationTest : InstallApplyTestSupport() {
  @Test
  fun `overlay then staging inlines external addon content into installed skills cache`() {
    val fixture = setupIosFixture()
    val external = seedExternalSource(fixture, "acme", listOf("acme-review.md"))
    runOverlay(fixture, listOf(external))

    val plan = planInstallForTest(fixture.request(selectedPlatforms = setOf("ios")))
    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status, "apply failures: ${result.failures}")
    val staging = result.skills.first { it.skillName == PACK_SIDECAR_PARENT_SKILL }.staging
    val stagingDir =
      staging.stagingDir
        ?: error("ios code-review skill was not staged")
    val acmeRendered =
      staging.renderedPointerFiles.firstOrNull { it.fileName == "acme-review.md" }
        ?: error(
          "acme-review.md must appear among the RENDERED pointer files in $stagingDir; " +
            "got: ${staging.renderedPointerFiles}",
        )
    assertTrue(
      Files.isRegularFile(acmeRendered.toPath()),
      "external addon must be inlined as a rendered pointer file under the install cache",
    )
    assertTrue(
      acmeRendered.toPath().toAbsolutePath().normalize().startsWith(
        fixture.home.resolve(".skill-bill/installed-skills"),
      ),
      "rendered addon pointer must live under the install cache, not the source tree ($acmeRendered)",
    )
    assertTrue(
      Files.isRegularFile(stagingDir.resolve("offline-review.md").toPath()),
      "pack-owned addon must remain inlined",
    )
    val renderedAddonBody = Files.readString(acmeRendered.toPath())
    assertContains(
      renderedAddonBody,
      "acme review body",
      "external addon content must appear in the RENDERED pointer file, not just the source .md",
    )
  }

  @Test
  fun `clean analog wipes addons then overlay reconstitutes before staging`() {
    val fixture = setupIosFixture()
    val external = seedExternalSource(fixture, "acme", listOf("acme-review.md"))
    runOverlay(fixture, listOf(external))

    val addonsDir = fixture.repoRoot.resolve("platform-packs/ios/addons")
    Files.walk(addonsDir).use { stream ->
      stream.filter { it != addonsDir && Files.isRegularFile(it) }
        .filter { path -> path.fileName.toString().startsWith("acme-") }
        .forEach(Files::deleteIfExists)
    }
    assertFalse(Files.exists(addonsDir.resolve("acme-review.md")))

    runOverlay(fixture, listOf(external))
    assertTrue(Files.isRegularFile(addonsDir.resolve("acme-review.md")), "overlay must reconstitute wiped addon files")

    val plan = planInstallForTest(fixture.request(selectedPlatforms = setOf("ios")))
    val result = applyInstallForTest(plan)
    assertEquals(InstallApplyStatus.SUCCESS, result.status, "apply failures: ${result.failures}")
  }

  @Test
  fun `zero config parity omits overlay and stages identically to baseline`() {
    val baseline = setupIosFixture()
    val baselinePlan = planInstallForTest(baseline.request(selectedPlatforms = setOf("ios")))
    val baselineResult = applyInstallForTest(baselinePlan)
    assertEquals(InstallApplyStatus.SUCCESS, baselineResult.status)

    val overlayed = setupIosFixture()
    runOverlay(overlayed, emptyList())
    val overlayPlan = planInstallForTest(overlayed.request(selectedPlatforms = setOf("ios")))
    val overlayResult = applyInstallForTest(overlayPlan)
    assertEquals(InstallApplyStatus.SUCCESS, overlayResult.status)

    val baselineContent = stagedSkillBody(baselineResult, "bill-ios-code-review")
    val overlayContent = stagedSkillBody(overlayResult, "bill-ios-code-review")
    assertEquals(baselineContent, overlayContent, "empty external config must be a byte-identical no-op")
  }

  @Test
  fun `overlaid external add-on reaches rendered agents without mutating upstream content md`() {
    val fixture = setupIosFixture()
    val packRoot = fixture.repoRoot.resolve("platform-packs/ios")
    val contentFiles =
      Files.walk(packRoot).use { stream ->
        stream.filter { path -> path.fileName.toString() == "content.md" }.toList()
      }
    val beforeBytes = contentFiles.associateWith { path -> Files.readAllBytes(path) }
    val marker = "external-overlay-native-agent-marker"
    val external =
      seedExternalSource(
        fixture,
        "acme",
        listOf("acme-review.md"),
        skillRelativeDir = "code-review/bill-ios-code-review-architecture",
        body = marker,
      )
    runOverlay(fixture, listOf(external))
    NativeAgentProvider.entries.forEach { provider ->
      val result =
        NativeAgentOperations.renderInstallArtifacts(
          NativeAgentInstallRenderRequest(
            platformPacksRoot = fixture.repoRoot.resolve("platform-packs"),
            skillsRoot = fixture.repoRoot.resolve("skills"),
            selectedPlatforms = listOf("ios"),
            provider = provider,
            home = fixture.home,
            compositionContext = installNativeAgentCompositionContext(),
          ),
        )
      val rendered =
        Files.readString(
          result.generatedFiles.single { path ->
            path.fileName.toString() == provider.fileName("bill-ios-code-review-architecture")
          },
        )
      assertTrue(marker in rendered, "${provider.directoryName} missing overlaid add-on content")
    }
    contentFiles.forEach { path ->
      assertTrue(
        beforeBytes.getValue(path).contentEquals(Files.readAllBytes(path)),
        "overlay plus render mutated $path",
      )
    }
  }

  private fun stagedSkillBody(
    result: InstallApplyResult,
    name: String,
  ): String {
    val stagingDir =
      result.skills.first { it.skillName == PACK_SIDECAR_PARENT_SKILL }.staging.stagingDir
        ?: error("$PACK_SIDECAR_PARENT_SKILL was not staged")
    return Files.readString(stagingDir.resolve("$name.md").toPath())
  }

  private fun setupIosFixture(): ApplyFixture {
    val fixture = setupApplyFixture()
    val repoRoot = fixture.repoRoot
    val packRoot = repoRoot.resolve("platform-packs/ios")
    seedPlatformPack(repoRoot, "ios")
    val addonsDir = Files.createDirectories(packRoot.resolve("addons"))
    Files.writeString(addonsDir.resolve("offline-review.md"), "# offline review body\n")
    val manifest = packRoot.resolve("platform.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest).replace(
        "  code-review/bill-ios-code-review: []",
        "  code-review/bill-ios-code-review:\n" +
          "    - name: offline-review.md\n" +
          "      target: platform-packs/ios/addons/offline-review.md",
      ) +
        """
        addon_usage:
          code-review/bill-ios-code-review:
            - slug: offline
              entrypoint: offline-review.md
        """.trimIndent() + "\n",
    )
    return fixture
  }

  private fun seedExternalSource(
    fixture: ApplyFixture,
    slug: String,
    mdFiles: List<String>,
    skillRelativeDir: String = "code-review/bill-ios-code-review",
    body: String? = null,
  ): ExternalAddonSource {
    val sourceDir = Files.createDirectories(fixture.home.resolve("private/ios-$slug"))
    mdFiles.forEach { name ->
      Files.writeString(sourceDir.resolve(name), body ?: "# $slug review body\n")
    }
    val entrypoint = mdFiles.first()
    Files.writeString(
      sourceDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        $skillRelativeDir:
          - slug: $slug
            entrypoint: $entrypoint
      pointers:
        $skillRelativeDir:
          - name: $entrypoint
            target: $entrypoint
      """.trimIndent() + "\n",
    )
    return ExternalAddonSource(sourceDir.toFileLocation(), "ios")
  }

  private fun runOverlay(
    fixture: ApplyFixture,
    sources: List<ExternalAddonSource>,
  ) {
    val port: ExternalAddonOverlayPort = overlayPort()
    port.applyOverlay(
      ExternalAddonOverlayRequest(
        platformPacksRoot = fixture.repoRoot.resolve("platform-packs"),
        sources = sources,
      ),
    )
  }

  private fun assertContains(
    actual: String,
    expected: String,
    message: String? = null,
  ) {
    assertTrue(
      actual.contains(expected),
      (message ?: "Expected staged content to contain '$expected'.") + " Got: $actual",
    )
  }

  private fun ApplyFixture.request(selectedPlatforms: Set<String>): InstallPlanRequest =
    request(
      selectedPlatforms = selectedPlatforms,
      agents = setOf(SupportedAgent.CODEX),
    )
}
