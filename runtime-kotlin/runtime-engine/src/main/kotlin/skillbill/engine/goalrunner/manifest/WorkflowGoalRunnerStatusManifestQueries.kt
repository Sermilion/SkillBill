package skillbill.engine.goalrunner.manifest

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerStatusManifestPage
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.workflow.decomposition.runtime.isActiveGoalRuntime
import java.nio.file.Path

internal class WorkflowGoalRunnerStatusManifestQueries(
  private val database: DatabaseSessionFactory,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) : GoalRunnerStatusManifestQueries {
  override fun statusManifestPage(
    parentWorkflowId: String,
    issueKey: String,
    offset: Int,
    limit: Int,
    repoRoot: Path?,
  ): GoalRunnerStatusManifestPage? =
    database.read { unit ->
      unit.workList.goalSubtaskPage(parentWorkflowId, offset, limit)
        ?.takeIf { it.manifest.issueKey == issueKey }
        ?.let { page ->
          GoalRunnerStatusManifestPage(
            GoalRunnerManifestState(
              parentWorkflowId,
              unit.dbPath.toString(),
              page.manifest,
              repoRoot = repoRoot,
              controlState = unit.goalRunnerControls.controlState(parentWorkflowId),
            ),
            page.totalCount,
          )
        }
    }

  override fun statusParentWorkflowId(
    issueKey: String,
    repoRoot: Path?,
  ): String? =
    database.read { unit ->
      val identity = repoRoot?.let(repositoryEnclosingRootPort::repositoryIdentity)
      var offset = 0
      var newest: String? = null
      var active: String? = null
      do {
        val page =
          unit.workList.goalParentCandidatePage(
            issueKey,
            identity,
            offset,
            WorkPaginationLimits.MAX_PAGE_SIZE,
          )
        if (newest == null) newest = page.firstOrNull()?.workflowId
        page.forEach { candidate ->
          if (isActiveGoalRuntime(candidate.manifestStatus, candidate.hasUnsettledSubtasks)) {
            check(active == null) {
              "Ambiguous decomposed parent workflows for '$issueKey': " +
                "$active, ${candidate.workflowId}."
            }
            active = candidate.workflowId
          }
        }
        offset += page.size
      } while (page.size == WorkPaginationLimits.MAX_PAGE_SIZE)
      active ?: newest
    }
}
