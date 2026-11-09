package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CliInstallReplayLastSelectionRuntimeTest {
  @Test
  fun `install replay last selection emits reusable fields without stale mcp path`() {
    val fixture = replayFixture()
    val staleMcpBin = fixture.home.resolve(".skill-bill/old-runtime/runtime-mcp/bin/runtime-mcp")
    writeInstallSelection(
      fixture.home,
      TestInstallSelection(
        selectedAgents = listOf("codex"),
        platformMode = "selected",
        selectedSlugs = listOf("kotlin"),
        telemetryLevel = "full",
        registerMcp = true,
        runtimeMcpBin = staleMcpBin.toString(),
      ),
    )

    val result =
      CliRuntime.run(
        replayLastSelectionArguments(fixture),
        CliRuntimeContext(
          userHome = fixture.home,
          environment = isolatedCliEnvironment(fixture.home),
        ),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "agent\tcodex\t${fixture.home.resolve(".agents/skills")}")
    assertContains(result.stdout, "platform-mode\tselected")
    assertContains(result.stdout, "platform\tkotlin")
    assertContains(result.stdout, "telemetry\tfull")
    assertContains(result.stdout, "installation-profile\tordinary")
    assertContains(result.stdout, "mcp\tregister")
    assertFalse(result.stdout.contains(staleMcpBin.toString()))
  }

  @Test
  fun `install replay last selection fails on stale selected platform slug`() {
    val fixture = replayFixture()
    writeInstallSelection(
      fixture.home,
      TestInstallSelection(
        selectedAgents = listOf("codex"),
        platformMode = "selected",
        selectedSlugs = listOf("retired"),
        telemetryLevel = "anonymous",
        registerMcp = false,
        runtimeMcpBin = null,
      ),
    )

    val result =
      CliRuntime.run(
        replayLastSelectionArguments(fixture),
        CliRuntimeContext(
          userHome = fixture.home,
          environment = isolatedCliEnvironment(fixture.home),
        ),
      )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "retired")
  }

  @Test
  fun `install replay last selection fails when shared record is missing`() {
    val fixture = replayFixture()

    val result =
      CliRuntime.run(
        replayLastSelectionArguments(fixture),
        CliRuntimeContext(
          userHome = fixture.home,
          environment = isolatedCliEnvironment(fixture.home),
        ),
      )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "No successful install selection is saved.")
  }

  @Test
  fun `install replay last selection fails when shared record is malformed`() {
    val fixture = replayFixture()
    Files.createDirectories(installSelectionPath(fixture.home).parent)
    Files.writeString(installSelectionPath(fixture.home), "[]")

    val result =
      CliRuntime.run(
        replayLastSelectionArguments(fixture),
        CliRuntimeContext(
          userHome = fixture.home,
          environment = isolatedCliEnvironment(fixture.home),
        ),
      )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "Install selection record")
    assertContains(result.stdout, "is malformed")
  }

  private fun replayFixture(): ReplayFixture {
    val home = Files.createTempDirectory("skillbill-cli-replay-selection-home")
    val repoRoot = Files.createTempDirectory("skillbill-cli-replay-selection-repo")
    val skillRoot = repoRoot.resolve("skills/bill-code-review")
    Files.createDirectories(skillRoot)
    Files.writeString(skillRoot.resolve("content.md"), testSkillContent())
    listOf("kotlin", "python").forEach { slug -> seedPlatformPack(repoRoot, slug) }
    return ReplayFixture(repoRoot, home)
  }

  private fun seedPlatformPack(
    repoRoot: Path,
    slug: String,
  ) {
    val codeReviewName = "bill-$slug-code-review"
    val qualityCheckName = "bill-$slug-code-check"
    val packRoot = repoRoot.resolve("platform-packs/$slug")
    Files.createDirectories(packRoot.resolve("code-review/$codeReviewName"))
    Files.createDirectories(packRoot.resolve("quality-check/$qualityCheckName"))
    Files.writeString(packRoot.resolve("platform.yaml"), platformManifest(slug, codeReviewName, qualityCheckName))
    Files.writeString(packRoot.resolve("code-review/$codeReviewName/content.md"), testSkillContent(codeReviewName))
    Files.writeString(
      packRoot.resolve("quality-check/$qualityCheckName/content.md"),
      testSkillContent(qualityCheckName),
    )
  }

  private fun replayLastSelectionArguments(fixture: ReplayFixture): List<String> =
    listOf(
      "install",
      "replay-last-selection",
      "--skills",
      fixture.repoRoot.resolve("skills").toString(),
      "--platform-packs",
      fixture.repoRoot.resolve("platform-packs").toString(),
    )

  private fun platformManifest(
    slug: String,
    codeReviewName: String,
    qualityCheckName: String,
  ): String =
    """
    |platform: "$slug"
    |contract_version: "1.8"
    |routing_signals:
    |  strong:
    |    - "$slug"
    |  tie_breakers: []
    |declared_code_review_areas: []
    |declared_files:
    |  baseline: "code-review/$codeReviewName/content.md"
    |  areas: {}
    |area_metadata: {}
    |display_name: "$slug"
    |declared_quality_check_file: "quality-check/$qualityCheckName/content.md"
    |
    """.trimMargin()

  private fun testSkillContent(skillName: String = "bill-code-review"): String =
    """
    |---
    |name: $skillName
    |description: Test skill.
    |---
    |
    |# $skillName
    |
    |Test body.
    |
    """.trimMargin()

  private fun writeInstallSelection(
    home: Path,
    selection: TestInstallSelection,
  ) {
    val mcpRuntimeBin = selection.runtimeMcpBin?.let { "\"$it\"" } ?: "null"
    Files.createDirectories(installSelectionPath(home).parent)
    Files.writeString(installSelectionPath(home), selection.toJson(mcpRuntimeBin))
  }

  private fun TestInstallSelection.toJson(mcpRuntimeBin: String): String =
    """
    |{
    |  "contract_version": "1.0",
    |  "selected_agents": [${selectedAgents.joinToString(",") { "\"$it\"" }}],
    |  "platform_pack_selection": {
    |    "mode": "$platformMode",
    |    "selected_slugs": [${selectedSlugs.joinToString(",") { "\"$it\"" }}]
    |  },
    |  "telemetry_level": "$telemetryLevel",
    |  "mcp_registration": {
    |    "register": $registerMcp,
    |    "runtime_mcp_bin": $mcpRuntimeBin
    |  }
    |}
    |
    """.trimMargin()

  private fun installSelectionPath(home: Path): Path = home.resolve(".skill-bill/install-selection.json")

  private data class ReplayFixture(
    val repoRoot: Path,
    val home: Path,
  )

  private data class TestInstallSelection(
    val selectedAgents: List<String>,
    val platformMode: String,
    val selectedSlugs: List<String>,
    val telemetryLevel: String,
    val registerMcp: Boolean,
    val runtimeMcpBin: String?,
  )
}
