package skillbill.engine.featuretask.lifecycle.execution

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.testProjectStore
import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskExecutionIdentityResolverTest {
  @Test
  fun `goal child resume keeps a legacy exact checkout identity`() {
    val identity =
      expectedIdentity(
        workflowId = "wf-child",
        storedIdentity = EXACT_IDENTITY,
        storedScope = FeatureTaskRouteScope.GOAL_CHILD,
        routeScope = FeatureTaskRouteScope.GOAL_CHILD,
      )

    assertEquals(EXACT_IDENTITY, identity.repositoryIdentity)
  }

  @Test
  fun `expected identity ignores a foreign stored checkout identity`() {
    val identity =
      expectedIdentity(
        workflowId = "wf-child",
        storedIdentity = FOREIGN_IDENTITY,
        storedScope = FeatureTaskRouteScope.GOAL_CHILD,
        routeScope = FeatureTaskRouteScope.GOAL_CHILD,
      )

    assertEquals(PRIMARY_IDENTITY, identity.repositoryIdentity)
  }

  @Test
  fun `plan resume keeps a legacy exact checkout identity`() {
    val identity =
      expectedPlanIdentity(
        workflowId = "wf-plan",
        storedIdentity = EXACT_IDENTITY,
      )

    assertEquals(EXACT_IDENTITY, identity.repositoryIdentity)
  }

  @Test
  fun `plan resume keeps a primary identity from a linked worktree`() {
    val identity =
      expectedPlanIdentity(
        workflowId = "wf-plan",
        storedIdentity = PRIMARY_IDENTITY,
      )

    assertEquals(PRIMARY_IDENTITY, identity.repositoryIdentity)
  }

  private fun expectedIdentity(
    workflowId: String,
    storedIdentity: String,
    storedScope: FeatureTaskRouteScope,
    routeScope: FeatureTaskRouteScope,
  ): FeatureTaskExecutionIdentity {
    val states = InMemoryRuntimeWorkflowRepository()
    states.saveFeatureTaskExecutionIdentity(
      FeatureTaskExecutionIdentity(
        workflowId = workflowId,
        normalizedIssueKey = ISSUE_KEY,
        repositoryIdentity = storedIdentity,
        governedSpecPath = SPEC_PATH,
        mode = FeatureTaskWorkflowMode.RUNTIME,
        routeScope = storedScope,
      ),
    )
    return resolver(states).expected(workflowId, ISSUE_KEY, REPO_ROOT, Path.of(SPEC_PATH), routeScope)
  }

  private fun expectedPlanIdentity(
    workflowId: String,
    storedIdentity: String,
  ): FeatureTaskExecutionIdentity {
    val states = InMemoryRuntimeWorkflowRepository()
    ExecutionPlanAdmissionFixture(SkeletonDefinition.PLAN).seed(
      states,
      workflowId,
      ISSUE_KEY,
      executionIdentity =
        FeatureTaskExecutionIdentity(
          workflowId,
          ISSUE_KEY,
          storedIdentity,
          SPEC_PATH,
          FeatureTaskWorkflowMode.RUNTIME,
          FeatureTaskRouteScope.STANDALONE,
        ),
    )
    return resolver(states).expected(
      workflowId,
      ISSUE_KEY,
      REPO_ROOT,
      Path.of(SPEC_PATH),
      FeatureTaskRouteScope.STANDALONE,
    )
  }

  private fun resolver(states: InMemoryRuntimeWorkflowRepository): FeatureTaskExecutionIdentityResolver =
    FeatureTaskExecutionIdentityResolver(FakeDatabaseSessionFactory(states), linkedWorktreePort(), testProjectStore)

  private fun linkedWorktreePort(): RepositoryEnclosingRootPort =
    object : RepositoryEnclosingRootPort by TestRepositoryEnclosingRoot {
      override fun primaryRepositoryIdentity(repoRoot: Path): String = PRIMARY_IDENTITY

      override fun repositoryIdentity(repoRoot: Path): String = EXACT_IDENTITY
    }

  private companion object {
    const val PRIMARY_IDENTITY = "repo-root-realpath-v1:/main"
    const val EXACT_IDENTITY = "repo-root-realpath-v1:/worktree"
    const val FOREIGN_IDENTITY = "repo-root-realpath-v1:/other"
    const val ISSUE_KEY = "SKILL-468"
    const val SPEC_PATH = ".feature-specs/SKILL-468/spec.md"
    val REPO_ROOT: Path = Path.of("/tmp/resolver-main")
  }
}
