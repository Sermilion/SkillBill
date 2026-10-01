package skillbill.infrastructure.skills.install

import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.install.model.SupportedAgent
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallApplyPlatformPackViewTest : InstallApplyTestSupport() {
  @Test
  fun `apply materializes selected platform pack manifests under each agent skill root`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan =
      planInstallForTest(
        fixture.request(
          selectedPlatforms = setOf("kotlin"),
          agents = setOf(SupportedAgent.CODEX),
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val agentRoot = fixture.home.resolve("agent-skill-targets/codex")
    val packRoot = agentRoot.resolve("platform-packs/kotlin")
    assertTrue(Files.isRegularFile(agentRoot.resolve("platform-packs/.skill-bill-install")))
    assertTrue(Files.isRegularFile(packRoot.resolve("platform.yaml")), "installed pack manifest is missing")
    assertEquals("kotlin", loadPlatformManifest(packRoot).slug)
    assertFalse(Files.exists(agentRoot.resolve("bill-kotlin-code-review"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(packRoot.resolve("code-review/bill-kotlin-code-review"), LinkOption.NOFOLLOW_LINKS))
    assertTrue(Files.isRegularFile(agentRoot.resolve("$PACK_SIDECAR_PARENT_SKILL/bill-kotlin-code-review.md")))
    assertFalse(Files.exists(packRoot.resolve("quality-check"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(
      Files.exists(agentRoot.resolve("platform-packs/kmp/platform.yaml"), LinkOption.NOFOLLOW_LINKS),
      "unselected pack manifest must not be discoverable",
    )
  }

  @Test
  fun `apply does not stage leftover pack quality-check files under skill-bill`() {
    val fixture = setupApplyFixture()
    val qualityCheckDir = fixture.repoRoot.resolve("platform-packs/kotlin/quality-check/bill-kotlin-code-check")
    Files.createDirectories(qualityCheckDir)
    Files.writeString(
      qualityCheckDir.resolve("content.md"),
      """
      ---
      name: bill-kotlin-code-check
      internal-for: skill-bill
      ---
      # leftover
      """.trimIndent(),
    )
    val manifestPath = fixture.repoRoot.resolve("platform-packs/kotlin/platform.yaml")
    Files.writeString(
      manifestPath,
      Files.readString(manifestPath) +
        "\ndeclared_quality_check_file: quality-check/bill-kotlin-code-check/content.md\n",
    )
    Files.createDirectories(fixture.home.resolve(".codex"))

    val plan =
      planInstallForTest(
        fixture.request(
          selectedPlatforms = setOf("kotlin"),
          agents = setOf(SupportedAgent.CODEX),
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val agentRoot = fixture.home.resolve("agent-skill-targets/codex")
    assertFalse(
      Files.exists(
        agentRoot.resolve("$PACK_SIDECAR_PARENT_SKILL/bill-kotlin-code-check.md"),
        LinkOption.NOFOLLOW_LINKS,
      ),
      "leftover pack checker must not install as $PACK_SIDECAR_PARENT_SKILL sidecar",
    )
  }

  @Test
  fun `apply excludes pack root agent history while preserving pack manifest and addons`() {
    val fixture = setupApplyFixture()
    seedPlatformPack(fixture.repoRoot, "ios")
    seedPlatformPack(fixture.repoRoot, "python")
    listOf("ios", "python").forEach { slug ->
      val packRoot = fixture.repoRoot.resolve("platform-packs/$slug")
      Files.createDirectories(packRoot.resolve("agent"))
      Files.writeString(packRoot.resolve("agent/history.md"), "$slug history")
      Files.writeString(packRoot.resolve("agent/decisions.md"), "$slug decisions")
      Files.createDirectories(packRoot.resolve("addons"))
      Files.writeString(packRoot.resolve("addons/$slug-addon.md"), "$slug addon")
    }
    Files.createDirectories(fixture.home.resolve(".codex"))

    val plan =
      planInstallForTest(
        fixture.request(
          selectedPlatforms = setOf("ios", "python"),
          agents = setOf(SupportedAgent.CODEX),
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    listOf("ios", "python").forEach { slug ->
      val packView = fixture.home.resolve("agent-skill-targets/codex/platform-packs/$slug")
      assertFalse(Files.exists(packView.resolve("agent/history.md"), LinkOption.NOFOLLOW_LINKS))
      assertFalse(Files.exists(packView.resolve("agent/decisions.md"), LinkOption.NOFOLLOW_LINKS))
      assertTrue(Files.isRegularFile(packView.resolve("platform.yaml")))
      assertTrue(Files.isRegularFile(packView.resolve("addons/$slug-addon.md")))
    }
  }

  @Test
  fun `apply succeeds when a selected platform pack skill declares internal-for and stages as a sidecar`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))

    val plan =
      planInstallForTest(
        fixture.request(
          selectedPlatforms = setOf("kotlin"),
          agents = setOf(SupportedAgent.CODEX),
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val agentRoot = fixture.home.resolve("agent-skill-targets/codex")
    val internalPackView = agentRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review")
    assertFalse(
      Files.exists(internalPackView, LinkOption.NOFOLLOW_LINKS),
      "internal pack skill must not materialize a standalone platform-packs symlink",
    )
    assertTrue(
      Files.isRegularFile(agentRoot.resolve("$PACK_SIDECAR_PARENT_SKILL/bill-kotlin-code-review.md")),
      "internal pack skill must stage as a sibling sidecar of its parent's installed directory",
    )
  }
}
