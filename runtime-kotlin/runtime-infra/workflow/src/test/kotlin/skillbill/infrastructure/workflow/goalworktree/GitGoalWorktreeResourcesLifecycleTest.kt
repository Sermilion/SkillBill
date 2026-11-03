package skillbill.infrastructure.workflow.goalworktree

import org.junit.jupiter.api.io.TempDir
import skillbill.infrastructure.workflow.git.runGit
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.time.NoopRuntimeTimingPort
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.time.model.RuntimeWaitResult
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitGoalWorktreeResourcesLifecycleTest {
  @TempDir
  lateinit var temp: Path

  private val repo: Path by lazy {
    Files.createDirectories(temp.toRealPath().resolve("repo")).also { root ->
      runGit(root, "init", "-b", "main")
      runGit(root, "config", "user.email", "test@example.com")
      runGit(root, "config", "user.name", "Test")
      Files.writeString(root.resolve("tracked.txt"), "committed\n")
      runGit(root, "add", ".")
      runGit(root, "commit", "-m", "initial")
    }
  }

  private val resources: GitGoalWorktreeResources by lazy { resourcesWith(NoopRuntimeTimingPort) }

  private fun resourcesWith(timing: RuntimeTimingPort): GitGoalWorktreeResources =
    GitGoalWorktreeResources(
      EnvironmentContext(
        environment = mapOf(STATE_DIR_ENVIRONMENT_KEY to temp.resolve("state").toString()),
        userHome = temp.resolve("home"),
      ),
      NoopRuntimeDiagnostics,
      timing,
    )

  private fun created(issueKey: String = "SKILL-1"): Path =
    assertIs<GoalWorktreeResult.Created>(resources.createOrAdopt(repo, issueKey, null, "main")).path

  private fun markInitializing(): Path =
    Files.writeString(repo.resolve(".git/worktrees/checkout/locked"), "initializing\n")

  @Test
  fun `a worktree git still marks as initializing is adopted only after git finishes it`() {
    val worktree = created()
    val lock = markInitializing()
    var waits = 0
    val waiting =
      resourcesWith(
        RuntimeTimingPort {
          waits += 1
          Files.delete(lock)
          RuntimeWaitResult.COMPLETED
        },
      )

    val adopted = assertIs<GoalWorktreeResult.Adopted>(waiting.createOrAdopt(repo, "SKILL-1", worktree, "main"))

    assertEquals(worktree, adopted.path)
    assertEquals(1, waits)
  }

  @Test
  fun `a worktree that never finishes initializing fails naming the path and stays untouched`() {
    val worktree = created()
    markInitializing()
    Files.writeString(worktree.resolve("tracked.txt"), "uncommitted\n")

    val failed = assertIs<GoalWorktreeResult.Failed>(resources.createOrAdopt(repo, "SKILL-1", worktree, "main"))

    assertTrue(worktree.toString() in failed.reason, failed.reason)
    assertEquals("uncommitted\n", Files.readString(worktree.resolve("tracked.txt")))
  }

  @Test
  fun `a revision shaped like an option never reaches git worktree add`() {
    val failed = assertIs<GoalWorktreeResult.Failed>(resources.createOrAdopt(repo, "SKILL-1", null, "--force"))

    assertTrue("--force" in failed.reason, failed.reason)
    assertTrue(Files.notExists(temp.resolve("state").resolve("goals")))
  }

  @Test
  fun `adopting a retained worktree keeps its uncommitted edits`() {
    val worktree = created()
    Files.writeString(worktree.resolve("tracked.txt"), "uncommitted\n")

    val adopted = assertIs<GoalWorktreeResult.Adopted>(resources.createOrAdopt(repo, "SKILL-1", worktree, "main"))

    assertEquals(worktree, adopted.path)
    assertEquals("uncommitted\n", Files.readString(worktree.resolve("tracked.txt")))
  }

  @Test
  fun `a listed worktree whose directory is gone is created again`() {
    val worktree = created()
    worktree.toFile().deleteRecursively()

    val recreated = assertIs<GoalWorktreeResult.Created>(resources.createOrAdopt(repo, "SKILL-1", worktree, "main"))

    assertEquals("committed\n", Files.readString(recreated.path.resolve("tracked.txt")))
  }

  @Test
  fun `an unlisted directory fails naming the path and stays untouched`() {
    val occupied = Files.createDirectories(temp.toRealPath().resolve("occupied"))
    val keep = Files.writeString(occupied.resolve("keep.txt"), "keep\n")

    val failed = assertIs<GoalWorktreeResult.Failed>(resources.createOrAdopt(repo, "SKILL-1", occupied, "main"))

    assertTrue(occupied.toString() in failed.reason, failed.reason)
    assertEquals("keep\n", Files.readString(keep))
  }

  @Test
  fun `checkoutHolding names the record that holds the branch`() {
    val other = temp.toRealPath().resolve("other")
    runGit(repo, "worktree", "add", "-b", "feat/other", other.toString(), "main")
    val goalWorktree = created()

    assertEquals(repo, resources.checkoutHolding(goalWorktree, "main"))
    assertEquals(other, resources.checkoutHolding(goalWorktree, "feat/other"))
    assertEquals(null, resources.checkoutHolding(goalWorktree, "feat/absent"))
  }
}
