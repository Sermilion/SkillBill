package skillbill.install

import skillbill.infrastructure.skills.install.plan.discoverBaseSkills
import skillbill.infrastructure.skills.install.staging.stageInstalledSkill
import skillbill.model.toPath
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DispatcherSkillInstallRepoTest {
  private val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    tempDirs.reversed().forEach { dir ->
      if (Files.exists(dir)) {
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
      }
    }
  }

  @Test
  fun `skill-bill installs beside every old listed skill with the feature class sidecars and add-on`() {
    val repoRoot = repoRootFromTest()
    val skillsRoot = repoRoot.resolve("skills")
    val home = Files.createTempDirectory("skillbill-dispatcher-install").also(tempDirs::add)
    val oldListedSkills =
      Files.list(skillsRoot).use { dirs ->
        dirs
          .filter { dir -> dir.name.startsWith("bill-") && Files.isRegularFile(dir.resolve("content.md")) }
          .map { dir -> dir.name }
          .toList()
      }

    val installed = discoverBaseSkills(skillsRoot).map { skill -> skill.name }.toSet()

    assertEquals(oldListedSkills.toSet() + "skill-bill", installed)
    listOf("skill-bill", "bill-feature").forEach { skillName ->
      val staged = stageInstalledSkill(repoRoot, skillsRoot.resolve(skillName), home)
      listOf(
        "peak-hours-warner.md",
        "shell-ceremony.md",
        "telemetry-contract.md",
        "agent-addon-execution-budget.md",
      ).forEach { pointer ->
        assertTrue(Files.isRegularFile(staged.stagingDir.resolve(pointer).toPath()), "$skillName/$pointer")
      }
    }
  }
}
