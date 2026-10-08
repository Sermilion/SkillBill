package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitDefaultBranchTest {
  private val roots = mutableListOf<Path>()

  @AfterTest
  fun tearDown() {
    roots.forEach { it.toFile().deleteRecursively() }
  }

  @Test
  fun `a clone reports the branch origin HEAD points at`() {
    val origin = repository("develop")
    commit(origin, "Base.kt")
    val clone = tempDirectory("skillbill-default-clone")
    runGit(clone, "clone", origin.toString(), ".")

    val result = gitDefaultBranch(clone)

    assertIs<WorkflowGitOperationResult.Ok>(result, result.toString())
    assertEquals("develop", result.value)
  }

  @Test
  fun `without origin HEAD a local master branch is the default`() {
    val repo = repository("master")
    commit(repo, "Base.kt")

    val result = gitDefaultBranch(repo)

    assertIs<WorkflowGitOperationResult.Ok>(result, result.toString())
    assertEquals("master", result.value)
  }

  @Test
  fun `without origin HEAD or a conventional branch the default is unknown`() {
    val repo = repository("develop")
    commit(repo, "Base.kt")

    val result = gitDefaultBranch(repo)

    assertIs<WorkflowGitOperationResult.Failed>(result, result.toString())
  }

  private fun repository(initialBranch: String): Path =
    tempDirectory("skillbill-default-$initialBranch").also { root ->
      runGit(root, "init", "--initial-branch", initialBranch)
      runGit(root, "config", "user.email", "runtime@skill-bill.test")
      runGit(root, "config", "user.name", "Skill Bill Runtime")
      runGit(root, "config", "commit.gpgsign", "false")
    }

  private fun tempDirectory(prefix: String): Path = Files.createTempDirectory(prefix).also(roots::add)

  private fun commit(
    root: Path,
    relativePath: String,
  ) {
    root.resolve(relativePath).writeText("content\n")
    runGit(root, "add", "-A")
    runGit(root, "commit", "-m", "Change $relativePath")
  }

  private fun runGit(
    root: Path,
    vararg args: String,
  ) {
    val result = runGitCommand(root, *args)
    assertTrue(result.ok, "git ${args.joinToString(" ")} failed: ${result.error}")
  }
}
