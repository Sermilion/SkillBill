package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.git.git
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitInProgressOperationTest {
  private lateinit var repo: Path
  private lateinit var worktree: Path

  @BeforeTest
  fun setUp() {
    repo = Files.createTempDirectory("skillbill-in-progress-repo")
    git(repo, "init", "--initial-branch", "main")
    git(repo, "config", "user.email", "runtime@skill-bill.test")
    git(repo, "config", "user.name", "Skill Bill Runtime")
    repo.resolve("Shared.kt").writeText("base\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-m", "base")
    worktree = Files.createTempDirectory("skillbill-in-progress-worktree").resolve("feature")
    git(repo, "worktree", "add", "-b", "feat/goal", worktree.toString())
    worktree.resolve("Shared.kt").writeText("feature\n")
    git(worktree, "commit", "-am", "feature")
    repo.resolve("Shared.kt").writeText("main\n")
    git(repo, "commit", "-am", "main")
  }

  @AfterTest
  fun tearDown() {
    worktree.parent.toFile().deleteRecursively()
    repo.toFile().deleteRecursively()
  }

  @Test
  fun `a resolved but uncommitted merge in a linked worktree is still in progress until committed`() {
    val conflicted =
      ProcessBuilder("git", "-C", worktree.toString(), "merge", "main").redirectErrorStream(true).start()
    conflicted.inputStream.readAllBytes()
    assertEquals(1, conflicted.waitFor())
    worktree.resolve("Shared.kt").writeText("resolved\n")
    git(worktree, "add", "Shared.kt")

    val resolved = GitStandardWorkflowGitWorktreeOperations.inProgressOperation(worktree)
    assertTrue(resolved.ok, resolved.error)
    assertEquals("merge", resolved.value)

    git(worktree, "commit", "--no-edit")

    val committed = GitStandardWorkflowGitWorktreeOperations.inProgressOperation(worktree)
    assertTrue(committed.ok, committed.error)
    assertEquals("", committed.value)
  }
}
