package skillbill.infrastructure.workflow.git.branches

import skillbill.infrastructure.workflow.git.git
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workflow.gitops.model.RepositoryBranchRef
import skillbill.ports.workflow.gitops.model.RepositoryBranchesResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GitRepositoryBranchesAdapterTest {
  private val adapter = GitRepositoryBranchesAdapter()
  private val folders = mutableListOf<Path>()

  @AfterTest
  fun tearDown() {
    folders.forEach { it.toFile().deleteRecursively() }
  }

  @Test
  fun `lists local and remote branches newest first without duplicates`() {
    val repo = repository("main")
    git(repo, "commit", "--allow-empty", "-m", "base")
    git(repo, "remote", "add", "origin", "https://invalid.invalid/skillbill.git")
    val tree = git(repo, "rev-parse", "HEAD^{tree}").trim()
    val refsByDate =
      listOf(
        "refs/heads/feature-a" to 100L,
        "refs/heads/feature-b" to 200L,
        "refs/heads/main" to 300L,
        "refs/remotes/origin/rel" to 400L,
        "refs/remotes/upstream/feature-a" to 500L,
        "refs/remotes/origin/feature-a" to 600L,
        "refs/remotes/origin/main" to 700L,
      )
    refsByDate.forEach { (ref, offset) -> git(repo, "update-ref", ref, commitAt(repo, tree, BASE_DATE + offset)) }
    git(repo, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
    val folder = Files.createDirectory(repo.resolve("sub"))

    assertEquals(
      RepositoryBranchesResult.Listed(
        repositoryRoot = repo.toRealPath().toString(),
        defaultBranch = "main",
        currentBranch = "main",
        local =
          listOf(
            RepositoryBranchRef("feature-b", BASE_DATE + 200),
            RepositoryBranchRef("feature-a", BASE_DATE + 100),
          ),
        remote =
          listOf(
            RepositoryBranchRef("upstream/feature-a", BASE_DATE + 500),
            RepositoryBranchRef("origin/rel", BASE_DATE + 400),
          ),
      ),
      adapter.branches(folder),
    )
  }

  @Test
  fun `an unknown default branch is still listed`() {
    val repo = repository("dev")
    val unborn = assertIs<RepositoryBranchesResult.Listed>(adapter.branches(repo))
    assertEquals(emptyList(), unborn.local)
    assertEquals(emptyList(), unborn.remote)

    git(repo, "commit", "--allow-empty", "-m", "base")

    val listed = assertIs<RepositoryBranchesResult.Listed>(adapter.branches(repo))
    assertEquals(null, listed.defaultBranch)
    assertEquals(listOf("dev"), listed.local.map(RepositoryBranchRef::name))
  }

  @Test
  fun `a folder outside git is not a repository`() {
    val folder = tempFolder()
    val missing = folder.resolve("missing")

    assertEquals(RepositoryBranchesResult.NotARepository(folder.toString()), adapter.branches(folder))
    assertEquals(RepositoryBranchesResult.NotARepository(missing.toString()), adapter.branches(missing))
  }

  @Test
  fun `current branch follows checkout and detached HEAD instead of default branch`() {
    val repo = repository("main")
    git(repo, "commit", "--allow-empty", "-m", "base")
    git(repo, "switch", "-c", "feature/current")
    val feature = assertIs<RepositoryBranchesResult.Listed>(adapter.branches(repo))
    assertEquals("main", feature.defaultBranch)
    assertEquals("feature/current", feature.currentBranch)

    assertIs<WorkflowGitOperationResult.Failed>(adapter.switchToDefaultBranch(repo, "stale"))
    assertEquals("feature/current", assertIs<RepositoryBranchesResult.Listed>(adapter.branches(repo)).currentBranch)
    assertIs<WorkflowGitOperationResult.Ok>(adapter.switchToDefaultBranch(repo, "feature/current"))
    assertEquals("main", assertIs<RepositoryBranchesResult.Listed>(adapter.branches(repo)).currentBranch)

    git(repo, "checkout", "--detach")
    assertEquals(null, assertIs<RepositoryBranchesResult.Listed>(adapter.branches(repo)).currentBranch)
  }

  @Test
  fun `switch refusal preserves conflicting local edits and untracked files`() {
    val repo = repository("main")
    val file = repo.resolve("shared.txt")
    Files.writeString(file, "base")
    git(repo, "add", "shared.txt")
    git(repo, "commit", "-m", "base")
    git(repo, "switch", "-c", "feature/current")
    Files.writeString(file, "feature")
    git(repo, "commit", "-am", "feature")
    Files.writeString(file, "operator edits")
    Files.writeString(repo.resolve("notes.txt"), "untracked notes")

    assertIs<WorkflowGitOperationResult.Failed>(adapter.switchToDefaultBranch(repo, "feature/current"))
    assertEquals("operator edits", Files.readString(file))
    assertEquals("untracked notes", Files.readString(repo.resolve("notes.txt")))
    assertEquals("feature/current", assertIs<RepositoryBranchesResult.Listed>(adapter.branches(repo)).currentBranch)
  }

  private fun repository(initialBranch: String): Path =
    tempFolder().also { repo ->
      git(repo, "init", "--initial-branch", initialBranch)
      git(repo, "config", "user.email", "runtime@skill-bill.test")
      git(repo, "config", "user.name", "Skill Bill Runtime")
    }

  private fun tempFolder(): Path = Files.createTempDirectory("skillbill-branches").also(folders::add)

  private fun commitAt(
    repo: Path,
    tree: String,
    epochSeconds: Long,
  ): String {
    val date = "$epochSeconds +0000"
    val env = mapOf("GIT_COMMITTER_DATE" to date, "GIT_AUTHOR_DATE" to date)
    return runGitCommand(repo, env, "commit-tree", tree, "-m", "commit at $epochSeconds").value.trim()
  }

  private companion object {
    const val BASE_DATE = 1_700_000_000L
  }
}
