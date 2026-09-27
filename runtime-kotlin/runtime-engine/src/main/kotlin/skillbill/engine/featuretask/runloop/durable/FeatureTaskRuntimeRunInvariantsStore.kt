package skillbill.engine.featuretask.runloop.durable

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.taskruntime.artifact.decodeRunInvariantsFromArtifact
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants

@Inject
class FeatureTaskRuntimeRunInvariantsStore(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
) {
  fun resolve(
    workflowId: String,
    proposed: FeatureTaskRuntimeRunInvariants? = null,
  ): FeatureTaskRuntimeRunInvariants? {
    proposed?.let { persistOrUpdateAgentAddons(workflowId, it) }
    return database.read { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@read null
      runInvariantsFrom(record.artifacts)
    }
  }

  private fun persistOrUpdateAgentAddons(
    workflowId: String,
    proposed: FeatureTaskRuntimeRunInvariants,
  ) {
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@transaction
      val artifacts = record.artifacts
      val existing = runInvariantsFrom(artifacts)
      when {
        existing == null -> workflowPersistence.persistRunInvariantsPatch(unitOfWork.workflowStates, record, proposed)
        existing.agentAddonSelection != proposed.agentAddonSelection ->
          workflowPersistence.persistRunInvariantsPatch(
            unitOfWork.workflowStates,
            record,
            existing.copy(agentAddonSelection = proposed.agentAddonSelection),
          )
      }
    }
  }
}

private fun runInvariantsFrom(artifacts: DurableWorkflowArtifacts): FeatureTaskRuntimeRunInvariants? {
  val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RUN_INVARIANTS
  val raw = family.value(artifacts) ?: return null
  val entryMap =
    JsonCodec.anyToStringAnyMap(raw)
      ?: throw InvalidWorkflowStateSchemaError(
        "Feature-task-runtime artifact '${family.label()}' must decode to a map.",
      )
  return decodeRunInvariantsFromArtifact(entryMap)
}
