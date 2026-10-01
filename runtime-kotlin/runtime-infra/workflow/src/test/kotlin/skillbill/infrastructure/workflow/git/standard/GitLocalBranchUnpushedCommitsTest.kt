package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.git.git
import skillbill.infrastructure.workflow.git.runGit
import skillbill.infrastructure.workflow.process.runGitCommand
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitLocalBranchUnpushedCommitsTest {
  private lateinit var repo: Path
  private lateinit var origin: Path

  @BeforeTest
  fun setUp() {
    origin = Files.createTempDirectory("skillbill-unpushed-origin")
    runGit(origin, "init", "--bare", "--initial-branch", "main")
    repo = Files.createTempDirectory("skillbill-unpushed-local")
    git("init", "--initial-branch", "main")
    git("config", "user.email", "runtime@skill-bill.test")
    git("config", "user.name", "Skill Bill Runtime")
    git("config", "commit.gpgsign", "false")
    git("remote", "add", "origin", origin.toString())
    write("Base.kt", "base\n")
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
  fun `a new branch off a pushed base reports no unpushed commits`() {
    git("checkout", "-b", "feat/new-goal")

    val result = gitLocalBranchHasUnpushedCommits(repo, "feat/new-goal")

    assertTrue(result.ok, result.error)
    assertEquals("false", result.value.trim())
  }

  @Test
  fun `a new branch with its own commit reports unpushed commits`() {
    git("checkout", "-b", "feat/new-goal")
    write("Base.kt", "local\n")
    git("add", "-A")
    git("commit", "-m", "local work")

    val result = gitLocalBranchHasUnpushedCommits(repo, "feat/new-goal")

    assertTrue(result.ok, result.error)
    assertEquals("true", result.value.trim())
  }

  @Test
  fun `a branch whose commits are published under another remote ref reports none unpushed`() {
    git("checkout", "-b", "feat/published")
    write("Base.kt", "published\n")
    git("add", "-A")
    git("commit", "-m", "published work")
    git("push", "origin", "feat/published:refs/heads/feat/published-elsewhere")
    git("fetch", "origin")

    val result = gitLocalBranchHasUnpushedCommits(repo, "feat/published")

    assertTrue(result.ok, result.error)
    assertEquals("false", result.value.trim())
  }

  @Test
  fun `a pushed branch still compares against its own origin counterpart`() {
    git("checkout", "-b", "feat/pushed")
    write("Base.kt", "pushed\n")
    git("add", "-A")
    git("commit", "-m", "pushed work")
    git("push", "-u", "origin", "feat/pushed")

    val pushed = gitLocalBranchHasUnpushedCommits(repo, "feat/pushed")

    assertTrue(pushed.ok, pushed.error)
    assertEquals("false", pushed.value.trim())

    write("Base.kt", "ahead\n")
    git("add", "-A")
    git("commit", "-m", "ahead of origin")

    val ahead = gitLocalBranchHasUnpushedCommits(repo, "feat/pushed")

    assertTrue(ahead.ok, ahead.error)
    assertEquals("true", ahead.value.trim())
  }

  @Test
  fun `a blank branch name is refused`() {
    val result = gitLocalBranchHasUnpushedCommits(repo, "   ")

    assertTrue(!result.ok)
    assertEquals("Branch name is required to compare with origin.", result.error)
  }

  private fun git(vararg args: String) = runGit(repo, *args)

  private fun runGit(
    root: Path,
    vararg args: String,
  ) {
    val result = runGitCommand(root, *args)
    assertTrue(result.ok, "git ${args.joinToString(" ")} failed: ${result.error}")
  }

  private fun write(
    relativePath: String,
    content: String,
  ) {
    repo.resolve(relativePath).writeText(content)
  }
}
