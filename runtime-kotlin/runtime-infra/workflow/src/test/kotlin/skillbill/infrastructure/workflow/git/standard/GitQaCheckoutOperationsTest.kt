package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.git.GitRepositoryFingerprintOperations
import skillbill.infrastructure.workflow.git.git
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowIgnoredFilesCopyResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitQaCheckoutOperationsTest {
  private lateinit var root: Path
  private lateinit var repo: Path
  private lateinit var checkout: Path

  @BeforeTest
  fun setUp() {
    root = Files.createTempDirectory("skillbill-qa-checkout")
    repo = root.resolve("repo").createDirectories()
    checkout = root.resolve("qa/invocation/checkout")
    git(repo, "init", "--initial-branch", "main")
    git(repo, "config", "user.email", "runtime@skill-bill.test")
    git(repo, "config", "user.name", "Skill Bill Runtime")
    repo.resolve(".gitignore").writeText("local.properties\nbuild/\n*.bin\n")
    repo.resolve("Staged.kt").writeText("base\n")
    repo.resolve("Unstaged.kt").writeText("base\n")
    repo.resolve("Deleted.kt").writeText("base\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-m", "base")
  }

  @AfterTest
  fun tearDown() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun `the checkout copies staged, unstaged, deleted and untracked changes with the same tree identity`() {
    repo.resolve("Staged.kt").writeText("staged\n")
    git(repo, "add", "Staged.kt")
    repo.resolve("Unstaged.kt").writeText("unstaged\n")
    Files.delete(repo.resolve("Deleted.kt"))
    repo.resolve("src").createDirectories().resolve("New.kt").writeText("untracked\n")

    val opened = openCheckout()

    assertEquals(identity(repo), identity(checkout))
    assertEquals(git(repo, "status", "--porcelain"), git(checkout, "status", "--porcelain"))
    assertEquals("staged\n", git(checkout, "show", ":Staged.kt") + "\n")
    assertEquals("unstaged\n", checkout.resolve("Unstaged.kt").readText())
    assertFalse(checkout.resolve("Deleted.kt").exists())
    assertEquals(checkout.toString(), opened.value)
  }

  @Test
  fun `an edit in the checkout leaves the repository untouched and goes away with the checkout`() {
    repo.resolve("Unstaged.kt").writeText("unstaged\n")
    val before = identity(repo)
    openCheckout()

    checkout.resolve("Unstaged.kt").writeText("fault injected by QA\n")
    checkout.resolve("Debug.kt").writeText("debug hook\n")

    assertEquals(before, identity(repo))
    assertEquals("unstaged\n", repo.resolve("Unstaged.kt").readText())
    assertTrue(GitStandardWorkflowGitWorktreeOperations.removeWorktree(repo, checkout).ok)
    assertFalse(checkout.exists())
    assertEquals(before, identity(repo))
    assertFalse(checkout.toString() in git(repo, "worktree", "list"))
  }

  @Test
  fun `ignored files outside ignored directories are copied and large ones are skipped`() {
    repo.resolve("local.properties").writeText("sdk.dir=/sdk\n")
    repo.resolve("app").createDirectories().resolve("local.properties").writeText("api=staging\n")
    repo.resolve("build").createDirectories().resolve("output.txt").writeText("generated\n")
    Files.write(repo.resolve("large.bin"), ByteArray(LIMIT.toInt() + 1))
    openCheckout()

    val copied =
      assertIs<WorkflowIgnoredFilesCopyResult.Copied>(
        GitStandardWorkflowGitWorktreeOperations.copyIgnoredFiles(repo, checkout, LIMIT),
      )

    assertEquals(listOf("app/local.properties", "local.properties"), copied.copiedPaths)
    assertEquals(listOf("large.bin"), copied.skippedPaths)
    assertEquals("sdk.dir=/sdk\n", checkout.resolve("local.properties").readText())
    assertEquals("api=staging\n", checkout.resolve("app/local.properties").readText())
    assertFalse(checkout.resolve("build").exists())
    assertEquals(identity(repo), identity(checkout))
  }

  private fun openCheckout(): WorkflowGitOperationResult {
    val head = git(repo, "rev-parse", "HEAD")
    assertTrue(GitStandardWorkflowGitWorktreeOperations.addDetachedWorktree(repo, checkout, head).ok)
    val copied = GitStandardWorkflowGitWorktreeOperations.copyWorkingState(repo, checkout)
    assertTrue(copied.ok, copied.error)
    return copied
  }

  private fun identity(path: Path): String =
    assertIs<WorkflowGitOperationResult.Ok>(GitRepositoryFingerprintOperations.repositoryTreeIdentity(path)).value

  private companion object {
    const val LIMIT = 1024L
  }
}
