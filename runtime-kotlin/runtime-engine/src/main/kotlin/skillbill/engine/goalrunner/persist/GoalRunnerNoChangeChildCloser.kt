package skillbill.engine.goalrunner.persist

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.workflow.service.WorkflowService
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.engine.model.isTerminalStatus
import skillbill.workflow.model.WorkflowStatus
import java.time.Clock

private const val MAX_ABANDON_REASON_CHARS: Int = 1000

@Inject
class GoalRunnerNoChangeChildCloser(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val workflowService: WorkflowService,
) {
  private val engine = WorkflowEngine()

  fun closeAccepted(workflowId: String) {
    database.transaction { unitOfWork ->
      val family = WorkflowFamily.TASK_RUNTIME
      val existing = unitOfWork.workflowStates.get(family, workflowId) ?: return@transaction
      if (family.definition.isTerminalStatus(existing.workflowStatus)) return@transaction
      val updated =
        engine.updateRecord(
          family.definition,
          existing,
          WorkflowUpdateInput(
            workflowStatus = WorkflowStatus.COMPLETED,
            currentStepId = existing.currentStepId,
            stepUpdates = null,
            artifactsPatch = null,
            sessionId = existing.sessionId,
            terminalInstant = clock.instant(),
          ),
        )
      unitOfWork.workflowStates.save(family, updated)
    }
  }

  fun abandon(
    workflowId: String,
    reason: String,
  ): String? {
    val existing =
      database.transaction { unitOfWork -> unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) }
    if (existing?.workflowStatus == WorkflowStatus.ABANDONED) return null
    return when (
      val result = workflowService.abandonFeatureTaskRuntime(workflowId, reason.take(MAX_ABANDON_REASON_CHARS))
    ) {
      is WorkflowUpdateResult.Ok -> null
      is WorkflowUpdateResult.Error -> result.error
    }
  }
}
