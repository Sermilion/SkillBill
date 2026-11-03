package skillbill.engine.goal

import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalRepositoryIdentityFiltersTest {
  @Test
  fun `a missing repo root reads unfiltered`() {
    assertEquals(listOf(null), goalRepositoryIdentityFilters(null, TestRepositoryEnclosingRoot))
  }

  @Test
  fun `a known repo root filters by primary identity then exact checkout identity`() {
    val port =
      object : RepositoryEnclosingRootPort by TestRepositoryEnclosingRoot {
        override fun primaryRepositoryIdentity(repoRoot: Path): String = "repo-root-realpath-v1:/main"

        override fun repositoryIdentity(repoRoot: Path): String = "repo-root-realpath-v1:/worktree"
      }

    assertEquals(
      listOf("repo-root-realpath-v1:/main", "repo-root-realpath-v1:/worktree"),
      goalRepositoryIdentityFilters(Path.of("/tmp/goal-identity-filters"), port),
    )
  }
}
