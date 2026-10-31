package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.git.git
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitBranchPushRewrittenHistoryTest {
  private lateinit var root: Path
  private lateinit var repo: Path
  private lateinit var remote: Path
  private val branch = "feat/rewrite"

  @BeforeTest
  fun setUp() {
    root = Files.createTempDirectory("skillbill-push-rewrite")
    remote = Files.createDirectory(root.resolve("remote"))
    repo = Files.createDirectory(root.resolve("repo"))
    git(remote, "init", "--bare", "-b", "main")
    git(repo, "init", "-b", "main")
    configure(repo)
    git(repo, "remote", "add", "origin", remote.toString())
    commit(repo, "base.txt", "base\n", "initial")
    git(repo, "push", "origin", "main")
    git(repo, "checkout", "-b", branch)
    commit(repo, "feature.txt", "feature\n", "feature")
    git(repo, "push", "-u", "origin", branch)
  }

  @AfterTest
  fun tearDown() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun `rebase of published commits replaces the remote branch with a lease`() {
    git(repo, "checkout", "main")
    commit(repo, "main.txt", "main\n", "main moved")
    git(repo, "push", "origin", "main")
    git(repo, "checkout", branch)
    git(repo, "rebase", "main")
    commit(repo, "feature.txt", "feature 2\n", "after rebase")

    val result = gitPushBranch(repo, branch, withLease = false)

    assertTrue(result is WorkflowGitOperationResult.Ok, result.error)
    assertEquals(git(repo, "rev-parse", "HEAD"), git(remote, "rev-parse", "refs/heads/$branch"))
  }

  @Test
  fun `remote commit the local branch never held keeps the push rejected`() {
    val other = Files.createDirectory(root.resolve("other"))
    git(root, "clone", "-b", branch, remote.toString(), other.toString())
    configure(other)
    commit(other, "repair.txt", "repair\n", "monitor repair")
    git(other, "push", "origin", branch)
    val repairHead = git(other, "rev-parse", "HEAD")
    git(repo, "commit", "--amend", "-m", "feature amended")

    val result = gitPushBranch(repo, branch, withLease = false)

    assertTrue(result is WorkflowGitOperationResult.Failed)
    assertContains(result.error, "never on local branch")
    assertEquals(repairHead, git(remote, "rev-parse", "refs/heads/$branch"))
  }

  private fun configure(dir: Path) {
    git(dir, "config", "user.email", "runtime@example.test")
    git(dir, "config", "user.name", "Runtime")
    git(dir, "config", "commit.gpgsign", "false")
  }

  private fun commit(
    dir: Path,
    file: String,
    content: String,
    message: String,
  ) {
    Files.writeString(dir.resolve(file), content)
    git(dir, "add", file)
    git(dir, "commit", "-m", message)
  }
}
