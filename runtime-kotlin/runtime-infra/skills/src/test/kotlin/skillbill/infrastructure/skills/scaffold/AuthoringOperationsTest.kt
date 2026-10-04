package skillbill.infrastructure.skills.scaffold

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.skills.scaffold.authoring.AuthoringOperations
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.ScaffoldAuthoringFailureCode
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.supportingFileTargets
import skillbill.infrastructure.skills.scaffold.runtime.service.scaffold
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthoringOperationsTest {
  @Test
  fun `fill fails fast and leaves content_md untouched when neither supplied nor existing carries frontmatter`() {
    val repo = seedFillFixtureRepo("fill-no-frontmatter")
    val skillName = "bill-fill-fixture"
    val contentFile = repo.resolve("skills").resolve(skillName).resolve("content.md")

    val unframedContent = "# Fixture Content\n\nBody without frontmatter to trip the F-D guard.\n"
    Files.writeString(contentFile, unframedContent)
    val before = Files.readAllBytes(contentFile)

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        AuthoringOperations.fill(
          repoRoot = repo,
          skillName = skillName,
          body = "Replacement body, also without frontmatter — should never write.",
          sectionName = null,
        )
      }

    assertContains(error.message.orEmpty(), "content.md must already carry a YAML frontmatter block")
    assertEquals(ScaffoldAuthoringFailureCode.AUTHORING_FAILURE, error.code)
    val after = Files.readAllBytes(contentFile)
    assertEquals(before.toList(), after.toList(), "content.md must be untouched when fill fails fast")
  }

  @Test
  fun `show status pins the typed authoring contract`() {
    val repo = seedFillFixtureRepo("show-keyset")
    val skillName = "bill-fill-fixture"

    val status = AuthoringOperations.show(repo, skillName, contentMode = "none")

    assertEquals(skillName, status.skillName)
    assertEquals("complete", status.completionStatus.wireValue)
    assertEquals(null, status.contentPreview, "content=none must not surface a content preview")
    assertEquals(null, status.content, "content=none must not surface full content")
    assertEquals(null, status.issues, "show without issues must not surface an issues list")
  }

  @Test
  fun `list returns a single typed status entry`() {
    val repo = seedFillFixtureRepo("list-keyset")
    val skillName = "bill-fill-fixture"

    val result = AuthoringOperations.list(repo, listOf(skillName))

    assertEquals(1, result.skillCount)
    assertEquals(1, result.skills.size)
    assertEquals(skillName, result.skills.first().skillName)
  }

  @Test
  fun `validate reports typed repo and selected modes`() {
    val repo = seedFillFixtureRepo("validate-keyset")
    val skillName = "bill-fill-fixture"

    val repoResult = AuthoringOperations.validate(repo, emptyList())
    val selectedResult = AuthoringOperations.validate(repo, listOf(skillName))

    assertEquals("repo", repoResult.mode)
    assertEquals(null, repoResult.skillNames, "repo mode must not carry skill_names")
    assertEquals(null, repoResult.suggestedCommands, "repo mode must not carry suggested_commands")
    assertEquals("selected", selectedResult.mode)
    assertEquals(listOf(skillName), selectedResult.skillNames)
  }

  @Test
  fun `scaffold propagates F-C error and leaves no partial skill when content_body carries frontmatter`() {
    val repo = Files.createTempDirectory("skillbill-scaffold-stacked-frontmatter")
    supportingFileTargets(repo).values.forEach { target ->
      Files.createDirectories(target.parent)
      Files.writeString(target, "# ${target.fileName}\n")
    }
    val skillName = "bill-scaffold-stacked-fm"
    val skillDir = repo.resolve("skills").resolve(skillName)
    val bodyWithFrontmatter =
      """
      |---
      |name: $skillName
      |description: Pre-supplied frontmatter that would stack with the canonical block.
      |---
      |
      |# Body
      |
      |Body with caller-supplied frontmatter to trip the F-C guard.
      """.trimMargin() + "\n"

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        scaffold(
          mapOf(
            "scaffold_payload_version" to "1.0",
            "kind" to "horizontal",
            "repo_root" to repo.toString(),
            "name" to skillName,
            "content_body" to bodyWithFrontmatter,
          ),
        )
      }

    assertContains(error.message.orEmpty(), "frontmatter")

    assertEquals(false, Files.exists(skillDir), "Failed scaffold left a partial skill directory at $skillDir")
  }

  @Test
  fun `fill accepts clean authored body without generated wrapper headings`() {
    val repo = seedFillFixtureRepo("fill-clean-body")
    val skillName = "bill-fill-fixture"
    val contentFile = repo.resolve("skills").resolve(skillName).resolve("content.md")

    val result =
      AuthoringOperations.fill(
        repoRoot = repo,
        skillName = skillName,
        body = "Clean authored guidance without wrapper headings.",
        sectionName = null,
      )
    val content = Files.readString(contentFile)

    assertEquals("complete", result.mutation.status.completionStatus.wireValue)
    assertContains(content, "# Fixture Content\n\nClean authored guidance without wrapper headings.")
    assertEquals(false, "## Descriptor" in content)
    assertEquals(false, "## Execution" in content)
    assertEquals(false, "## Ceremony" in content)
  }

  @Test
  fun `fill rejects generated wrapper headings and rolls back content_md`() {
    val repo = seedFillFixtureRepo("fill-reject-wrapper-heading")
    val skillName = "bill-fill-fixture"
    val contentFile = repo.resolve("skills").resolve(skillName).resolve("content.md")
    val before = Files.readString(contentFile)

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        AuthoringOperations.fill(
          repoRoot = repo,
          skillName = skillName,
          body = "## Execution\n\nGenerated wrapper content must not be authored here.",
          sectionName = null,
        )
      }

    assertContains(error.message.orEmpty(), "generated wrapper boilerplate heading '## Execution'")
    assertEquals(before, Files.readString(contentFile), "content.md must roll back after wrapper-heading rejection")
  }

  @Test
  fun `edit with section targets authored H2 sections only`() {
    val repo = seedSectionFixtureRepo("edit-authored-section")
    val skillName = "bill-section-fixture"
    val contentFile = repo.resolve("skills").resolve(skillName).resolve("content.md")

    val result =
      AuthoringOperations.editWithBodyFile(
        repoRoot = repo,
        skillName = skillName,
        body = "Updated guidance body.",
        sectionName = "Review Guidance",
      )
    val content = Files.readString(contentFile)

    assertEquals("Review Guidance", result.updatedSection)
    assertContains(content, "## Review Guidance\n\nUpdated guidance body.")
    assertContains(content, "## Review Focus")
    assertContains(content, "Initial focus.")
  }

  @Test
  fun `edit with generated wrapper section explains authored surface instead of mutating`() {
    val repo = seedSectionFixtureRepo("edit-generated-section")
    val skillName = "bill-section-fixture"
    val contentFile = repo.resolve("skills").resolve(skillName).resolve("content.md")
    val before = Files.readString(contentFile)

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        AuthoringOperations.editWithBodyFile(
          repoRoot = repo,
          skillName = skillName,
          body = "New generated section body.",
          sectionName = "Descriptor",
        )
      }

    val message = error.message.orEmpty()
    assertContains(message, "Cannot edit generated wrapper section '## Descriptor'")
    assertContains(message, "Edit authored content.md sections")
    assertContains(message, "platform.yaml manifest fields")
    assertEquals(before, Files.readString(contentFile), "content.md must not change for generated-section edits")
  }

  private fun seedFillFixtureRepo(prefix: String): Path {
    val repo = Files.createTempDirectory("skillbill-$prefix")
    val skillName = "bill-fill-fixture"
    val skillDir = repo.resolve("skills").resolve(skillName)
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: $skillName
      description: Fixture skill for AuthoringOperations integration tests.
      ---

      # Fixture Content

      Initial authored content.
      """.trimIndent() + "\n",
    )
    return repo
  }

  private fun seedSectionFixtureRepo(prefix: String): Path {
    val repo = Files.createTempDirectory("skillbill-$prefix")
    val skillName = "bill-section-fixture"
    val skillDir = repo.resolve("skills").resolve(skillName)
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: $skillName
      description: Fixture skill for AuthoringOperations section tests.
      ---

      # Fixture Content

      ## Review Focus

      Initial focus.

      ## Review Guidance

      Initial guidance.
      """.trimIndent() + "\n",
    )
    return repo
  }
}
