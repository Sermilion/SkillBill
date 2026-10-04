package skillbill.infrastructure.skills.scaffold

import org.junit.jupiter.api.io.TempDir
import skillbill.contracts.JsonCodec
import skillbill.error.core.ExternalPlatformPackFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.skills.externalplatformpack.ExternalPlatformPackConfigKeys
import skillbill.infrastructure.skills.scaffold.rendering.renderContentBody
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELL_CONTRACT_VERSION
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.TemplateContext
import skillbill.install.model.ExternalAddonSource
import skillbill.ports.install.addon.model.ExternalAddonOverlayRequest
import skillbill.ports.repository.toFileLocation
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalPlatformPackOverlayPrecedenceTest {
  @TempDir
  lateinit var root: Path

  @Test
  fun `overlay writes the installed effective pack and leaves the author tree unchanged`() {
    val home = Files.createDirectories(root.resolve("home"))
    val repo = Files.createDirectories(root.resolve("repo"))
    val author = root.resolve("author/kotlin")
    val installedRoot = root.resolve("installed/platform-packs")
    writePack(repo.resolve("platform-packs/kotlin"), "BUNDLED_MARKER", includeArchitecture = true)
    writePack(author, "AUTHOR_MARKER", includeArchitecture = false)
    copyPack(author, installedRoot.resolve("kotlin"))
    val authorBytes = Files.readAllBytes(author.resolve("platform.yaml"))
    val authorContent = Files.readAllBytes(author.resolve("code-review/bill-kotlin-code-review/content.md"))
    register(home, author)
    val source = addonSource("kotlin", "code-review/bill-kotlin-code-review")

    val result =
      overlayPort().applyOverlay(
        ExternalAddonOverlayRequest(
          platformPacksRoot = installedRoot,
          sources = listOf(source),
          userHome = home,
          environment = mapOf(CONFIG_ENVIRONMENT_KEY to home.resolve("config.json").toString()),
          repoRoot = repo,
        ),
      )

    assertTrue(result.touched)
    assertTrue(Files.isRegularFile(installedRoot.resolve("kotlin/addons/acme-review.md")))
    assertEquals(authorBytes.toList(), Files.readAllBytes(author.resolve("platform.yaml")).toList())
    assertEquals(
      authorContent.toList(),
      Files.readAllBytes(author.resolve("code-review/bill-kotlin-code-review/content.md")).toList(),
    )
  }

  @Test
  fun `a consumer path that exists only on the bundled pack fails and is not copied`() {
    val home = Files.createDirectories(root.resolve("home"))
    val repo = Files.createDirectories(root.resolve("repo"))
    val author = root.resolve("author/kotlin")
    val installedRoot = root.resolve("installed/platform-packs")
    val bundledArea = repo.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review-architecture/content.md")
    writePack(repo.resolve("platform-packs/kotlin"), "BUNDLED_MARKER", includeArchitecture = true)
    writePack(author, "AUTHOR_MARKER", includeArchitecture = false)
    copyPack(repo.resolve("platform-packs/kotlin"), installedRoot.resolve("kotlin"))
    val bundledBefore = Files.readAllBytes(bundledArea)
    register(home, author)
    val source = addonSource("kotlin", "code-review/bill-kotlin-code-review-architecture")

    assertFailsWith<SkillBillRuntimeException> {
      overlayPort().applyOverlay(
        ExternalAddonOverlayRequest(
          platformPacksRoot = installedRoot,
          sources = listOf(source),
          userHome = home,
          environment = mapOf(CONFIG_ENVIRONMENT_KEY to home.resolve("config.json").toString()),
          repoRoot = repo,
        ),
      )
    }.also { assertEquals(ExternalPlatformPackFailureCode.OVERLAY, it.code) }

    assertEquals(bundledBefore.toList(), Files.readAllBytes(bundledArea).toList())
    assertFalse(Files.exists(installedRoot.resolve("kotlin/addons/acme-review.md")))
    assertFalse(Files.exists(author.resolve("addons/acme-review.md")))
  }

  private fun addonSource(
    platform: String,
    consumer: String,
  ): ExternalAddonSource {
    val sourceDir = Files.createDirectories(root.resolve("addon-$consumer".replace("/", "-")))
    Files.writeString(sourceDir.resolve("acme-review.md"), "# acme body\n")
    Files.writeString(
      sourceDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        $consumer:
          - slug: acme
            entrypoint: acme-review.md
      pointers:
        $consumer:
          - name: acme-review.md
            target: acme-review.md
      """.trimIndent() + "\n",
    )
    return ExternalAddonSource(sourceDir.toFileLocation(), platform)
  }

  private fun register(
    home: Path,
    pack: Path,
  ) {
    val config = home.resolve("config.json")
    Files.writeString(
      config,
      JsonCodec.mapToJsonString(
        mapOf(
          ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES to
            listOf(
              mapOf("path" to pack.toAbsolutePath().normalize().toString()),
            ),
        ),
      ) + "\n",
    )
  }

  private fun copyPack(
    source: Path,
    destination: Path,
  ) {
    Files.walk(source).use { paths ->
      paths.forEach { path ->
        val target = destination.resolve(source.relativize(path))
        if (Files.isDirectory(path)) {
          Files.createDirectories(target)
        } else {
          Files.createDirectories(target.parent)
          Files.copy(path, target)
        }
      }
    }
  }

  private fun writePack(
    packRoot: Path,
    marker: String,
    includeArchitecture: Boolean,
  ) {
    val baseline = packRoot.resolve("code-review/bill-kotlin-code-review/content.md")
    Files.createDirectories(baseline.parent)
    Files.writeString(baseline, reviewBody("bill-kotlin-code-review", marker))
    val areas =
      if (!includeArchitecture) {
        "declared_code_review_areas: []\n"
      } else {
        val area = packRoot.resolve("code-review/bill-kotlin-code-review-architecture/content.md")
        Files.createDirectories(area.parent)
        Files.writeString(area, reviewBody("bill-kotlin-code-review-architecture", "BUNDLED_AREA_MARKER"))
        "declared_code_review_areas:\n  - architecture\n"
      }
    val areaFile =
      if (includeArchitecture) {
        "  areas:\n    architecture: \"code-review/bill-kotlin-code-review-architecture/content.md\"\n"
      } else {
        ""
      }
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      buildString {
        appendLine("platform: kotlin")
        appendLine("contract_version: \"$SHELL_CONTRACT_VERSION\"")
        appendLine("display_name: \"Kotlin\"")
        appendLine("routing_signals:")
        appendLine("  strong:")
        appendLine("    - \".kt\"")
        appendLine("  tie_breakers: []")
        append(areas)
        appendLine("declared_files:")
        appendLine("  baseline: \"code-review/bill-kotlin-code-review/content.md\"")
        append(areaFile)
        appendLine("validation_gate:")
        appendLine("  full_gate_command: [\"overlay-gate\"]")
        appendLine("  cache_bypassing_full_gate_command: [\"overlay-gate-nocache\"]")
        appendLine("  collect_all_full_gate_command: [\"overlay-gate\"]")
        appendLine("  cache_bypassing_collect_all_full_gate_command: [\"overlay-gate-nocache\"]")
        appendLine("  build_command: [\"overlay-build\"]")
        appendLine("  cache_bypassing_build_command: [\"overlay-build-nocache\"]")
        appendLine("  findings:")
        appendLine("    format: junit_xml")
        appendLine("    artifact_globs: [\"build/test-results/**/*.xml\"]")
        appendLine("    compiler_diagnostics:")
        appendLine("      format: gradle_kotlin_compiler_stdout")
      },
    )
  }

  private fun reviewBody(
    skillName: String,
    marker: String,
  ): String = renderContentBody(TemplateContext(skillName, "code-review", "kotlin", "", "Kotlin"), marker)
}
