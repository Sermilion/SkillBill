package skillbill.infrastructure.workflow.filesystem

import skillbill.ports.diff.model.ReviewDiffQuery
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSystemDiffResolverTest {
  private val resolver = FileSystemDiffResolver()

  @AfterTest
  fun clearInterruptFlag() {
    Thread.interrupted()
  }

  @Test
  fun `a rejected exit is unavailable while a no-index untracked diff is a patch`() {
    val root = repoWithCommit()
    val orphanCommit = orphanCommit(root)
    Files.writeString(root.resolve("new.txt"), "hello\n")

    assertNull(resolver.mergeBase(root, orphanCommit))
    val patch = assertNotNull(resolver.diff(root, ReviewDiffQuery.UntrackedFile("new.txt")))
    assertTrue("+hello" in patch, patch)
    assertEquals(listOf("new.txt"), resolver.untrackedPaths(root))
  }

  @Test
  fun `an empty diff is a successful empty string and an unknown revision is unavailable`() {
    val root = repoWithCommit()
    val head = assertNotNull(resolver.resolveCommit(root, "HEAD"))

    assertEquals("", resolver.diff(root, ReviewDiffQuery.CommitRange(head, head)))
    assertNull(resolver.diff(root, ReviewDiffQuery.CommitRange("no-such-revision", head)))
  }

  @Test
  fun `an interrupted thread propagates the interruption and keeps its flag`() {
    val root = repoWithCommit()

    Thread.currentThread().interrupt()
    assertFailsWith<InterruptedException> { resolver.resolveCommit(root, "HEAD") }

    assertTrue(Thread.currentThread().isInterrupted)
  }

  private fun repoWithCommit(): Path {
    val root = Files.createTempDirectory("diff-resolver")
    git(root, "init", "-b", "main")
    Files.writeString(root.resolve("a.txt"), "a\n")
    git(root, "add", "a.txt")
    commit(root, "first")
    return root
  }

  private fun orphanCommit(root: Path): String {
    git(root, "checkout", "--orphan", "unrelated")
    git(root, "rm", "-rf", ".")
    commit(root, "unrelated root")
    val sha = git(root, "rev-parse", "HEAD").trim()
    git(root, "checkout", "main")
    return sha
  }

  private fun commit(
    root: Path,
    message: String,
  ) {
    git(
      root,
      "-c",
      "user.name=Test",
      "-c",
      "user.email=test@example.com",
      "-c",
      "commit.gpgsign=false",
      "commit",
      "--allow-empty",
      "-m",
      message,
    )
  }

  private fun git(
    root: Path,
    vararg args: String,
  ): String {
    val process =
      ProcessBuilder(listOf("git") + args)
        .directory(root.toFile())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
      "git ${args.joinToString(" ")} failed: $output"
    }
    return output
  }

  private companion object {
    const val GIT_TIMEOUT_SECONDS = 60L
  }
}
