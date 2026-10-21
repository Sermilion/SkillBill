package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.git.git
import skillbill.infrastructure.workflow.process.GitProcessResult
import skillbill.infrastructure.workflow.process.invokeGitProcess
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitBranchPushRecoveryTest {
  private lateinit var root: Path
  private lateinit var repo: Path
  private lateinit var remote: Path
  private val branch = "feat/recovery"

  @BeforeTest
  fun setUp() {
    root = Files.createTempDirectory("skillbill-push-recovery")
    remote = Files.createDirectory(root.resolve("remote"))
    repo = Files.createDirectory(root.resolve("repo"))
    git(remote, "init", "--bare")
    git(repo, "init", "-b", branch)
    git(repo, "config", "user.email", "runtime@example.test")
    git(repo, "config", "user.name", "Runtime")
    git(repo, "config", "commit.gpgsign", "false")
    git(repo, "remote", "add", "origin", remote.toString())
    Files.writeString(repo.resolve("file.txt"), "base\n")
    git(repo, "add", "file.txt")
    git(repo, "commit", "-m", "initial")
  }

  @AfterTest
  fun tearDown() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun `lost push acknowledgement completes from live push destination and restores upstream`() {
    val pushRemote = Files.createDirectory(root.resolve("push-remote"))
    git(pushRemote, "init", "--bare")
    git(repo, "config", "remote.origin.pushurl", pushRemote.toString())
    var pushes = 0
    val result =
      gitPushBranch(repo, branch, withLease = false) { args, deadline ->
        val actual = execute(args, deadline)
        if (args.first() == "push") {
          pushes++
          assertEquals(0, actual.exitCode)
          git(repo, "branch", "--unset-upstream")
          git(repo, "update-ref", "-d", "refs/remotes/origin/$branch")
          timedOut()
        } else {
          actual
        }
      }

    assertTrue(result is WorkflowGitOperationResult.Ok, result.error)
    assertEquals(1, pushes)
    assertEquals(git(repo, "rev-parse", "HEAD"), git(pushRemote, "rev-parse", "refs/heads/$branch"))
    assertEquals("origin/$branch", git(repo, "rev-parse", "--abbrev-ref", "@{upstream}"))
    assertEquals(git(repo, "rev-parse", "HEAD"), git(repo, "rev-parse", "@{upstream}"))
    assertEquals("", git(repo, "status", "--porcelain"))
  }

  @Test
  fun `unpublished timeout retries the same commit without force and verifies another lost acknowledgement`() {
    var pushes = 0
    val originalHead = git(repo, "rev-parse", "HEAD")
    val result =
      gitPushBranch(repo, branch, withLease = true) { args, deadline ->
        if (args.first() != "push") {
          execute(args, deadline)
        } else {
          pushes++
          if (pushes == 2) {
            assertFalse(args.any { it.startsWith("--force") })
            assertEquals(45L, deadline)
            assertEquals(0, execute(args, deadline).exitCode)
          }
          timedOut()
        }
      }

    assertTrue(result is WorkflowGitOperationResult.Ok, result.error)
    assertEquals(2, pushes)
    assertEquals(originalHead, git(repo, "rev-parse", "HEAD"))
    assertEquals(originalHead, git(remote, "rev-parse", "refs/heads/$branch"))
    assertEquals("origin/$branch", git(repo, "rev-parse", "--abbrev-ref", "@{upstream}"))
  }

  @Test
  fun `recovery preserves a diverged remote instead of repeating a force push`() {
    git(repo, "push", "origin", branch)
    val originalHead = git(repo, "rev-parse", "HEAD")
    Files.writeString(repo.resolve("file.txt"), "remote change\n")
    git(repo, "commit", "-am", "remote change")
    git(repo, "push", "origin", branch)
    val remoteHead = git(repo, "rev-parse", "HEAD")
    git(repo, "reset", "--hard", originalHead)
    Files.writeString(repo.resolve("file.txt"), "local change\n")
    git(repo, "commit", "-am", "local change")
    val localHead = git(repo, "rev-parse", "HEAD")
    var pushes = 0

    val result =
      gitPushBranch(repo, branch, withLease = true) { args, deadline ->
        if (args.first() == "push" && ++pushes == 1) timedOut() else execute(args, deadline)
      }

    assertTrue(result is WorkflowGitOperationResult.Failed)
    assertContains(result.error, "Normal push retry")
    assertContains(result.error, localHead)
    assertEquals(2, pushes)
    assertEquals(remoteHead, git(remote, "rev-parse", "refs/heads/$branch"))
    assertEquals(localHead, git(repo, "rev-parse", "HEAD"))
  }

  @Test
  fun `offline recovery stops after one retry and retains the original timeout and commit`() {
    val originalHead = git(repo, "rev-parse", "HEAD")
    var pushes = 0
    val result =
      gitPushBranch(repo, branch, withLease = false) { args, deadline ->
        when (args.first()) {
          "push" -> {
            pushes++
            timedOut()
          }
          "ls-remote" -> GitProcessResult("connection refused", readFailure = null, exitCode = 128)
          else -> execute(args, deadline)
        }
      }

    assertTrue(result is WorkflowGitOperationResult.Failed)
    assertContains(result.error, "timed out")
    assertContains(result.error, "connection refused")
    assertContains(result.error, originalHead)
    assertEquals(2, pushes)
    assertEquals(originalHead, git(repo, "rev-parse", "HEAD"))
  }

  @Test
  fun `moving local branch after publication cannot produce a receipt for its new unpublished head`() {
    var pushes = 0
    val originalHead = git(repo, "rev-parse", "HEAD")
    val result =
      gitPushBranch(repo, branch, withLease = false) { args, deadline ->
        val actual = execute(args, deadline)
        if (args.first() == "push") {
          pushes++
          Files.writeString(repo.resolve("file.txt"), "new local change\n")
          git(repo, "commit", "-am", "concurrent local commit")
          timedOut()
        } else {
          actual
        }
      }

    assertTrue(result is WorkflowGitOperationResult.Failed)
    assertContains(result.error, "branch moved")
    assertEquals(1, pushes)
    assertEquals(originalHead, git(remote, "rev-parse", "refs/heads/$branch"))
    assertTrue(originalHead != git(repo, "rev-parse", "HEAD"))
  }

  @Test
  fun `changed push destination cannot mark an unpublished destination as synchronized`() {
    val replacement = Files.createDirectory(root.resolve("replacement"))
    git(replacement, "init", "--bare")
    val result =
      gitPushBranch(repo, branch, withLease = false) { args, deadline ->
        val actual = execute(args, deadline)
        if (args.first() == "push") {
          git(repo, "config", "remote.origin.pushurl", replacement.toString())
          timedOut()
        } else {
          actual
        }
      }

    assertTrue(result is WorkflowGitOperationResult.Failed)
    assertContains(result.error, "push destination changed")
    assertEquals("", git(replacement, "for-each-ref", "refs/heads/$branch"))
    assertEquals(git(repo, "rev-parse", "HEAD"), git(remote, "rev-parse", "refs/heads/$branch"))
  }

  private fun execute(
    args: List<String>,
    deadline: Long,
  ): GitProcessResult = invokeGitProcess(repo, args, stdin = null, deadlineSeconds = deadline)

  private fun timedOut(): GitProcessResult = GitProcessResult("", readFailure = null, timedOut = true)
}
