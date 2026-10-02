package skillbill.install

import skillbill.infrastructure.skills.install.staging.StageInstalledSkillInput
import skillbill.infrastructure.skills.install.staging.stageInstalledSkill
import skillbill.infrastructure.skills.scaffold.authoring.parseInternalForFrontmatter
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InternalSkillStagingRepoTest {
  private val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    tempDirs.reversed().forEach { dir ->
      if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
        Files.walk(dir).use { stream ->
          stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      }
    }
  }

  @Test
  fun `shipped kmp ui wrapper resolves its flat compose guidelines companion`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-kmp-companion-home").also(tempDirs::add)
    val parentDir = repoRoot.resolve("skills/$PACK_SIDECAR_PARENT_SKILL")
    val uiDir = repoRoot.resolve("platform-packs/kmp/code-review/bill-kmp-code-review-ui")
    val uiSkill =
      InstallPlanSkill(
        name = "bill-kmp-code-review-ui",
        sourceDir = uiDir.toFileLocation(),
        kind = InstallPlanSkillKind.PLATFORM_PACK,
        platformSlug = "kmp",
        internalFor = PACK_SIDECAR_PARENT_SKILL,
      )

    val rendered =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = repoRoot,
          sourceSkillDir = parentDir,
          home = home,
          selectedPackSkills = listOf(uiSkill),
        ),
      )

    val wrapper = rendered.stagingDir.resolve("bill-kmp-code-review-ui.md")
    val companion = rendered.stagingDir.resolve("compose-guidelines.md")
    assertTrue(Files.isRegularFile(wrapper.toPath(), LinkOption.NOFOLLOW_LINKS))
    assertTrue(Files.isRegularFile(companion.toPath(), LinkOption.NOFOLLOW_LINKS))
    assertTrue(Files.readString(wrapper.toPath()).contains("[compose-guidelines.md](compose-guidelines.md)"))
    assertEquals(companion.toPath(), wrapper.toPath().parent.resolve("compose-guidelines.md"))
  }

  @Test
  fun `every shipped pack specialist stages as a sidecar of skill-bill only`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-sidecar-parent-home").also(tempDirs::add)
    val packSkills = shippedCodeReviewSkills(repoRoot)
    assertTrue(packSkills.isNotEmpty(), "expected shipped pack code-review skills")
    val misparented = packSkills.filter { it.internalFor != PACK_SIDECAR_PARENT_SKILL }.map { it.name }
    assertTrue(misparented.isEmpty(), "pack specialists must declare internal-for: skill-bill; found $misparented")

    val rendered =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = repoRoot,
          sourceSkillDir = repoRoot.resolve("skills/$PACK_SIDECAR_PARENT_SKILL"),
          home = home,
          selectedPackSkills = packSkills,
        ),
      )

    val expectedWrappers = packSkills.map { "${it.name}.md" }.toSet()
    val expectedCompanions = packSkills.map { it.sourceDir.toPath() }.flatMap(::authoredCompanionNames).toSet()
    assertEquals(
      expectedWrappers + expectedCompanions,
      rendered.renderedSidecarFiles.map { it.fileName }.toSet(),
      "skill-bill must stage exactly the shipped pack specialists as sidecars",
    )
    expectedWrappers.forEach { name ->
      assertTrue(
        Files.isRegularFile(rendered.stagingDir.resolve(name).toPath(), LinkOption.NOFOLLOW_LINKS),
        "missing staged sidecar $name under the installed $PACK_SIDECAR_PARENT_SKILL dir",
      )
    }
  }

  private fun authoredCompanionNames(skillDir: Path): List<String> =
    Files.list(skillDir).use { stream ->
      stream
        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        .map { it.fileName.toString() }
        .filter { it.endsWith(".md") && it != "content.md" }
        .toList()
    }

  private fun shippedCodeReviewSkills(repoRoot: Path): List<InstallPlanSkill> {
    val packsRoot = repoRoot.resolve("platform-packs")
    return Files.list(packsRoot).use { packs -> packs.filter { Files.isDirectory(it) }.sorted().toList() }
      .flatMap { packDir ->
        val codeReview = packDir.resolve("code-review")
        if (!Files.isDirectory(codeReview)) {
          emptyList()
        } else {
          Files.list(codeReview).use { skills -> skills.filter { Files.isDirectory(it) }.sorted().toList() }
            .map { skillDir ->
              InstallPlanSkill(
                name = skillDir.fileName.toString(),
                sourceDir = skillDir.toFileLocation(),
                kind = InstallPlanSkillKind.PLATFORM_PACK,
                platformSlug = packDir.fileName.toString(),
                internalFor = parseInternalForFrontmatter(skillDir.resolve("content.md")),
              )
            }
        }
      }
  }
}
