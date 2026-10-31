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
  fun `remote commits the local branch lacks are rebased under the local commits and pushed`() {
    val repairHead = pushFromOtherClone(branch, "repair.txt", "repair\n", "monitor repair")
    commit(repo, "local.txt", "local\n", "local work")

    val result = gitPushBranch(repo, branch, withLease = false)

    assertTrue(result is WorkflowGitOperationResult.Ok, result.error)
    assertEquals(git(repo, "rev-parse", "HEAD"), git(remote, "rev-parse", "refs/heads/$branch"))
    assertEquals(repairHead, git(repo, "rev-parse", "HEAD~1"))
    assertEquals("local work", git(repo, "log", "-1", "--format=%s").trim())
  }

  @Test
  fun `local commit already pushed from another clone is dropped while integrating`() {
    val other = cloneBranch(branch)
    commit(other, "scroll.txt", "scroll\n", "scroll rebased elsewhere")
    git(other, "push", "origin", branch)
    commit(repo, "scroll.txt", "scroll\n", "scroll")
    commit(repo, "final.txt", "final\n", "finalization")

    val result = gitPushBranch(repo, branch, withLease = false)

    assertTrue(result is WorkflowGitOperationResult.Ok, result.error)
    assertEquals(
      listOf("finalization", "scroll rebased elsewhere", "feature", "initial"),
      git(repo, "log", "--format=%s").lines().filter(String::isNotBlank),
    )
    assertEquals(git(repo, "rev-parse", "HEAD"), git(remote, "rev-parse", "refs/heads/$branch"))
  }

  @Test
  fun `conflicting remote commits abort the rebase and keep the local branch`() {
    val repairHead = pushFromOtherClone(branch, "feature.txt", "theirs\n", "monitor repair")
    commit(repo, "feature.txt", "ours\n", "local work")
    val localHead = git(repo, "rev-parse", "HEAD").trim()

    val result = gitPushBranch(repo, branch, withLease = false)

    assertTrue(result is WorkflowGitOperationResult.Failed)
    assertContains(result.error, "conflicts in: feature.txt")
    assertEquals(localHead, git(repo, "rev-parse", "HEAD").trim())
    assertEquals("", git(repo, "status", "--porcelain").trim())
    assertEquals(repairHead, git(remote, "rev-parse", "refs/heads/$branch"))
  }

  @Test
  fun `protected branch behind its remote is not rebased`() {
    git(repo, "checkout", "main")
    val remoteMain = pushFromOtherClone("main", "upstream.txt", "upstream\n", "upstream")
    commit(repo, "local.txt", "local\n", "local on main")
    val localHead = git(repo, "rev-parse", "HEAD")

    val result = gitPushBranch(repo, "main", withLease = false)

    assertTrue(result is WorkflowGitOperationResult.Failed)
    assertEquals(localHead, git(repo, "rev-parse", "HEAD"))
    assertEquals(remoteMain, git(remote, "rev-parse", "refs/heads/main"))
  }

  private fun cloneBranch(name: String): Path {
    val other = Files.createTempDirectory(root, "other")
    Files.delete(other)
    git(root, "clone", "-b", name, remote.toString(), other.toString())
    configure(other)
    return other
  }

  private fun pushFromOtherClone(
    name: String,
    file: String,
    content: String,
    message: String,
  ): String {
    val other = cloneBranch(name)
    commit(other, file, content, message)
    git(other, "push", "origin", name)
    return git(other, "rev-parse", "HEAD")
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
