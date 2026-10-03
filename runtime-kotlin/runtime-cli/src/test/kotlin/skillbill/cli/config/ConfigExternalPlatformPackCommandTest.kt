package skillbill.cli.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonCodec
import skillbill.contracts.config.ExternalPlatformPackTelemetryPayloadKeys
import skillbill.infrastructure.skills.externalplatformpack.ExternalPlatformPackConfigKeys
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY

class ConfigExternalPlatformPackCommandTest {
  @Test
  fun `resolve is read-only and names the external slug source kind and shadowed bundled slug`() {
    val home = Files.createTempDirectory("skillbill-pack-resolve-home")
    val repo = Files.createTempDirectory("skillbill-pack-resolve-repo")
    val external = Files.createTempDirectory("skillbill-pack-resolve-external").resolve("kotlin")
    val config = home.resolve("pinned-config.json")
    writePack(repo.resolve("platform-packs/kotlin"), "BUNDLED_BASELINE_MARKER", "bundled-gate-collect")
    writePack(external, "EXTERNAL_BASELINE_MARKER", "external-gate-collect")
    val before = writeConfig(config, external)
    val result =
      CliRuntime.run(
        listOf("config", "resolve-external-platform-packs", "--repo-root", repo.toString()),
        CliRuntimeContext(
          userHome = home,
          repositoryRoot = repo,
          environment = mapOf(CONFIG_ENVIRONMENT_KEY to config.toString()),
        ),
      )
    assertEquals(0, result.exitCode, result.stdout)
    assertTrue(result.stdout.contains("kotlin\texternal\tkotlin\t"))
    assertEquals(before, Files.readString(config))
  }

  @Test
  fun `register dry-run and unregister of a missing registration do not delete the author directory`() {
    val home = Files.createTempDirectory("skillbill-pack-register-home")
    val repo = Files.createTempDirectory("skillbill-pack-register-repo")
    val author = Files.createTempDirectory("skillbill-pack-register-author").resolve("kotlin")
    val config = home.resolve("pinned-config.json")
    writePack(repo.resolve("platform-packs/kotlin"), "BUNDLED_BASELINE_MARKER", "bundled-gate-collect")
    writePack(author, "EXTERNAL_BASELINE_MARKER", "external-gate-collect")
    Files.writeString(author.resolve("keep.txt"), "author-bytes")
    val before = writeConfig(config)
    val context =
      CliRuntimeContext(
        userHome = home,
        repositoryRoot = repo,
        environment = mapOf(CONFIG_ENVIRONMENT_KEY to config.toString()),
      )
    val dryRun =
      CliRuntime.run(
        listOf(
          "config",
          "register-external-platform-pack",
          "--path",
          author.toString(),
          "--repo-root",
          repo.toString(),
          "--dry-run",
        ),
        context,
      )
    assertEquals(0, dryRun.exitCode, dryRun.stdout)
    assertTrue(dryRun.stdout.contains("Would register"))
    assertEquals(before, Files.readString(config))
    assertEquals("author-bytes", Files.readString(author.resolve("keep.txt")))

    val unregistered =
      CliRuntime.run(
        listOf("config", "unregister-external-platform-pack", "--path", author.toString()),
        context,
      )
    assertEquals(0, unregistered.exitCode, unregistered.stdout)
    assertEquals("author-bytes", Files.readString(author.resolve("keep.txt")))
    assertTrue(Files.isDirectory(author))
  }

  @Test
  fun `resolve failure payload keeps the error type and omits the pack path`() {
    val home = Files.createTempDirectory("skillbill-pack-resolve-fail-home")
    val repo = Files.createTempDirectory("skillbill-pack-resolve-fail-repo")
    val secret = Files.createTempDirectory("skillbill-pack-resolve-fail-secret").resolve("kotlin")
    val config = home.resolve("pinned-config.json")
    writePack(repo.resolve("platform-packs/kotlin"), "BUNDLED_BASELINE_MARKER", "bundled-gate-collect")
    Files.createDirectories(secret)
    Files.writeString(secret.resolve("platform.yaml"), "platform: [\n")
    writeConfig(config, secret)
    val guidance = "Open the private README before retrying."
    Files.writeString(secret.resolve("README.md"), guidance)

    val result =
      CliRuntime.run(
        listOf("config", "resolve-external-platform-packs", "--repo-root", repo.toString()),
        CliRuntimeContext(
          userHome = home,
          repositoryRoot = repo,
          environment = mapOf(CONFIG_ENVIRONMENT_KEY to config.toString()),
        ),
      )

    assertEquals(1, result.exitCode)
    val payload = requireNotNull(result.payload)
    assertEquals(
      "ManifestFailureCode.INVALID_MANIFEST_SCHEMA",
      payload[ExternalPlatformPackTelemetryPayloadKeys.ERROR_TYPE],
    )
    assertEquals("external", payload[ExternalPlatformPackTelemetryPayloadKeys.SOURCE_KIND])
    val serialized = payload.values.joinToString(" ")
    assertFalse(secret.toString() in serialized)
    assertFalse(guidance in serialized)
  }

  private fun writeConfig(
    config: Path,
    vararg packs: Path,
  ): String {
    val text =
      JsonCodec.mapToJsonString(
        mapOf(
          "install_id" to "stable-id",
          "external_addon_sources" to emptyList<Any>(),
          ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES to
            packs.map { pack ->
              mapOf("path" to pack.toAbsolutePath().normalize().toString())
            },
        ),
      ) + "\n"
    Files.writeString(config, text)
    return text
  }

  private fun writePack(
    packRoot: Path,
    marker: String,
    gate: String,
  ) {
    val baseline = packRoot.resolve("code-review/bill-kotlin-code-review/content.md")
    Files.createDirectories(baseline.parent)
    Files.writeString(baseline, reviewBody(marker))
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
      validation_gate:
        full_gate_command: ["$gate"]
        cache_bypassing_full_gate_command: ["$gate-nocache"]
        collect_all_full_gate_command: ["$gate"]
        cache_bypassing_collect_all_full_gate_command: ["$gate-nocache"]
        build_command: ["$gate-build"]
        cache_bypassing_build_command: ["$gate-build-nocache"]
        findings:
          format: junit_xml
          artifact_globs: ["build/test-results/**/*.xml"]
          compiler_diagnostics:
            format: gradle_kotlin_compiler_stdout
      """.trimIndent() + "\n",
    )
  }

  private fun reviewBody(marker: String): String =
    """
    ---
    name: bill-kotlin-code-review
    description: $marker
    ---

    # Review Content

    ## Classification Rules

    $marker
    - If the platform's strong signals dominate, select this pack.

    ## Diff-Signal Routing Table

    - Module boundaries -> `architecture` specialist.

    ## Mixed Diffs

    Keep the baseline specialists for the whole review.

    ## Finding Discipline

    Keep this section limited to platform-specific finding preconditions.
    """.trimIndent() + "\n"
}
