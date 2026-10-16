package skillbill.infrastructure.workflow.process

import org.junit.jupiter.api.Timeout
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitProcessInvocationTest {
  @Test
  fun `porcelain status preserves the leading index column so repair manifests retain the whole path`() {
    val repoRoot = newRepo("skillbill-git-porcelain")
    try {
      Files.writeString(repoRoot.resolve("source.txt"), "before")
      runGit(repoRoot, "add", "source.txt")
      runGit(repoRoot, "commit", "-m", "Initial source")
      Files.writeString(repoRoot.resolve("source.txt"), "after")

      val result = invokeGitProcess(repoRoot, listOf("status", "--porcelain", "-uall"), stdin = null)

      assertEquals(0, result.exitCode)
      assertEquals(" M source.txt", result.output)
    } finally {
      repoRoot.toFile().deleteRecursively()
    }
  }

  @Test
  fun `stdin reaches git and round trips through the object store`() {
    val repoRoot = newRepo("skillbill-git-process-stdin")
    val payload = "stdin payload\nsecond line\n"

    val written = invokeGitProcess(repoRoot, listOf("hash-object", "-w", "--stdin"), payload.toByteArray())
    assertEquals(0, written.exitCode, written.output)
    assertNull(written.readFailure)

    val readBack = invokeGitProcess(repoRoot, listOf("cat-file", "blob", written.output), stdin = null)
    assertEquals(0, readBack.exitCode, readBack.output)
    assertEquals(payload.trim(), readBack.output)
  }

  @Test
  @Timeout(value = 20, unit = TimeUnit.SECONDS)
  fun `a selected diff over budget stops reading early and reports truncated hunks`() {
    val repoRoot = newRepo("skillbill-git-process-bounded")
    val tracked = repoRoot.resolve("tracked.txt")
    Files.writeString(tracked, (1..5_000).joinToString("\n") { "line $it" } + "\n")
    runGit(repoRoot, "add", ".")
    runGit(repoRoot, "commit", "-m", "initial")
    Files.writeString(tracked, (1..5_000).joinToString("\n") { "changed $it" } + "\n")

    val request =
      WorkflowSelectedDiffHunksRequest(paths = listOf("tracked.txt"), maxHunks = 1, maxLines = 5, maxBytes = 200)
    val result =
      readSelectedDiffHunks(
        repoRoot = repoRoot,
        args = listOf("diff", "--unified=3", "--") + request.paths,
        staged = false,
        budget = SelectedDiffBudget(request),
      )

    assertEquals(WorkflowGitOperationStatus.OK, result.status, result.error)
    assertTrue(result.hunks.truncated, "expected the bounded read to stop before the end of the diff")
    assertTrue(result.hunks.hunks.single().lines.size <= 5)
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  fun `a git command past its deadline reports a timeout`() {
    val repoRoot = newRepo("skillbill-git-process-timeout")

    val result =
      invokeGitProcess(
        repoRoot = repoRoot,
        args = listOf("-c", "alias.stall=!sleep 20", "stall"),
        stdin = null,
        deadlineSeconds = 1L,
      )

    assertTrue(result.timedOut)
    assertEquals(-1, result.exitCode)
  }

  private fun newRepo(prefix: String): Path {
    val repoRoot = Files.createTempDirectory(prefix)
    runGit(repoRoot, "init")
    runGit(repoRoot, "config", "user.email", "skill-bill@example.test")
    runGit(repoRoot, "config", "user.name", "Skill Bill")
    runGit(repoRoot, "config", "commit.gpgsign", "false")
    return repoRoot
  }

  private fun runGit(
    repoRoot: Path,
    vararg args: String,
  ) {
    val result = invokeGitProcess(repoRoot, args.toList(), stdin = null)
    check(result.exitCode == 0) { "git ${args.joinToString(" ")} failed: ${result.output}" }
  }
}
