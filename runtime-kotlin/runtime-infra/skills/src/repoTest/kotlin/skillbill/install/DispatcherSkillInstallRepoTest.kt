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

  @Test
  fun `skill-bill routes both operations to the operation subcommand and states the relay rule`() {
    val content = Files.readString(repoRootFromTest().resolve("skills/skill-bill/content.md"))
    val routingRows = content.lines().filter { line -> line.startsWith("| `/skill-bill") }

    listOf(
      "operation:update-check" to "`skill-bill operation update-check`",
      "operation:release" to "`skill-bill operation release bump:<value>",
      "operation:pr-review-fix" to "`skill-bill operation pr-review-fix [<pr>] [<tokens>]",
      "operation:verify" to "`skill-bill operation verify [spec:<value>] [target:<value>] [mode:inline\\|delegated]",
    ).forEach { (token, command) ->
      assertTrue(
        routingRows.any { row -> token in row && command in row },
        "no routing row sends $token to $command",
      )
    }
    val prose = content.replace(Regex("\\s+"), " ")
    assertTrue("status: awaiting_confirmation confirm:<token>" in prose)
    assertTrue(
      "ask the operator once whether to proceed. On yes, run the same operation with `confirm:<token>`." in prose,
    )
    assertTrue("Never pass `confirm:` without an operator answer." in content)
    assertTrue("`confirm:<token>` and that `select:`" in prose, "pr-review-fix must re-run with the operator's select:")
    assertTrue("Never pass `confirm:` or `select:` without an operator answer." in prose)
    assertTrue("For `operation:verify`, forward `mode:` verbatim" in prose, "verify must forward mode: verbatim")
    assertTrue("ask the operator once to confirm or adjust them." in prose)
    assertTrue("`rehydrate-needed:`, run Rehydrate for that spec path, then run the same operation once more." in prose)
    assertTrue(
      "`mode:` or `target:` outside `phase:review` and `operation:verify`" in prose,
      "the token-rejection rule must admit verify's mode: and target:",
    )
    assertTrue("the caller passes `phase:` together with `operation:`: report a usage error." in content)
    assertTrue("SKILL-382" !in content, "the pre-SKILL-382 operation refusal must be gone")
  }
}
