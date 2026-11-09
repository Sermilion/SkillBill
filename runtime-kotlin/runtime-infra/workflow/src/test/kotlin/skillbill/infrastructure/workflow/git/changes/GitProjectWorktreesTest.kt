package skillbill.infrastructure.workflow.git.changes

import skillbill.infrastructure.workflow.git.git
import skillbill.ports.workchanges.model.WorkTreeProjectResult
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class GitProjectWorktreesTest {
  private val home = Files.createTempDirectory("project-worktrees")
  private val adapter = GitWorkTreeSnapshotAdapter()

  @AfterTest
  fun cleanUp() {
    home.toFile().deleteRecursively()
  }

  @Test
  fun `linked checkouts share a project while a clone is separate and the bare directory is excluded`() {
    val original = Files.createDirectory(home.resolve("original"))
    val bare = home.resolve("bare.git")
    val linked = home.resolve("linked")
    val clone = home.resolve("clone")
    git(original, "init", "--initial-branch", "main")
    git(original, "config", "user.email", "test@example.com")
    git(original, "config", "user.name", "Test")
    original.resolve("file.txt").writeText("initial\n")
    git(original, "add", ".")
    git(original, "commit", "-m", "initial")
    git(home, "clone", "--bare", original.toString(), bare.toString())
    git(bare, "worktree", "add", linked.toString(), "main")
    git(home, "clone", bare.toString(), clone.toString())

    val project = assertIs<WorkTreeProjectResult.Listed>(adapter.projectWorktrees(linked.toString()))
    val independent = assertIs<WorkTreeProjectResult.Listed>(adapter.projectWorktrees(clone.toString()))
    assertEquals(bare.toRealPath().toString(), project.projectId)
    assertEquals(linked.toRealPath().toString(), project.checkoutRoot)
    assertEquals(listOf(linked.toRealPath().toString()), project.worktreeRoots)
    assertNotEquals(project.projectId, independent.projectId)
    assertEquals(listOf(clone.toRealPath().toString()), independent.worktreeRoots)
  }
}
