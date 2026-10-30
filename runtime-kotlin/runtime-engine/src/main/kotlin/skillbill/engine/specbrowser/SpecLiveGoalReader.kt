package skillbill.engine.specbrowser

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.engine.goalrunner.goalRepositoryIdentity
import skillbill.engine.work.IdeStatusService
import skillbill.engine.work.control.WorkRunActions
import skillbill.engine.work.control.model.WorkRunOptions
import skillbill.engine.work.eligibility.workPauseEligible
import skillbill.engine.work.pagination.readLiveIssueKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.workflow.decomposition.runtime.isActiveGoalRuntime
import java.nio.file.Path

internal data class ControlLive(
  val workflowId: String,
  val repositoryIdentity: String,
  val lifecycle: IdeStatusLifecycleState,
  val pauseRequested: Boolean,
  val pauseEligible: Boolean,
  val options: WorkRunOptions,
)

internal class SpecLiveGoalReader(
  private val database: DatabaseSessionFactory,
  private val status: IdeStatusService,
  private val runs: WorkRunActions,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) {
  fun overrides(
    repoRoot: Path,
    candidates: LiveCandidates,
  ): Map<String, LiveGoal> {
    if (candidates.unfinished.isEmpty() && candidates.finished.isEmpty()) return emptyMap()
    val identity = goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort)
    return database.readIfPresent { unit ->
      val finishedWithLiveWork =
        if (candidates.finished.isEmpty()) emptySet() else candidates.finished intersect readLiveIssueKeys(unit)
      (candidates.unfinished + finishedWithLiveWork).mapNotNull { key ->
        liveGoal(unit, identity, key)?.let { key to it }
      }.toMap()
    }.orEmpty()
  }

  fun controlFacts(
    repoRoot: Path,
    issueKey: String,
  ): ControlLive? {
    val identity = goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort)
    return database.readIfPresent { unit ->
      val workflowId = parentWorkflowId(unit, identity, issueKey) ?: return@readIfPresent null
      val item = status.detail(WorkDetailRequest(workflowId)).item ?: return@readIfPresent null
      ControlLive(
        workflowId = workflowId,
        repositoryIdentity = item.snapshot.repositoryIdentity,
        lifecycle = item.snapshot.lifecycleState,
        pauseRequested = item.snapshot.pauseRequested == true,
        pauseEligible = workPauseEligible(item.snapshot),
        options = runs.options(item, unit),
      )
    }
  }

  private fun liveGoal(
    unit: UnitOfWork,
    identity: String,
    key: String,
  ): LiveGoal? {
    val workflowId = parentWorkflowId(unit, identity, key) ?: return null
    return try {
      status.projectBoardSelection(listOf(workflowId), WorkBoardRequest()).items.singleOrNull()?.snapshot?.let {
        LiveGoal(it.lifecycleState, it.pauseRequested == true)
      }
    } catch (_: SkillBillRuntimeException) {
      null
    }
  }

  private fun parentWorkflowId(
    unit: UnitOfWork,
    identity: String,
    issueKey: String,
  ): String? {
    val candidates = unit.workList.goalParentCandidatePage(issueKey, identity, 0, WorkPaginationLimits.MAX_PAGE_SIZE)
    val chosen = candidates.firstOrNull { isActiveGoalRuntime(it.manifestStatus, it.hasUnsettledSubtasks) }
    return (chosen ?: candidates.firstOrNull())?.workflowId
  }
}
