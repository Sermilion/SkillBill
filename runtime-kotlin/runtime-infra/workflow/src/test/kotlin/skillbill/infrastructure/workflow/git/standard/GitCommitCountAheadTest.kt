package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitCommitCountAheadTest {
  private lateinit var repo: Path
  private lateinit var origin: Path

  @BeforeTest
  fun setUp() {
    origin = Files.createTempDirectory("skillbill-count-origin")
    runGit(origin, "init", "--bare", "--initial-branch", "main")
    repo = Files.createTempDirectory("skillbill-count-local")
    git("init", "--initial-branch", "main")
    git("config", "user.email", "runtime@skill-bill.test")
    git("config", "user.name", "Skill Bill Runtime")
    git("config", "commit.gpgsign", "false")
    git("remote", "add", "origin", origin.toString())
    commit("Base.kt", "base\n")
    git("push", "-u", "origin", "main")
  }

  @AfterTest
  fun tearDown() {
    repo.toFile().deleteRecursively()
    origin.toFile().deleteRecursively()
  }

  @Test
  fun `a branch counts the commits it holds beyond the base revision`() {
    git("checkout", "-b", "feat/count")
    commit("One.kt", "one\n")
    commit("Two.kt", "two\n")
    commit("Three.kt", "three\n")

    val result = GitStandardWorkflowGitCommitHistoryOperations.commitCountAhead(repo, "origin/main")

    assertIs<WorkflowGitOperationResult.Ok>(result, result.toString())
    assertEquals("3", result.value.trim())
  }

  @Test
  fun `a branch level with the base counts zero`() {
    git("checkout", "-b", "feat/level")

    val result = GitStandardWorkflowGitCommitHistoryOperations.commitCountAhead(repo, "origin/main")

    assertIs<WorkflowGitOperationResult.Ok>(result, result.toString())
    assertEquals("0", result.value.trim())
  }

  @Test
  fun `an unknown base revision fails`() {
    val result = GitStandardWorkflowGitCommitHistoryOperations.commitCountAhead(repo, "origin/missing")

    assertIs<WorkflowGitOperationResult.Failed>(result, result.toString())
  }

  @Test
  fun `the merge base stays at the fork point after the base moves on`() {
    val forkPoint = runGitCommand(repo, "rev-parse", "HEAD").value.trim()
    git("checkout", "-b", "feat/fork")
    commit("Feature.kt", "feature\n")
    git("checkout", "main")
    commit("Moved.kt", "moved\n")
    git("push", "origin", "main")
    git("checkout", "feat/fork")

    val result = GitStandardWorkflowGitCommitHistoryOperations.mergeBaseWithHead(repo, "origin/main")

    assertIs<WorkflowGitOperationResult.Ok>(result, result.toString())
    assertEquals(forkPoint, result.value.trim())
  }

  private fun commit(
    relativePath: String,
    content: String,
  ) {
    repo.resolve(relativePath).writeText(content)
    git("add", "-A")
    git("commit", "-m", "Change $relativePath")
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
