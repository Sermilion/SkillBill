package skillbill.infrastructure.skills.install.plan

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.SkillStagingFailureCode
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.ports.repository.toFileLocation
import skillbill.testing.repoRootFromTest

class InstallPlanInternalSkillDiscoveryTest {
  private val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    tempDirs.reversed().forEach { dir ->
      if (Files.exists(dir)) {
        Files.walk(dir).use { stream ->
          stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      }
    }
  }

  @Test
  fun `source installation discovery excludes the retired over-engineering skill`() {
    val skills = discoverBaseSkills(repoRootFromTest().resolve("skills"))

    assertTrue(skills.none { it.name == "bill-over-engineering-review" })
  }

  @Test
  fun `discoverBaseSkills carries internal-for classification onto the install plan`() {
    val repoRoot = Files.createTempDirectory("skillbill-plan-discovery").also(tempDirs::add)
    seedSkill(repoRoot, "bill-feature", null)
    seedSkill(repoRoot, "bill-feature-helper", "bill-feature")

    val skills = discoverBaseSkills(repoRoot.resolve("skills"))

    val child = skills.single { it.name == "bill-feature-helper" }
    assertEquals("bill-feature", child.internalFor)
    val parent = skills.single { it.name == "bill-feature" }
    assertEquals(null, parent.internalFor)
  }

  @Test
  fun `validateInstallPlanInternalSkills passes for a valid parent and child`() {
    val skills =
      listOf(
        planSkill("bill-feature", internalFor = null),
        planSkill("bill-feature-helper", internalFor = "bill-feature"),
      )
    validateInstallPlanInternalSkills(skills)
  }

  @Test
  fun `validateInstallPlanInternalSkills fails for an unknown parent`() {
    val skills = listOf(planSkill("bill-feature-helper", internalFor = "bill-featur"))
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInstallPlanInternalSkills(skills)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertTrue(error.message.orEmpty().contains("not a discovered skill"))
    assertTrue(error.message.orEmpty().contains("bill-feature-helper"))
  }

  @Test
  fun `validateInstallPlanInternalSkills fails for a self parent`() {
    val skills = listOf(planSkill("bill-feature-helper", internalFor = "bill-feature-helper"))
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInstallPlanInternalSkills(skills)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertTrue(error.message.orEmpty().contains("skill itself"))
  }

  @Test
  fun `validateInstallPlanInternalSkills fails for an empty internal-for value`() {
    val skills = listOf(planSkill("bill-feature-helper", internalFor = "  "))
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInstallPlanInternalSkills(skills)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertTrue(error.message.orEmpty().contains("empty value"))
  }

  @Test
  fun `validateInstallPlanInternalSkills fails when parent is itself internal`() {
    val skills =
      listOf(
        planSkill("bill-other", internalFor = null),
        planSkill("bill-feature", internalFor = "bill-other"),
        planSkill("bill-feature-helper", internalFor = "bill-feature"),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInstallPlanInternalSkills(skills)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertTrue(error.message.orEmpty().contains("chained internal-for"))
    assertTrue(error.message.orEmpty().contains("bill-feature-helper"))
  }

  @Test
  fun `validateInstallPlanInternalSkills passes for a platform-pack skill internal to a base parent`() {
    val skills =
      listOf(
        planSkill("bill-code-review", internalFor = null),
        planSkill(
          "bill-kotlin-code-review",
          internalFor = "bill-code-review",
          kind = InstallPlanSkillKind.PLATFORM_PACK,
        ),
      )
    validateInstallPlanInternalSkills(skills)
  }

  @Test
  fun `validateInstallPlanInternalSkills fails when a platform-pack skill declares a pack-skill parent`() {
    val skills =
      listOf(
        planSkill(
          "bill-kotlin-code-review",
          internalFor = null,
          kind = InstallPlanSkillKind.PLATFORM_PACK,
        ),
        planSkill(
          "bill-kotlin-code-review-security",
          internalFor = "bill-kotlin-code-review",
          kind = InstallPlanSkillKind.PLATFORM_PACK,
        ),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInstallPlanInternalSkills(skills)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertTrue(error.message.orEmpty().contains("listed base skill"))
    assertTrue(error.message.orEmpty().contains("bill-kotlin-code-review-security"))
  }

  @Test
  fun `validateInstallPlanInternalSkills fails when the parent is a platform-pack skill`() {
    val skills =
      listOf(
        planSkill("bill-kotlin-code-review", internalFor = null, kind = InstallPlanSkillKind.PLATFORM_PACK),
        planSkill("bill-feature-helper", internalFor = "bill-kotlin-code-review"),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInstallPlanInternalSkills(skills)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertTrue(error.message.orEmpty().contains("listed base skill"))
  }

  @Test
  fun `a blank internal-for value in content md loud-fails through discovery and validation`() {
    val repoRoot = Files.createTempDirectory("skillbill-plan-blank").also(tempDirs::add)
    val skillDir = repoRoot.resolve("skills/bill-feature-helper")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: bill-feature-helper
      description: Internal.
      internal-for:
      ---

      Body.
      """.trimIndent(),
    )

    val skills = discoverBaseSkills(repoRoot.resolve("skills"))
    assertEquals("", skills.single().internalFor, "blank value must be preserved, not treated as listed")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInstallPlanInternalSkills(skills)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertTrue(error.message.orEmpty().contains("empty value"))
  }

  private fun planSkill(
    name: String,
    internalFor: String?,
    kind: InstallPlanSkillKind = InstallPlanSkillKind.BASE,
  ): InstallPlanSkill =
    InstallPlanSkill(
      name = name,
      sourceDir = Path.of("/repo/skills/$name").toAbsolutePath().normalize().toFileLocation(),
      kind = kind,
      platformSlug = if (kind == InstallPlanSkillKind.PLATFORM_PACK) "kotlin" else null,
      internalFor = internalFor,
    )

  private fun seedSkill(
    repoRoot: Path,
    skillName: String,
    internalFor: String?,
  ) {
    val skillDir = repoRoot.resolve("skills/$skillName")
    Files.createDirectories(skillDir)
    val frontmatter =
      buildString {
        appendLine("---")
        appendLine("name: $skillName")
        appendLine("description: $skillName skill.")
        if (internalFor != null) {
          appendLine("internal-for: $internalFor")
        }
        appendLine("---")
      }
    Files.writeString(skillDir.resolve("content.md"), frontmatter + "\nAuthored body.\n")
  }
}
