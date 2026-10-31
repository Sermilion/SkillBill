package skillbill.engine.featuretask.incident.report

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.engine.diagnostics.containableFailure
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.workflow.support.workflowFamilyFor
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.artifact.phaseRecordsFromWorkflowArtifacts
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.LaunchProviderNamespace

data class BlockIncidentLaunchFacts(
  val providerId: String?,
  val phaseStrategyId: String?,
  val modelId: String?,
)

@Inject
class BlockIncidentLaunchFactsReader(
  private val executionPlanCodec: FeatureTaskRuntimeExecutionPlanCodec,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun read(
    unit: UnitOfWork,
    occurrence: BlockIncidentOccurrence,
  ): BlockIncidentLaunchFacts {
    return catchingNonCancellation {
      val snapshot = matchingWorkflow(unit, occurrence) ?: return EMPTY
      val phaseId = checkNotNull(occurrence.phaseId)
      val matchingRecord = phaseRecordsFromWorkflowArtifacts(snapshot.artifacts).getValue(phaseId)
      val descriptor =
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(snapshot.artifacts)
          ?: return EMPTY
      val encoded = JsonCodec.valueToJsonString(descriptor).toByteArray(Charsets.UTF_8)
      val plan = executionPlanCodec.decode(encoded)
      val dispatch = plan.dispatchStrategyByStep[phaseId] ?: return EMPTY
      val assignment = plan.stepLaunchAssignments[phaseId]
      BlockIncidentLaunchFacts(
        providerId = assignment?.launch?.namespace?.takeIf { it != LaunchProviderNamespace.OPAQUE }?.wireValue,
        phaseStrategyId = dispatch.strategyId,
        modelId = matchingRecord.launchedModel,
      )
    }.getOrElse { failure ->
      val exception = containableFailure(failure)
      diagnostics.warning(
        "Recorded block incident launch facts are unreadable; omitting provider, strategy, and model.",
        exception,
      )
      EMPTY
    }
  }

  private fun matchingWorkflow(
    unit: UnitOfWork,
    occurrence: BlockIncidentOccurrence,
  ): WorkflowStateSnapshot? {
    val workflowId = occurrence.workflowId ?: return null
    val phaseId = occurrence.phaseId ?: return null
    val attempt = occurrence.attempt ?: return null
    return if (workflowFamilyFor(unit.workflowStates, workflowId) == WorkflowFamily.TASK_RUNTIME) {
      unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)?.takeIf { snapshot ->
        val record = phaseRecordsFromWorkflowArtifacts(snapshot.artifacts)[phaseId]
        matchesOccurrence(snapshot.artifacts, record, occurrence, phaseId, attempt)
      }
    } else {
      null
    }
  }

  private fun matchesOccurrence(
    artifacts: Map<String, Any?>,
    record: FeatureTaskRuntimePhaseRecord?,
    occurrence: BlockIncidentOccurrence,
    phaseId: String,
    attempt: Int,
  ): Boolean {
    if (record?.phaseId != phaseId || record.attemptCount != attempt || occurrence.executionId != null) return false
    if (occurrence.generation == null) return true
    val identity = BlockIncidentIdentity.fromArtifacts(artifacts) ?: return false
    return identity.incidentToken == occurrence.occurrenceToken && identity.stepId == phaseId &&
      identity.attempt == attempt && identity.generation == occurrence.generation
  }

  private companion object {
    val EMPTY = BlockIncidentLaunchFacts(null, null, null)
  }
}
