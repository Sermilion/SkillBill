package skillbill.infrastructure.skills.install

import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.SupportedAgent
import skillbill.ports.projectstore.FixedProjectStorePort
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallApplyRepoLocalConfigTest : InstallApplyTestSupport() {
  @Test
  fun `apply scaffolds default repo-local config and anchored gitignore entry`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan = planInstallForTest(fixture.request(agents = setOf(SupportedAgent.CODEX)))
    val projectStore = newProjectStore()

    val result = applyInstallForTest(plan, projectStore = projectStore)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val configPath = projectStore.resolve(fixture.repoRoot).configPath
    assertTrue(Files.exists(configPath), "install should scaffold the repo-local config in the project store")
    assertFalse(Files.exists(fixture.repoRoot.resolve(".skill-bill/config.yaml")))
    val configContent = Files.readString(configPath)
    assertContains(configContent, "spec_type: local")
    assertFalse(configContent.contains("code_review_parallel_agent"))
    val gitignoreContent = Files.readString(fixture.repoRoot.resolve(".gitignore"))
    assertEquals(1, gitignoreContent.lines().count { line -> line.trim() == ".skill-bill/**" })
    assertEquals(1, gitignoreContent.lines().count { line -> line.trim() == "!.skill-bill/config.yaml" })
    assertSinglePhasesExceptions(gitignoreContent)
  }

  @Test
  fun `reapply is idempotent and preserves a user-edited config`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan = planInstallForTest(fixture.request(agents = setOf(SupportedAgent.CODEX)))
    val projectStore = newProjectStore()
    applyInstallForTest(plan, projectStore = projectStore)
    val configPath = projectStore.resolve(fixture.repoRoot).configPath
    val userEdited = "spec_type: linear\ncode_review_parallel_agent: claude\n"
    Files.writeString(configPath, userEdited)

    val second = applyInstallForTest(plan, projectStore = projectStore)

    assertEquals(InstallApplyStatus.SUCCESS, second.status)
    assertEquals(userEdited, Files.readString(configPath), "re-install must not clobber a user-edited config")
    val gitignoreContent = Files.readString(fixture.repoRoot.resolve(".gitignore"))
    assertEquals(
      1,
      gitignoreContent.lines().count { line -> line.trim() == ".skill-bill/**" },
      "re-install must not duplicate the .gitignore entry",
    )
    assertEquals(
      1,
      gitignoreContent.lines().count { line -> line.trim() == "!.skill-bill/config.yaml" },
      "re-install must not duplicate the config exception",
    )
    assertSinglePhasesExceptions(gitignoreContent)
  }

  @Test
  fun `apply leaves the store config absent when a legacy checkout config exists`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val legacyConfig = fixture.repoRoot.resolve(".skill-bill/config.yaml")
    Files.createDirectories(legacyConfig.parent)
    val legacyContent = "spec_type: linear\n"
    Files.writeString(legacyConfig, legacyContent)
    val plan = planInstallForTest(fixture.request(agents = setOf(SupportedAgent.CODEX)))
    val projectStore = newProjectStore()

    val result = applyInstallForTest(plan, projectStore = projectStore)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertFalse(
      Files.exists(projectStore.resolve(fixture.repoRoot).configPath),
      "install must not scaffold defaults that would shadow the legacy config's one-time copy",
    )
    assertEquals(legacyContent, Files.readString(legacyConfig))
  }

  @Test
  fun `apply adds the phases exceptions to a gitignore that only carries the older two lines`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val gitignorePath = fixture.repoRoot.resolve(".gitignore")
    Files.writeString(gitignorePath, ".skill-bill/**\n!.skill-bill/config.yaml\n")
    val plan = planInstallForTest(fixture.request(agents = setOf(SupportedAgent.CODEX)))

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val gitignoreContent = Files.readString(gitignorePath)
    assertEquals(1, gitignoreContent.lines().count { line -> line.trim() == ".skill-bill/**" })
    assertEquals(1, gitignoreContent.lines().count { line -> line.trim() == "!.skill-bill/config.yaml" })
    assertSinglePhasesExceptions(gitignoreContent)
  }

  @Test
  fun `apply preserves an existing user gitignore and appends the anchored entry once`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val gitignorePath = fixture.repoRoot.resolve(".gitignore")
    Files.writeString(gitignorePath, "build/\n*.log\n")
    val plan = planInstallForTest(fixture.request(agents = setOf(SupportedAgent.CODEX)))

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val gitignoreContent = Files.readString(gitignorePath)
    assertContains(gitignoreContent, "build/")
    assertContains(gitignoreContent, "*.log")
    assertEquals(1, gitignoreContent.lines().count { line -> line.trim() == ".skill-bill/**" })
    assertEquals(1, gitignoreContent.lines().count { line -> line.trim() == "!.skill-bill/config.yaml" })
    assertSinglePhasesExceptions(gitignoreContent)
  }

  private fun newProjectStore(): FixedProjectStorePort =
    FixedProjectStorePort(Files.createTempDirectory("skillbill-install-apply-store").also(tempDirs::add))

  private fun assertSinglePhasesExceptions(gitignoreContent: String) {
    listOf("!.skill-bill/phases/", "!.skill-bill/phases/**").forEach { exception ->
      assertEquals(1, gitignoreContent.lines().count { line -> line.trim() == exception }, exception)
    }
  }
}
