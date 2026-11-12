package skillbill.infrastructure.workflow.branchworktree

import org.junit.jupiter.api.io.TempDir
import skillbill.infrastructure.workflow.git.runGit
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeCreation
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolder
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolderKind
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeRelease
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitBranchWorktreeResourcesLifecycleTest {
  @TempDir
  lateinit var temp: Path

  private val root: Path by lazy { temp.toRealPath() }

  private val repo: Path by lazy {
    val origin = Files.createDirectories(root.resolve("origin.git"))
    runGit(origin, "init", "--bare", "-b", "main")
    Files.createDirectories(root.resolve("repo")).also { repo ->
      runGit(repo, "init", "-b", "main")
      runGit(repo, "config", "user.email", "test@example.com")
      runGit(repo, "config", "user.name", "Test")
      runGit(repo, "config", "commit.gpgsign", "false")
      Files.writeString(repo.resolve("tracked.txt"), "committed\n")
      runGit(repo, "add", ".")
      runGit(repo, "commit", "-m", "initial")
      runGit(repo, "remote", "add", "origin", origin.toString())
      runGit(repo, "push", "origin", "main")
    }
  }

  private val resources by lazy {
    GitBranchWorktreeResources(
      EnvironmentContext(
        environment = mapOf(STATE_DIR_ENVIRONMENT_KEY to temp.resolve("state").toString()),
        userHome = temp.resolve("home"),
      ),
      NoopRuntimeDiagnostics,
    )
  }

  private val review = BranchWorktreeHolder(BranchWorktreeHolderKind.PHASE, "review")
  private val verify = BranchWorktreeHolder(BranchWorktreeHolderKind.OPERATION, "verify")

  @Test
  fun `a branch only origin has gets a tracking worktree beside the main checkout`() {
    runGit(repo, "push", "origin", "main:refs/heads/Feature/Remote_Only")

    val created = assertIs<BranchWorktreeCreation.Created>(resources.create(repo, "Feature/Remote_Only", review))

    assertEquals(root.resolve("repo-feature-remote-only"), created.path)
    assertEquals("Feature/Remote_Only", runGit(created.path, "symbolic-ref", "--short", "HEAD"))
    assertEquals("origin/Feature/Remote_Only", runGit(created.path, "rev-parse", "--abbrev-ref", "@{upstream}"))
  }

  @Test
  fun `a local branch started from a linked worktree lands beside the main checkout under a free name`() {
    runGit(repo, "branch", "local-only")
    val linked = root.resolve("elsewhere").resolve("linked")
    runGit(repo, "worktree", "add", "--detach", linked.toString())
    Files.createDirectories(root.resolve("repo-local-only"))

    val created = assertIs<BranchWorktreeCreation.Created>(resources.create(linked, "local-only", review))

    assertEquals(root.resolve("repo-local-only-2"), created.path)
    assertEquals("local-only", runGit(created.path, "symbolic-ref", "--short", "HEAD"))
  }

  @Test
  fun `a branch neither local nor on origin is missing and creates nothing`() {
    assertEquals(BranchWorktreeCreation.Missing, resources.create(repo, "nowhere", review))
    assertFalse(Files.exists(root.resolve("repo-nowhere")))
  }

  @Test
  fun `a detached worktree can use the checked out branch tip and releases cleanly`() {
    runGit(repo, "checkout", "-b", "feature/x")
    val tip = runGit(repo, "rev-parse", "HEAD")

    val path = assertIs<BranchWorktreeCreation.Created>(resources.createDetached(repo, "feature/x", review)).path

    assertEquals(tip, runGit(path, "rev-parse", "HEAD"))
    assertEquals("HEAD", runGit(path, "rev-parse", "--abbrev-ref", "HEAD"))
    assertEquals("feature/x", runGit(repo, "symbolic-ref", "--short", "HEAD"))
    assertEquals(BranchWorktreeRelease.Removed(path), resources.release(path, review))
    assertFalse(Files.exists(path))
    assertEquals("feature/x", runGit(repo, "symbolic-ref", "--short", "HEAD"))
  }

  @Test
  fun `the worktree is removed once its last holder releases it and the branch stays`() {
    runGit(repo, "branch", "shared")
    val path = assertIs<BranchWorktreeCreation.Created>(resources.create(repo, "shared", review)).path

    assertTrue(resources.hold(path, verify))
    assertFalse(resources.hold(path, verify))
    assertEquals(BranchWorktreeRelease.StillHeld, resources.release(path, review))
    assertTrue(Files.isDirectory(path))
    assertEquals(BranchWorktreeRelease.Removed(path), resources.release(path, verify))

    assertFalse(Files.exists(path))
    assertEquals("shared", runGit(repo, "branch", "--list", "shared").removePrefix("*").trim())
  }

  @Test
  fun `a created worktree with local changes is kept and says why`() {
    runGit(repo, "branch", "dirty")
    val path = assertIs<BranchWorktreeCreation.Created>(resources.create(repo, "dirty", review)).path
    Files.writeString(path.resolve("notes.txt"), "work in progress\n")

    val kept = assertIs<BranchWorktreeRelease.Kept>(resources.release(path, review))

    assertEquals(path, kept.path)
    assertTrue(kept.detail.isNotBlank())
    assertEquals("work in progress\n", Files.readString(path.resolve("notes.txt")))
  }

  @Test
  fun `a worktree skill-bill did not create is never held or removed`() {
    runGit(repo, "branch", "manual")
    val manual = root.resolve("manual")
    runGit(repo, "worktree", "add", manual.toString(), "manual")

    assertFalse(resources.hold(manual, review))
    assertEquals(BranchWorktreeRelease.NotHeld, resources.release(manual, review))
    assertEquals(BranchWorktreeRelease.NotHeld, resources.release(repo, review))
    assertTrue(Files.isDirectory(manual))
    assertTrue(Files.isDirectory(repo))
  }
}
