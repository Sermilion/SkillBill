package skillbill.engine.goal

import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalRepositoryIdentityTest {
  @Test
  fun `candidates list primary identity before exact checkout identity`() {
    val repoRoot = Path.of("/tmp/goal-identity")
    val port =
      object : RepositoryEnclosingRootPort by TestRepositoryEnclosingRoot {
        override fun primaryRepositoryIdentity(repoRoot: Path): String = "repo-root-realpath-v1:/main"

        override fun repositoryIdentity(repoRoot: Path): String = "repo-root-realpath-v1:/worktree"
      }

    assertEquals(
      listOf("repo-root-realpath-v1:/main", "repo-root-realpath-v1:/worktree"),
      goalRepositoryIdentityCandidates(repoRoot, port),
    )
  }

  @Test
  fun `candidates collapse to one entry when primary and exact identities match`() {
    val repoRoot = Path.of("/tmp/goal-identity-equal")
    val port =
      object : RepositoryEnclosingRootPort by TestRepositoryEnclosingRoot {
        override fun primaryRepositoryIdentity(repoRoot: Path): String = "repo-root-realpath-v1:/main"

        override fun repositoryIdentity(repoRoot: Path): String = "repo-root-realpath-v1:/main"
      }

    assertEquals(listOf("repo-root-realpath-v1:/main"), goalRepositoryIdentityCandidates(repoRoot, port))
  }
}
