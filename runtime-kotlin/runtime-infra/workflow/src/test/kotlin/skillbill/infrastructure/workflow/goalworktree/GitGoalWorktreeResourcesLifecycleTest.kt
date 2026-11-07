package skillbill.infrastructure.workflow.goalworktree

import org.junit.jupiter.api.io.TempDir
import skillbill.infrastructure.workflow.git.runGit
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.time.NoopRuntimeTimingPort
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.time.model.RuntimeWaitResult
import skillbill.ports.workflow.goalworktree.model.GoalBranchCheckout
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettleRequest
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettlement
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
      runGit(root, "config", "commit.gpgsign", "false")
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

  private fun markRemoval(worktree: Path): Path {
    val attributes = Files.readAttributes(worktree, BasicFileAttributes::class.java)
    return Files.writeString(
      worktree.resolveSibling("checkout.removing"),
      "${attributes.fileKey()}\n${attributes.creationTime()}",
    )
  }

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

  @Test
  fun `settlement removes a clean published worktree and keeps its branch`() {
    val remote = temp.toRealPath().resolve("remote.git")
    Files.createDirectories(remote)
    runGit(remote, "init", "--bare", "-b", "main")
    runGit(repo, "remote", "add", "origin", remote.toString())
    runGit(repo, "push", "--set-upstream", "origin", "main")
    runGit(repo, "fetch", "origin", "main")
    val worktree = created("SKILL-2")
    val branchTip = runGit(repo, "rev-parse", "refs/heads/main")

    assertEquals(GoalWorktreeSettlement.Removed, resources.settle(GoalWorktreeSettleRequest(repo, worktree)))
    assertTrue(Files.notExists(worktree))
    assertEquals(branchTip, runGit(repo, "rev-parse", "refs/heads/main"))
  }

  @Test
  fun `settlement retains tracked edits with their reason and contents`() {
    val worktree = created("SKILL-3")
    Files.writeString(worktree.resolve("tracked.txt"), "keep this edit\n")

    val retained =
      assertIs<GoalWorktreeSettlement.Retained>(resources.settle(GoalWorktreeSettleRequest(repo, worktree)))

    assertEquals(MonitorRetentionReason.DIRTY_WORKTREE, retained.reason)
    assertEquals("keep this edit\n", Files.readString(worktree.resolve("tracked.txt")))
  }

  @Test
  fun `settlement retains untracked files`() {
    val worktree = created("SKILL-4")
    Files.writeString(worktree.resolve("untracked.txt"), "keep this file\n")

    val retained =
      assertIs<GoalWorktreeSettlement.Retained>(resources.settle(GoalWorktreeSettleRequest(repo, worktree)))

    assertEquals(MonitorRetentionReason.UNTRACKED_FILES, retained.reason)
    assertEquals("keep this file\n", Files.readString(worktree.resolve("untracked.txt")))
  }

  @Test
  fun `settlement removes a published worktree that holds only ignored files`() {
    Files.writeString(repo.resolve(".gitignore"), "build/\nlocal.properties\n")
    runGit(repo, "add", ".gitignore")
    runGit(repo, "commit", "-m", "ignore build and local properties")
    val remote = temp.toRealPath().resolve("remote-ignored-only.git")
    Files.createDirectories(remote)
    runGit(remote, "init", "--bare", "-b", "main")
    runGit(repo, "remote", "add", "origin", remote.toString())
    runGit(repo, "push", "--set-upstream", "origin", "main")
    runGit(repo, "fetch", "origin", "main")
    val worktree = created("SKILL-ignored")
    Files.createDirectories(worktree.resolve("runtime-kotlin/build"))
    Files.writeString(worktree.resolve("runtime-kotlin/build/out.txt"), "gradle\n")
    Files.writeString(worktree.resolve("local.properties"), "sdk.dir=/fake\n")

    assertEquals(GoalWorktreeSettlement.Removed, resources.settle(GoalWorktreeSettleRequest(repo, worktree)))
    assertTrue(Files.notExists(worktree))
  }

  @Test
  fun `settlement retains non-ignored untracked files when ignored files are also present`() {
    Files.writeString(repo.resolve(".gitignore"), "build/\n")
    runGit(repo, "add", ".gitignore")
    runGit(repo, "commit", "-m", "ignore build")
    val remote = temp.toRealPath().resolve("remote-ignored-untracked.git")
    Files.createDirectories(remote)
    runGit(remote, "init", "--bare", "-b", "main")
    runGit(repo, "remote", "add", "origin", remote.toString())
    runGit(repo, "push", "--set-upstream", "origin", "main")
    runGit(repo, "fetch", "origin", "main")
    val worktree = created("SKILL-mixed")
    Files.createDirectories(worktree.resolve("build"))
    Files.writeString(worktree.resolve("build/out.txt"), "ignored\n")
    Files.writeString(worktree.resolve("real-untracked.txt"), "keep this file\n")

    val retained =
      assertIs<GoalWorktreeSettlement.Retained>(resources.settle(GoalWorktreeSettleRequest(repo, worktree)))

    assertEquals(MonitorRetentionReason.UNTRACKED_FILES, retained.reason)
    assertEquals("keep this file\n", Files.readString(worktree.resolve("real-untracked.txt")))
  }

  @Test
  fun `settlement removes a published feature branch worktree and keeps the branch ref`() {
    val remote = temp.toRealPath().resolve("remote-feature-branch.git")
    Files.createDirectories(remote)
    runGit(remote, "init", "--bare", "-b", "main")
    runGit(repo, "remote", "add", "origin", remote.toString())
    runGit(repo, "push", "--set-upstream", "origin", "main")
    val worktree = created("SKILL-published-branch")
    runGit(worktree, "checkout", "-b", "feat/skill-468")
    Files.writeString(worktree.resolve("feature.txt"), "shipped\n")
    runGit(worktree, "add", "feature.txt")
    runGit(worktree, "commit", "-m", "feature work")
    runGit(worktree, "push", "--set-upstream", "origin", "feat/skill-468")
    runGit(worktree, "checkout", "--detach", "HEAD")
    val branchTip = runGit(repo, "rev-parse", "refs/heads/feat/skill-468")

    assertEquals(GoalWorktreeSettlement.Removed, resources.settle(GoalWorktreeSettleRequest(repo, worktree)))
    assertTrue(Files.notExists(worktree))
    assertEquals(branchTip, runGit(repo, "rev-parse", "refs/heads/feat/skill-468"))
  }

  private fun removePublishedGoalWorktree() {
    val remote = temp.toRealPath().resolve("remote-main-checkout.git")
    Files.createDirectories(remote)
    runGit(remote, "init", "--bare", "-b", "main")
    runGit(repo, "remote", "add", "origin", remote.toString())
    runGit(repo, "push", "--set-upstream", "origin", "main")
    val worktree = created("SKILL-9")
    runGit(worktree, "checkout", "-b", "feat/X")
    Files.writeString(worktree.resolve("goal.txt"), "from the goal\n")
    runGit(worktree, "add", "goal.txt")
    runGit(worktree, "commit", "-m", "goal work")
    runGit(worktree, "push", "--set-upstream", "origin", "feat/X")
    assertEquals(GoalWorktreeSettlement.Removed, resources.settle(GoalWorktreeSettleRequest(repo, worktree)))
  }

  @Test
  fun `a clean main checkout switches to the goal branch once its worktree is removed`() {
    removePublishedGoalWorktree()

    assertEquals(GoalBranchCheckout.CheckedOut, resources.checkoutGoalBranch(repo, "feat/X"))
    assertEquals("feat/X", runGit(repo, "symbolic-ref", "--short", "HEAD"))
    assertTrue(Files.exists(repo.resolve("goal.txt")))
  }

  @Test
  fun `a main checkout with a modified tracked file is skipped and left untouched`() {
    removePublishedGoalWorktree()
    Files.writeString(repo.resolve("tracked.txt"), "operator edit\n")

    val skipped = assertIs<GoalBranchCheckout.SkippedUncommittedChanges>(resources.checkoutGoalBranch(repo, "feat/X"))

    assertTrue("tracked.txt" in skipped.detail, skipped.detail)
    assertEquals("operator edit\n", Files.readString(repo.resolve("tracked.txt")))
    assertEquals("main", runGit(repo, "symbolic-ref", "--short", "HEAD"))
  }

  @Test
  fun `an untracked file the goal branch would overwrite fails the switch and survives`() {
    removePublishedGoalWorktree()
    Files.writeString(repo.resolve("goal.txt"), "operator file\n")

    assertIs<GoalBranchCheckout.Failed>(resources.checkoutGoalBranch(repo, "feat/X"))

    assertEquals("operator file\n", Files.readString(repo.resolve("goal.txt")))
    assertEquals("main", runGit(repo, "symbolic-ref", "--short", "HEAD"))
  }

  @Test
  fun `a merge in progress in the main checkout fails the switch and keeps the branch`() {
    removePublishedGoalWorktree()
    Files.writeString(repo.resolve(".git/MERGE_HEAD"), runGit(repo, "rev-parse", "HEAD") + "\n")

    val failed = assertIs<GoalBranchCheckout.Failed>(resources.checkoutGoalBranch(repo, "feat/X"))

    assertTrue("merge" in failed.detail, failed.detail)
    assertEquals("main", runGit(repo, "symbolic-ref", "--short", "HEAD"))
  }

  @Test
  fun `settlement finishes a removal that was interrupted part way`() {
    publishMain("remote-interrupted.git")
    val worktree = created("SKILL-interrupted")
    val marker = markRemoval(worktree)
    Files.delete(worktree.resolve("tracked.txt"))
    Files.writeString(worktree.resolve("leftover.txt"), "half deleted\n")

    assertEquals(GoalWorktreeSettlement.Removed, resources.settle(GoalWorktreeSettleRequest(repo, worktree)))
    assertTrue(Files.notExists(worktree))
    assertTrue(Files.notExists(marker))
    assertFalse(worktree.toString() in runGit(repo, "worktree", "list", "--porcelain"))
  }

  @Test
  fun `a failed removal that git finished leaves no marker and keeps the worktree guarded`() {
    publishMain("remote-locked.git")
    val worktree = created("SKILL-locked")
    runGit(repo, "worktree", "lock", worktree.toString())

    val retained =
      assertIs<GoalWorktreeSettlement.Retained>(resources.settle(GoalWorktreeSettleRequest(repo, worktree)))

    assertEquals(MonitorRetentionReason.CLEANUP_FAILED, retained.reason)
    assertTrue(Files.notExists(worktree.resolveSibling("checkout.removing")))
    runGit(repo, "worktree", "unlock", worktree.toString())
    Files.writeString(worktree.resolve("tracked.txt"), "later edit\n")
    assertEquals(
      MonitorRetentionReason.DIRTY_WORKTREE,
      assertIs<GoalWorktreeSettlement.Retained>(resources.settle(GoalWorktreeSettleRequest(repo, worktree))).reason,
    )
  }

  @Test
  fun `adopting a worktree whose removal was interrupted checks it out again`() {
    val worktree = created("SKILL-readopt")
    val marker = markRemoval(worktree)
    Files.delete(worktree.resolve("tracked.txt"))

    val recreated =
      assertIs<GoalWorktreeResult.Created>(resources.createOrAdopt(repo, "SKILL-readopt", worktree, "main"))

    assertEquals("committed\n", Files.readString(recreated.path.resolve("tracked.txt")))
    assertTrue(Files.notExists(marker))
  }

  @Test
  fun `a surviving removal marker cannot delete edits after checkout recreation and resume`() {
    publishMain("remote-recreated.git")
    val worktree = created("SKILL-recreated")
    val marker = markRemoval(worktree)
    runGit(repo, "worktree", "remove", "--force", worktree.toString())

    assertIs<GoalWorktreeResult.Created>(resources.createOrAdopt(repo, "SKILL-recreated", worktree, "main"))
    Files.writeString(worktree.resolve("tracked.txt"), "new operator work\n")
    Files.writeString(worktree.resolve("new.txt"), "new untracked work\n")

    assertIs<GoalWorktreeResult.Adopted>(resources.createOrAdopt(repo, "SKILL-recreated", worktree, "main"))
    assertEquals("new operator work\n", Files.readString(worktree.resolve("tracked.txt")))
    assertEquals("new untracked work\n", Files.readString(worktree.resolve("new.txt")))
    assertTrue(Files.notExists(marker))
    assertEquals(
      MonitorRetentionReason.DIRTY_WORKTREE,
      assertIs<GoalWorktreeSettlement.Retained>(resources.settle(GoalWorktreeSettleRequest(repo, worktree))).reason,
    )
  }

  @Test
  fun `a removal marker for another directory cannot delete a replacement checkout`() {
    val worktree = created("SKILL-replaced")
    markRemoval(worktree)
    Files.move(worktree, temp.toRealPath().resolve("old-checkout"))
    runGit(repo, "worktree", "prune")
    runGit(repo, "worktree", "add", "--detach", worktree.toString(), "main")
    Files.writeString(worktree.resolve("tracked.txt"), "unpublished replacement work\n")
    runGit(worktree, "add", ".")
    runGit(worktree, "commit", "-m", "replacement work")
    val head = runGit(worktree, "rev-parse", "HEAD")

    assertEquals(
      MonitorRetentionReason.UNPUBLISHED_COMMITS,
      assertIs<GoalWorktreeSettlement.Retained>(resources.settle(GoalWorktreeSettleRequest(repo, worktree))).reason,
    )
    Files.writeString(worktree.resolve("tracked.txt"), "new operator edits\n")
    assertIs<GoalWorktreeResult.Adopted>(resources.createOrAdopt(repo, "SKILL-replaced", worktree, "main"))
    assertEquals(head, runGit(worktree, "rev-parse", "HEAD"))
    assertEquals("new operator edits\n", Files.readString(worktree.resolve("tracked.txt")))
  }

  @Test
  fun `an empty legacy removal marker cannot authorize deleting retained edits`() {
    val worktree = created("SKILL-legacy-marker")
    Files.createFile(worktree.resolveSibling("checkout.removing"))
    Files.writeString(worktree.resolve("tracked.txt"), "keep this edit\n")

    assertIs<GoalWorktreeResult.Adopted>(resources.createOrAdopt(repo, "SKILL-legacy-marker", worktree, "main"))
    assertEquals(
      MonitorRetentionReason.DIRTY_WORKTREE,
      assertIs<GoalWorktreeSettlement.Retained>(resources.settle(GoalWorktreeSettleRequest(repo, worktree))).reason,
    )
    assertEquals("keep this edit\n", Files.readString(worktree.resolve("tracked.txt")))
  }

  private fun publishMain(remoteName: String) {
    val remote = Files.createDirectories(temp.toRealPath().resolve(remoteName))
    runGit(remote, "init", "--bare", "-b", "main")
    runGit(repo, "remote", "add", "origin", remote.toString())
    runGit(repo, "push", "--set-upstream", "origin", "main")
  }

  @Test
  fun `settlement retains a clean commit that has no remote tracking ref`() {
    val worktree = created("SKILL-5")
    Files.writeString(worktree.resolve("private-commit.txt"), "keep this commit\n")
    runGit(worktree, "add", "private-commit.txt")
    runGit(worktree, "commit", "-m", "unpublished work")

    val retained =
      assertIs<GoalWorktreeSettlement.Retained>(resources.settle(GoalWorktreeSettleRequest(repo, worktree)))

    assertEquals(MonitorRetentionReason.UNPUBLISHED_COMMITS, retained.reason)
    assertEquals("keep this commit\n", Files.readString(worktree.resolve("private-commit.txt")))
  }
}
