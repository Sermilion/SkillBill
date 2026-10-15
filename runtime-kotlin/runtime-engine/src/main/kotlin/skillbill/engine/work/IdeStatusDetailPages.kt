package skillbill.engine.work

import skillbill.engine.work.pagination.WorkDetailAnchor
import skillbill.ports.idestatus.model.StandalonePhaseExecutionAnchor
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.idestatus.model.WorkDetailList
import skillbill.ports.idestatus.model.WorkDetailRuntimeStatus
import skillbill.ports.idestatus.model.WorkExecutionInstance
import skillbill.ports.work.model.WorkGoalDependencyAnchor
import skillbill.ports.work.model.WorkGoalDependencyPage
import skillbill.ports.work.model.WorkGoalSubtaskPage

/** A detail item plus, per list, the identity of the last row that list read, which its next page continues after. */
internal data class ProjectedDetailItem(
  val item: WorkDetailItem,
  val nextAnchors: Map<WorkDetailList, WorkDetailAnchor>,
)

/** A goal subtask page for [list], with the last subtask id the read returned before any projection filtering. */
internal data class GoalPageRead(
  val page: WorkGoalSubtaskPage,
  val list: WorkDetailList,
  val lastReadId: Int?,
)

internal data class RuntimePhasePageRead(
  val status: WorkDetailRuntimeStatus,
  val hasMore: Boolean,
)

/** Each list's next anchor is its own last-read row: never another list's, and never a row the list did not read. */
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
