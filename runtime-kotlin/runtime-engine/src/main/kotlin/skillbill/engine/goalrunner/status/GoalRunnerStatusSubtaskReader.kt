package skillbill.engine.goalrunner.status

import skillbill.contracts.workflow.identity.status.GOAL_PLANNING_WAVE_CAP
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.manifest.goalDependencySatisfied
import skillbill.engine.goalrunner.manifest.planWorkflowPlanningProgress
import skillbill.engine.goalrunner.manifest.reconcileGoalSubtasks
import skillbill.engine.goalrunner.manifest.selectGoalCurrentSubtask
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.error.shellcontent.invalidGoalPlanningPreparationSchemaError
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusSummary
import skillbill.goalrunner.model.GoalRunnerStatusCounts
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus

internal data class GoalRunnerStatusSubtasks(
  val state: GoalRunnerManifestState,
  val counts: GoalRunnerStatusCounts,
  val current: DecompositionSubtask?,
  val planning: GoalPlanningStatusSnapshot?,
  val validationSubtasks: List<DecompositionSubtask>,
  val acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
  val completedNoChange: DecompositionSubtask?,
)

internal class GoalRunnerStatusSubtaskReader(
  private val manifests: GoalRunnerManifestStore,
  private val outcomes: GoalRunnerWorkflowOutcomeStore,
  private val unit: UnitOfWork,
) {
  fun read(
    parentId: String,
    request: GoalRunnerStatusRequest,
  ): GoalRunnerStatusSubtasks? {
    val first =
      manifests.statusManifestPage(parentId, request.issueKey, 0, WorkPaginationLimits.MAX_PAGE_SIZE, request.repoRoot)
        ?: return null
    val accumulator = SubtaskAccumulator()
    val planWorkflowPlanned = unit.planWorkflowPlanningProgress(parentId)?.plannedSubtaskIds.orEmpty()
    var offset = 0
    var page = first
    do {
      val projected = reconcile(parentId, request, page.state.manifest.subtasks)
      val planningIds =
        projected.filter {
          it.status.decompositionStatus() != DecompositionStatus.SKIPPED
        }.mapTo(mutableSetOf()) { it.id }
      val legacyPrepared = unit.goalPlanningPreparations.preparedPlanIdsForSubtasks(parentId, planningIds)
      projected.forEach { subtask ->
        val runnable =
          subtask.status.decompositionStatus() == DecompositionStatus.PENDING &&
            accumulator.firstRunnable == null && dependenciesSatisfied(parentId, request, subtask)
        accumulator.add(subtask, legacyPrepared, planWorkflowPlanned, runnable, request)
      }
      offset += page.state.manifest.subtasks.size
      if (offset >= first.totalCount) break
      page =
        requireNotNull(
          manifests.statusManifestPage(
            parentId,
            request.issueKey,
            offset,
            WorkPaginationLimits.MAX_PAGE_SIZE,
            request.repoRoot,
          ),
        )
      check(page.state.manifest.subtasks.isNotEmpty()) { "Goal status pagination did not advance." }
    } while (true)
    return accumulator.result(parentId, request, first.state)
  }

  fun reconcile(
    parentId: String,
    request: GoalRunnerStatusRequest,
    subtasks: List<DecompositionSubtask>,
  ): List<DecompositionSubtask> {
    val ids = subtasks.mapTo(mutableSetOf()) { it.id }
    return reconcileGoalSubtasks(
      request.issueKey,
      subtasks,
      outcomes.authoritativeOutcomesForSubtasks(request.issueKey, ids),
      unit.goalRunnerControls.outOfBandAcceptancesForSubtasks(parentId, ids),
      outcomes,
    )
  }

  private fun dependenciesSatisfied(
    parentId: String,
    request: GoalRunnerStatusRequest,
    subtask: DecompositionSubtask,
  ): Boolean {
    var offset = 0
    do {
      val page = unit.workList.goalDependencyPage(parentId, offset, WorkPaginationLimits.MAX_PAGE_SIZE, subtask.id)
      val dependencies = page.entries.map { it.dependency }
      val required = dependencies.filterNot { it.optional && it.skipped }
      val ids = required.mapTo(mutableSetOf()) { it.subtaskId }
      val resolved = reconcile(parentId, request, unit.workList.goalSubtasksByIds(parentId, ids)).associateBy { it.id }
      if (!dependencies.all {
          goalDependencySatisfied(it, resolved[it.subtaskId]?.status.decompositionStatus())
        }
      ) {
        return false
      }
      offset = page.nextOffset ?: return true
    } while (true)
  }

  private inner class SubtaskAccumulator {
    private var counts = GoalRunnerStatusCounts()
    private var firstBlocked: DecompositionSubtask? = null
    private var firstInProgress: DecompositionSubtask? = null
    private var firstPending: DecompositionSubtask? = null
    var firstRunnable: DecompositionSubtask? = null
      private set
    private var firstNoChange: DecompositionSubtask? = null
    private var anyCommit = false
    private var planningCount = 0
    private var preparedCount = 0
    private var legacyPreparedCount = 0
    private val missingPlans = mutableListOf<Int>()
    private val validation = mutableListOf<DecompositionSubtask>()

    fun add(
      subtask: DecompositionSubtask,
      legacyPrepared: Set<Int>,
      planWorkflowPlanned: Set<Int>,
      runnable: Boolean,
      request: GoalRunnerStatusRequest,
    ) {
      val status = subtask.status.decompositionStatus()
      counts = counts.adding(status)
      select(subtask, status, runnable)
      anyCommit = anyCommit || !subtask.commitSha.isNullOrBlank()
      if (status != DecompositionStatus.SKIPPED) {
        planningCount++
        val inLegacy = subtask.id in legacyPrepared
        if (inLegacy) legacyPreparedCount++
        if (inLegacy || subtask.id in planWorkflowPlanned) {
          preparedCount++
        } else if (missingPlans.size < GOAL_PLANNING_WAVE_CAP) {
          missingPlans += subtask.id
        }
      }
      val selectedValidation =
        request.includeCompletedSubtaskValidation &&
          subtask.id in request.completedSubtaskValidationIds.orEmpty()
      if (selectedValidation && status == DecompositionStatus.COMPLETE) {
        validation += subtask
      }
    }

    private fun select(
      subtask: DecompositionSubtask,
      status: DecompositionStatus?,
      runnable: Boolean,
    ) {
      if (status == DecompositionStatus.BLOCKED && firstBlocked == null) firstBlocked = subtask
      if (status == DecompositionStatus.IN_PROGRESS && firstInProgress == null) firstInProgress = subtask
      if (status == DecompositionStatus.PENDING && firstPending == null) firstPending = subtask
      if (runnable) firstRunnable = subtask
      if (status == DecompositionStatus.COMPLETED_NO_CHANGE && firstNoChange == null) firstNoChange = subtask
    }

    fun result(
      parentId: String,
      request: GoalRunnerStatusRequest,
      state: GoalRunnerManifestState,
    ): GoalRunnerStatusSubtasks {
      if (unit.goalPlanningPreparations.preparedPlanStatusCount(parentId) != legacyPreparedCount) {
        throw invalidGoalPlanningPreparationSchemaError(
          parentId,
          "subtask_id",
          "stored plan checkpoints must match the governed ordering",
        )
      }
      val current = selectGoalCurrentSubtask(firstBlocked, firstInProgress, firstRunnable, firstPending)
      val planningBlock =
        current?.takeIf {
          it.status.decompositionStatus() == DecompositionStatus.BLOCKED && it.lastResumableStep in
            setOf(
              "preplan",
              "plan",
            )
        }
      val planning =
        manifests.planningStatusFromSummary(
          parentId,
          GoalPlanningStatusSummary(planningCount, preparedCount, missingPlans),
          planningBlock?.id,
          planningBlock?.blockedReason,
        )
      val selected = request.completedSubtaskValidationIds.orEmpty() + listOfNotNull(current?.id)
      val acceptances =
        selected.chunked(WorkPaginationLimits.MAX_PAGE_SIZE)
          .flatMap { unit.goalRunnerControls.outOfBandAcceptancesForSubtasks(parentId, it.toSet()).entries }
          .associate { it.toPair() }
      return GoalRunnerStatusSubtasks(
        state,
        counts,
        current,
        planning,
        validation,
        acceptances,
        firstNoChange.takeIf { counts.pending == 0 && counts.blocked == 0 && !anyCommit },
      )
    }
  }
}
