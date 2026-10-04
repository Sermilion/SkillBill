package skillbill.application.work

import me.tatarka.inject.annotations.Inject
import skillbill.application.work.model.WorkListItem
import skillbill.application.work.model.WorkListItemKind
import skillbill.application.work.model.WorkListResult
import skillbill.error.shellcontent.invalidWorkListRowError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine

@Inject
class WorkListService(
  private val database: DatabaseSessionFactory,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
) {
  private val workflowEngine = WorkflowEngine()

  fun list(limit: Int? = null): WorkListResult {
    require(limit == null || limit > 0) { "--limit must be a positive integer." }
    return database.read { unitOfWork ->
      val persistedWork = unitOfWork.workList.list(limit)
      validateWorkflowSnapshots(unitOfWork, persistedWork)
      WorkListResult(
        dbPath = unitOfWork.dbPath.toString(),
        work = persistedWork.map(WorkItem::toApplicationItem),
      )
    }
  }

  private fun validateWorkflowSnapshots(
    unitOfWork: UnitOfWork,
    work: List<WorkItem>,
  ) {
    work.groupBy(::workflowFamily).forEach { (family, items) ->
      family ?: return@forEach
      val snapshots = unitOfWork.workflowStates.getAll(family, items.mapTo(linkedSetOf(), WorkItem::workflowId))
      items.forEach { item ->
        val snapshot =
          snapshots[item.workflowId]
            ?: throw invalidWorkListRowError(
              "Work-list row '${item.workflowId}' has no matching ${item.workflowKind.wireValue} workflow snapshot.",
            )
        workflowEngine.summaryView(family.definition, snapshot)
        workflowSnapshotValidator.validate(snapshot, family.definition.workflowName)
      }
    }
  }
}

private fun workflowFamily(item: WorkItem): WorkflowFamily? =
  when (item.workflowKind) {
    WorkItemKind.FEATURE_TASK_PROSE -> null
    WorkItemKind.FEATURE_TASK_RUNTIME -> WorkflowFamily.TASK_RUNTIME
    WorkItemKind.FEATURE_VERIFY -> WorkflowFamily.VERIFY
    WorkItemKind.FEATURE_GOAL -> null
  }

private fun WorkItem.toApplicationItem(): WorkListItem =
  WorkListItem(
    issueKey = issueKey,
    workflowKind =
      when (workflowKind) {
        WorkItemKind.FEATURE_TASK_PROSE -> WorkListItemKind.FEATURE_TASK_PROSE
        WorkItemKind.FEATURE_TASK_RUNTIME -> WorkListItemKind.FEATURE_TASK_RUNTIME
        WorkItemKind.FEATURE_VERIFY -> WorkListItemKind.FEATURE_VERIFY
        WorkItemKind.FEATURE_GOAL -> WorkListItemKind.FEATURE_GOAL
      },
    workflowId = workflowId,
    startedAt = startedAt,
    currentState = currentState,
    stateEnteredAt = stateEnteredAt,
    stateEnteredAtEstimated = stateEnteredAtEstimated,
  )
