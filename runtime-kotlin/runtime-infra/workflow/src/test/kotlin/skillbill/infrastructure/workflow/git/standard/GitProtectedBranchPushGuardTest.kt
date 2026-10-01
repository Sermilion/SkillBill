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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitProtectedBranchPushGuardTest {
  private lateinit var repo: Path
  private lateinit var remote: Path

  @BeforeTest
  fun setUp() {
    remote = Files.createTempDirectory("skillbill-protected-remote")
    ProcessBuilder("git", "init", "--bare", "--initial-branch", "main")
      .directory(remote.toFile()).start().waitFor()
    repo = Files.createTempDirectory("skillbill-protected-push")
    git("init", "--initial-branch", "main")
    git("config", "user.email", "runtime@skill-bill.test")
    git("config", "user.name", "Skill Bill Runtime")
    git("config", "commit.gpgsign", "false")
    git("remote", "add", "origin", remote.toString())
    Files.writeString(repo.resolve("base.txt"), "base\n")
    git("add", "-A")
    git("commit", "-m", "base")
    git("push", "-u", "origin", "main")
  }

  @AfterTest
  fun tearDown() {
    repo.toFile().deleteRecursively()
    remote.toFile().deleteRecursively()
  }

  @Test
  fun `a lease push to a protected branch is refused before git runs`() {
    val remoteHeadBefore = remoteHead()

    val pushed = gitPushBranch(repo, "main", withLease = true)

    assertFalse(pushed is WorkflowGitOperationResult.Ok)
    assertContains(pushed.error.orEmpty(), "Refusing to force-push protected branch 'main'")
    assertEquals(remoteHeadBefore, remoteHead())
  }

  @Test
  fun `a lease push to a feature branch still works`() {
    git("checkout", "-b", "feat/guarded")
    Files.writeString(repo.resolve("base.txt"), "changed\n")
    git("add", "-A")
    git("commit", "-m", "change")

    assertTrue(gitPushBranch(repo, "feat/guarded", withLease = true) is WorkflowGitOperationResult.Ok)
  }

  private fun remoteHead(): String {
    val process =
      ProcessBuilder("git", "rev-parse", "HEAD")
        .directory(remote.toFile()).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    return output
  }

  private fun git(vararg args: String) {
    val process =
      ProcessBuilder(listOf("git") + args)
        .directory(repo.toFile()).redirectErrorStream(true).start()
    process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed" }
  }
}
