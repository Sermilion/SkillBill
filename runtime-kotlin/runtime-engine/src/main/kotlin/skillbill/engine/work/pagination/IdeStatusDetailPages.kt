package skillbill.engine.work.pagination

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.ports.idestatus.model.StandalonePhaseExecutionAnchor
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.idestatus.model.WorkDetailList
import skillbill.ports.idestatus.model.WorkDetailRuntimeStatus
import skillbill.ports.idestatus.model.WorkExecutionInstance
import skillbill.ports.work.model.WorkGoalDependencyAnchor
import skillbill.ports.work.model.WorkGoalDependencyPage
import skillbill.ports.work.model.WorkGoalSubtaskPage

internal data class ProjectedDetailItem(
  val item: WorkDetailItem,
  val nextAnchors: Map<WorkDetailList, WorkDetailAnchor>,
)

internal data class GoalPageRead(
  val page: WorkGoalSubtaskPage,
  val list: WorkDetailList,
  val lastReadId: Int?,
)

internal fun goalSubtaskPageLimit(
  requestedPageSize: Int,
  completedOnly: Boolean,
): Int = if (completedOnly) requestedPageSize else WorkPaginationLimits.MAX_PAGE_SIZE

internal data class RuntimePhasePageRead(
  val status: WorkDetailRuntimeStatus,
  val hasMore: Boolean,
)

internal fun detailNextAnchors(
  history: List<WorkExecutionInstance>,
  goal: GoalPageRead?,
  validation: GoalPageRead?,
  dependencies: WorkGoalDependencyPage?,
  runtime: RuntimePhasePageRead?,
): Map<WorkDetailList, WorkDetailAnchor> =
  buildMap {
    history.lastOrNull()?.takeIf { it.runSequence.isNotEmpty() }?.let {
      put(
        WorkDetailList.EXECUTIONS,
        WorkDetailAnchor.Execution(StandalonePhaseExecutionAnchor(it.runSequence, it.invocationId)),
      )
    }
    listOfNotNull(goal, validation).forEach { read ->
      read.lastReadId?.let { put(read.list, WorkDetailAnchor.Subtask(read.list, it)) }
    }
    dependencies?.entries?.lastOrNull()?.let {
      val anchor = WorkGoalDependencyAnchor(it.subtaskId, it.dependency.subtaskId)
      put(WorkDetailList.DEPENDENCIES, WorkDetailAnchor.Dependency(anchor))
    }
    runtime?.status?.phases?.lastOrNull()?.let { put(WorkDetailList.PHASES, WorkDetailAnchor.Phase(it.phaseId)) }
  }
