package skillbill.engine.featuretask.lifecycle.branch

import skillbill.ports.workflow.gitops.DefaultBranchGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class BaseBranchResolutionTest {
  private val repoRoot = Path.of("repo")

  @Test
  fun `a recorded base branch wins over the repository default`() {
    assertEquals("release", defaultBranch("develop").baseBranchOrDefault(repoRoot, " release "))
  }

  @Test
  fun `a run without a recorded base integrates into the repository default branch`() {
    assertEquals("develop", defaultBranch("develop").baseBranchOrDefault(repoRoot, null))
    assertEquals("develop", defaultBranch("develop").baseBranchOrDefault(repoRoot, " "))
  }

  @Test
  fun `an unreadable repository default falls back to main`() {
    val unreadable =
      object : DefaultBranchGitOperations {
        override fun defaultBranch(repoRoot: Path) = WorkflowGitOperationResult.Failed(error = "no origin")
      }

    assertEquals("main", unreadable.baseBranchOrDefault(repoRoot, null))
  }

  private fun defaultBranch(name: String) =
    object : DefaultBranchGitOperations {
      override fun defaultBranch(repoRoot: Path) = WorkflowGitOperationResult.Ok(value = name)
    }
}
