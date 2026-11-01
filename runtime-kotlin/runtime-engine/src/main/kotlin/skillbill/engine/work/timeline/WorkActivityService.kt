package skillbill.engine.work.timeline

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.work.timeline.model.WorkActivityBlock
import skillbill.engine.work.timeline.model.WorkActivityBlockKind
import skillbill.engine.work.timeline.model.WorkActivityGroup
import skillbill.engine.work.timeline.model.WorkActivityResult
import skillbill.engine.work.timeline.model.WorkActivityStatus
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.ports.UnitOfWork
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.work.model.WorkActivityAttempt
import skillbill.ports.work.model.WorkItemKind
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

@Inject
public class WorkActivityService(
  private val database: DatabaseSessionFactory,
  private val phaseReads: ActivityPhaseReads,
  private val finishReads: ActivityGoalFinishReads,
  private val diagnostics: RuntimeDiagnostics,
) {
  private val completed = linkedMapOf<WorkActivityAttempt, WorkActivityGroup>()

  @Synchronized
  public fun timeline(goalWorkflowId: String): WorkActivityResult {
    if (!database.databaseExists()) {
      return unavailable(goalWorkflowId, "Skill Bill database is not present.", null)
    }
    return try {
      database.read { unit -> read(goalWorkflowId, unit) }
    } catch (error: SkillBillRuntimeException) {
      unavailable(goalWorkflowId, error.message ?: "Activity could not be read.", error)
    }
  }

  private fun unavailable(
    id: String,
    reason: String,
    error: Throwable?,
  ): WorkActivityResult {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "work_activity goal=$id value_used=unavailable value_expected=timeline cause=$reason",
      error,
    )
    return WorkActivityResult.Unavailable(reason)
  }

  private fun read(
    goalWorkflowId: String,
    unit: UnitOfWork,
  ): WorkActivityResult {
    val goal =
      unit.workList.findByWorkflowId(goalWorkflowId)
        .singleOrNull { it.workflowKind == WorkItemKind.FEATURE_GOAL }
        ?: return WorkActivityResult.NotFound
    val planned = planned(goalWorkflowId, unit)
    val attempts = pages { offset -> unit.workList.goalActivityAttempts(goalWorkflowId, offset, PAGE_SIZE) }
    val names = planned.associate { it.id to it.name }
    val groups =
      attempts.map { attempt ->
        val cached = completed[attempt]
        val group = cached ?: attemptGroup(attempt, names[attempt.subtaskId], unit)
        if (group.status == WorkActivityStatus.COMPLETE) {
          completed[attempt] = group
          while (completed.size > MAX_CACHED_CHILDREN) completed.remove(completed.keys.first())
        }
        group
      }.toMutableList()
    val started = attempts.map { it.subtaskId }.toSet()
    planned.filter { it.id !in started }.forEach { subtask ->
      groups +=
        WorkActivityGroup(
          "pending-${subtask.id}",
          subtask.id,
          subtask.name,
          WorkActivityStatus.PENDING,
          null,
          null,
          emptyList(),
          emptyList(),
        )
    }
    val stops = pages { offset -> unit.workList.goalActivityStops(goalWorkflowId, offset, PAGE_SIZE) }
    finishReads.group(goal, groups, stops, unit)?.let(groups::add)
    val ordered = groups.sortedWith(compareBy<WorkActivityGroup> { it.startedAt == null }.thenBy { it.startedAt })
    return WorkActivityResult.Listed(ordered)
  }

  private fun attemptGroup(
    attempt: WorkActivityAttempt,
    name: String?,
    unit: UnitOfWork,
  ): WorkActivityGroup {
    val status = activityStatus(attempt.workflowStatus ?: attempt.eventStatus)
    val phases = phaseReads.phases(attempt.workflowId, unit)
    val blocks = activityBlocks(attempt.workflowId, unit).toMutableList()
    if (blocks.isEmpty() && !attempt.blockedReason.isNullOrBlank()) {
      blocks +=
        WorkActivityBlock(
          "event-${attempt.workflowId}",
          attempt.finishedAt ?: attempt.startedAt,
          WorkActivityBlockKind.BLOCKED,
          null,
          null,
          attempt.blockedReason,
          false,
        )
    }
    return WorkActivityGroup(
      attempt.workflowId,
      attempt.subtaskId,
      name ?: attempt.name.orEmpty(),
      status,
      minOf(attempt.startedAt, phases.mapNotNull { it.startedAt }.minOrNull() ?: attempt.startedAt),
      if (status == WorkActivityStatus.RUNNING) {
        null
      } else {
        listOfNotNull(
          attempt.finishedAt,
          phases.mapNotNull { it.finishedAt }.maxOrNull(),
          blocks.maxOfOrNull { it.at },
        ).maxOrNull()
      },
      phases,
      blocks.sortedBy { it.at },
    )
  }

  private fun planned(
    goalWorkflowId: String,
    unit: UnitOfWork,
  ): List<DecompositionSubtask> =
    buildList {
      var after: Int? = null
      do {
        val page = unit.workList.goalSubtaskPage(goalWorkflowId, 0, PAGE_SIZE, afterSubtaskId = after) ?: break
        val subtasks = page.manifest.subtasks
        addAll(subtasks)
        after = subtasks.lastOrNull()?.id
      } while (subtasks.size == PAGE_SIZE)
    }
}

internal fun activityBlocks(
  workflowId: String,
  unit: UnitOfWork,
): List<WorkActivityBlock> =
  pages { offset -> unit.blockIncidents.listForWorkPage(workflowId, offset, PAGE_SIZE) }.map { incident ->
    val occurrence = incident.occurrence
    val decision = occurrence.failureDisposition == FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION.wireValue
    WorkActivityBlock(
      occurrence.occurrenceToken,
      occurrence.observedAt,
      if (decision) WorkActivityBlockKind.AWAITING_DECISION else WorkActivityBlockKind.BLOCKED,
      occurrence.phaseId,
      occurrence.attempt,
      occurrence.reason,
      decision,
    )
  }.distinctBy { it.id }

internal fun activityStatus(status: String?): WorkActivityStatus =
  when (status) {
    DecompositionStatus.COMPLETE.wireValue, WorkflowStatus.COMPLETED.wireValue,
    DecompositionStatus.COMPLETED_NO_CHANGE.wireValue,
    -> WorkActivityStatus.COMPLETE
    WorkflowStatus.RUNNING.wireValue, DecompositionStatus.IN_PROGRESS.wireValue -> WorkActivityStatus.RUNNING
    WorkflowStatus.BLOCKED.wireValue, WorkflowStatus.FAILED.wireValue -> WorkActivityStatus.BLOCKED
    WorkflowStatus.PENDING.wireValue -> WorkActivityStatus.PENDING
    WorkflowStatus.ABANDONED.wireValue, WorkflowStatus.PAUSED.wireValue,
    WorkflowStatus.TIMED_OUT.wireValue, DecompositionStatus.SKIPPED.wireValue,
    -> WorkActivityStatus.INTERRUPTED
    else -> throw invalidWorkflowStateSchemaError("Unsupported activity status '$status'.")
  }

internal fun <T> pages(read: (Int) -> List<T>): List<T> =
  buildList {
    var offset = 0
    do {
      val page = read(offset)
      addAll(page)
      offset += page.size
    } while (page.size == PAGE_SIZE)
  }

private const val PAGE_SIZE = WorkPaginationLimits.MAX_PAGE_SIZE
private const val MAX_CACHED_CHILDREN = 256
