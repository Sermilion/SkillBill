package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.process.runGitCommand
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitStandardWorkflowGitReleaseTagOperationsTest {
  private lateinit var repo: Path
  private lateinit var origin: Path

  @BeforeTest
  fun setUp() {
    origin = Files.createTempDirectory("skillbill-release-origin")
    runGit(origin, "init", "--bare", "--initial-branch", "main")
    repo = Files.createTempDirectory("skillbill-release-local")
    git("init", "--initial-branch", "main")
    git("config", "user.email", "runtime@skill-bill.test")
    git("config", "user.name", "Skill Bill Runtime")
    git("config", "commit.gpgsign", "false")
    git("config", "tag.gpgsign", "false")
    git("remote", "add", "origin", origin.toString())
    repo.resolve("Base.kt").writeText("base\n")
    git("add", "-A")
    git("commit", "-m", "base")
    git("push", "-u", "origin", "main")
  }

  @AfterTest
  fun tearDown() {
    repo.toFile().deleteRecursively()
    origin.toFile().deleteRecursively()
  }

  @Test
  fun `the pushed annotated tag carries the exact multi-line message and becomes the last release tag`() {
    val operations = GitStandardWorkflowGitReleaseTagOperations
    val message = "## What's New in v1.0.0\n\n### New Features\n- Operations: `skill-bill operation`\n\n"

    val created = operations.createAnnotatedTag(repo, "v1.0.0", message)
    val pushed = operations.pushTag(repo, "v1.0.0")

    assertTrue(created.ok, created.error)
    assertTrue(pushed.ok, pushed.error)
    assertEquals(message, rawTagMessage(origin, "v1.0.0"))
    assertEquals("v1.0.0", operations.lastReleaseTag(repo).value)
  }

  @Test
  fun `a newer release tag on another branch is still the last release tag`() {
    git("tag", "v1.0.0")
    git("checkout", "-b", "hotfix")
    repo.resolve("Hotfix.kt").writeText("hotfix\n")
    git("add", "-A")
    git("commit", "-m", "hotfix")
    git("tag", "v1.0.1")
    git("checkout", "main")

    assertEquals("v1.0.1", GitStandardWorkflowGitReleaseTagOperations.lastReleaseTag(repo).value)
  }

  private fun rawTagMessage(
    root: Path,
    tag: String,
  ): String {
    val process = ProcessBuilder("git", "-C", root.toString(), "cat-file", "tag", tag).start()
    val raw = process.inputStream.readBytes().toString(Charsets.UTF_8)
    assertEquals(0, process.waitFor(), "git cat-file tag $tag failed")
    return raw.substringAfter("\n\n")
  }

  private fun git(vararg args: String) = runGit(repo, *args)

  private fun runGit(
    root: Path,
    vararg args: String,
  ) {
    val result = runGitCommand(root, *args)
    assertTrue(result.ok, "git ${args.joinToString(" ")} failed: ${result.error}")
  }
}
