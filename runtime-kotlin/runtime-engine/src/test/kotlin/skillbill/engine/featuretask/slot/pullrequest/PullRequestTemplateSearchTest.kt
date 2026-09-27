package skillbill.engine.featuretask.slot.pullrequest

import skillbill.infrastructure.workflow.git.goal.FileSystemPullRequestTemplateFiles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PullRequestTemplateSearchTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-pr-template")
  private val files = FileSystemPullRequestTemplateFiles()

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
  }

  @Test
  fun `a found template keeps its headings in order and drops its checklist`() {
    write(".github/pull_request_template.md", TEMPLATE)

    val template = PullRequestTemplateSearch.resolve(repoRoot, files)

    assertIs<PullRequestTemplate.Found>(template)
    assertEquals(".github/pull_request_template.md", template.path)
    val content = template.content
    assertTrue(content.indexOf("## Summary") < content.indexOf("## Testing"), content)
    assertTrue(content.indexOf("## Testing") < content.indexOf("## Notes"), content)
    assertTrue("Describe the change." in content, content)
    assertTrue("Keep this prose." in content, content)
    assertFalse("[ ]" in content || "[x]" in content, content)
    assertFalse("## Checklist" in content, "a checklist-only section drops its heading: $content")
  }

  @Test
  fun `an empty repository has no template`() {
    assertEquals(PullRequestTemplate.Absent, PullRequestTemplateSearch.resolve(repoRoot, files))
  }

  @Test
  fun `two directory templates and no default are ambiguous and name both paths`() {
    write(".github/PULL_REQUEST_TEMPLATE/bugfix.md", "## Bug\n")
    write(".github/PULL_REQUEST_TEMPLATE/feature.md", "## Feature\n")

    val template = PullRequestTemplateSearch.resolve(repoRoot, files)

    assertIs<PullRequestTemplate.Ambiguous>(template)
    assertEquals(
      listOf(".github/PULL_REQUEST_TEMPLATE/bugfix.md", ".github/PULL_REQUEST_TEMPLATE/feature.md"),
      template.paths,
    )
  }

  @Test
  fun `a single-file template wins over directory templates`() {
    write("PULL_REQUEST_TEMPLATE.md", "## Default\n")
    write(".github/PULL_REQUEST_TEMPLATE/bugfix.md", "## Bug\n")
    write(".github/PULL_REQUEST_TEMPLATE/feature.md", "## Feature\n")

    val template = PullRequestTemplateSearch.resolve(repoRoot, files)

    assertIs<PullRequestTemplate.Found>(template)
    assertEquals("PULL_REQUEST_TEMPLATE.md", template.path)
    assertEquals("## Default", template.content)
  }

  @Test
  fun `a directory template wins over the docs template listed after it`() {
    write("docs/pull_request_template.md", "## Docs\n")
    write(".github/PULL_REQUEST_TEMPLATE/feature.md", "## Feature\n")

    val template = PullRequestTemplateSearch.resolve(repoRoot, files)

    assertIs<PullRequestTemplate.Found>(template)
    assertEquals(".github/PULL_REQUEST_TEMPLATE/feature.md", template.path)
  }

  @Test
  fun `a single directory template is found`() {
    write(".github/PULL_REQUEST_TEMPLATE/feature.md", "## Feature\n- [ ] tested\n\nWhy it matters.\n")

    val template = PullRequestTemplateSearch.resolve(repoRoot, files)

    assertIs<PullRequestTemplate.Found>(template)
    assertEquals(".github/PULL_REQUEST_TEMPLATE/feature.md", template.path)
    assertEquals("## Feature\n\nWhy it matters.", template.content)
  }

  @Test
  fun `a symlinked template reports the searched location, not its target`() {
    write("templates/shared/pr.md", "## Shared\n")
    Files.createDirectories(repoRoot.resolve(".github"))
    Files.createSymbolicLink(
      repoRoot.resolve(".github/pull_request_template.md"),
      repoRoot.resolve("templates/shared/pr.md"),
    )

    val template = PullRequestTemplateSearch.resolve(repoRoot, files)

    assertIs<PullRequestTemplate.Found>(template)
    assertEquals(".github/pull_request_template.md", template.path)
    assertEquals("## Shared", template.content)
  }

  private fun write(
    relative: String,
    content: String,
  ) {
    val target = repoRoot.resolve(relative)
    Files.createDirectories(target.parent)
    Files.writeString(target, content)
  }

  private companion object {
    val TEMPLATE =
      """
      ## Summary
      Describe the change.

      ## Checklist
      - [ ] Tests pass
      - [x] Docs updated

      ## Testing
      * [ ] Manual check
      Keep this prose.

      ## Notes
      Anything else.
      """.trimIndent()
  }
}
