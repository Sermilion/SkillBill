package dev.skillbill.desktop.core.domain.model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNull

class NewWorkBranchProblemTest {
  @Test
  fun `off default checkout requires an explicit base choice and a changed branch warns again`() {
    val branches = RepositoryBranches("/repo", "main", emptyList(), emptyList(), "feature/current")
    assertContains(branches.newWorkBranchProblem(null).orEmpty(), "Switch to main")
    assertNull(branches.newWorkBranchProblem("feature/current"))
    assertNull(branches.copy(currentBranch = "main").newWorkBranchProblem(null))
    assertContains(branches.copy(currentBranch = "feature/other").newWorkBranchProblem(null).orEmpty(), "feature/other")
  }

  @Test
  fun `detached HEAD and unknown default do not silently select a base`() {
    val branches = RepositoryBranches("/repo", "main", emptyList(), emptyList())
    assertContains(branches.newWorkBranchProblem(null).orEmpty(), "detached HEAD")
    assertContains(branches.copy(currentBranch = "dev", defaultBranch = null).newWorkBranchProblem(null).orEmpty(), "Choose a base branch explicitly")
    assertNull(branches.newWorkBranchProblem("main"))
  }
}
