package skillbill.engine.specbrowser

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.engine.goal.goalRepositoryIdentityCandidates
import skillbill.engine.goal.goalRepositoryRootFromIdentity
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
import skillbill.ports.work.model.WorkGoalParentCandidate
import skillbill.workflow.decomposition.runtime.isOccupyingActiveGoalRuntime
import java.nio.file.Path

internal data class ControlLive(
  val workflowId: String,
  val repositoryIdentity: String,
  val ownerRepoRoot: String,
  val lifecycle: IdeStatusLifecycleState,
  val pauseRequested: Boolean,
  val pauseEligible: Boolean,
  val targetAvailable: Boolean,
  val options: WorkRunOptions,
)

internal class SpecLiveGoalReader(
  private val database: DatabaseSessionFactory,
  private val status: IdeStatusService,
  private val runs: WorkRunActions,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) {
  fun overrides(
    checkoutRoots: List<Path>,
    candidates: LiveCandidates,
  ): Map<String, LiveGoal> {
    if (candidates.unfinished.isEmpty() && candidates.finished.isEmpty()) return emptyMap()
    val identities = identities(checkoutRoots)
    return database.readIfPresent { unit ->
      val finishedWithLiveWork =
        if (candidates.finished.isEmpty()) emptySet() else candidates.finished intersect readLiveIssueKeys(unit)
      (candidates.unfinished + finishedWithLiveWork).mapNotNull { key ->
        liveGoal(unit, identities, key)?.let { key to it }
      }.toMap()
    }.orEmpty()
  }

  fun controlFacts(
    checkoutRoots: List<Path>,
    issueKey: String,
  ): ControlLive? {
    val identities = identities(checkoutRoots)
    return database.readIfPresent { unit ->
      val candidates = parentWorkflowCandidates(unit, identities, issueKey)
      val active = candidates.filter { it.isActive }
      val ambiguous = active.size > 1 || active.isEmpty() && candidates.size > 1
      val selected = active.firstOrNull() ?: candidates.firstOrNull() ?: return@readIfPresent null
      val item = status.detail(WorkDetailRequest(selected.candidate.workflowId)).item ?: return@readIfPresent null
      val options = runs.options(item, unit)
      ControlLive(
        workflowId = selected.candidate.workflowId,
        repositoryIdentity = item.snapshot.repositoryIdentity,
        ownerRepoRoot =
          goalRepositoryRootFromIdentity(item.snapshot.repositoryIdentity)?.toString() ?: selected.repoRoot.toString(),
        lifecycle = item.snapshot.lifecycleState,
        pauseRequested = item.snapshot.pauseRequested == true,
        pauseEligible = !ambiguous && workPauseEligible(item.snapshot),
        targetAvailable = !ambiguous,
        options = if (ambiguous) options.copy(canCancel = false, canLaunch = false) else options,
      )
    }
  }

  fun goals(
    checkoutRoots: List<Path>,
    issueKey: String,
  ): List<LiveGoal> {
    val identities = identities(checkoutRoots)
    return database.readIfPresent { unit -> liveGoals(unit, identities, issueKey) }.orEmpty()
  }

  private fun liveGoal(
    unit: UnitOfWork,
    identities: List<RepositoryIdentity>,
    key: String,
  ): LiveGoal? =
    liveGoals(unit, identities, key).let { goals ->
      goals.firstOrNull { lifecycleActive(it.lifecycle) } ?: goals.firstOrNull()
    }

  private fun liveGoals(
    unit: UnitOfWork,
    identities: List<RepositoryIdentity>,
    key: String,
  ): List<LiveGoal> =
    parentWorkflowCandidates(unit, identities, key).mapNotNull { owner ->
      try {
        status.projectBoardSelection(listOf(owner.candidate.workflowId), WorkBoardRequest())
          .items.singleOrNull()?.snapshot?.let { LiveGoal(it.lifecycleState, it.pauseRequested == true) }
      } catch (_: SkillBillRuntimeException) {
        null
      }
    }

  private fun parentWorkflowCandidates(
    unit: UnitOfWork,
    identities: List<RepositoryIdentity>,
    issueKey: String,
  ): List<GoalOwner> =
    identities.flatMap { owner ->
      unit.workList.goalParentCandidatePage(issueKey, owner.identity, 0, WorkPaginationLimits.MAX_PAGE_SIZE)
        .map { GoalOwner(it, owner.repoRoot, isActive(it)) }
    }.distinctBy { it.candidate.workflowId }

  private fun identities(checkoutRoots: List<Path>): List<RepositoryIdentity> =
    checkoutRoots.distinct().flatMap { root ->
      goalRepositoryIdentityCandidates(root, repositoryEnclosingRootPort).map { RepositoryIdentity(it, root) }
    }.distinctBy { it.identity }

  private fun isActive(candidate: WorkGoalParentCandidate): Boolean =
    isOccupyingActiveGoalRuntime(candidate.workflowStatus, candidate.manifestStatus, candidate.hasUnsettledSubtasks)

  private fun lifecycleActive(lifecycle: IdeStatusLifecycleState): Boolean = lifecycle == IdeStatusLifecycleState.ACTIVE

  private data class RepositoryIdentity(val identity: String, val repoRoot: Path)

  private data class GoalOwner(
    val candidate: WorkGoalParentCandidate,
    val repoRoot: Path,
    val isActive: Boolean,
  )
}
