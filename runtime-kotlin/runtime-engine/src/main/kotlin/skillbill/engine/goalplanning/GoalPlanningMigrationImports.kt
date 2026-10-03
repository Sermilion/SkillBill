package skillbill.engine.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.goal.GoalPlanningPreparationPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseExecutionOrigin
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import java.time.Clock

@Inject
class GoalPlanningMigrationImports(
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val clock: Clock,
) {
  internal fun prepare(
    session: GoalRunnerPersistenceSession,
    parent: WorkflowStateSnapshot,
    sourceShared: SharedGoalPreplanCheckpoint,
    plans: List<Pair<GoalSubtaskPlanCheckpoint, GoalSubtaskPlanCheckpoint>>,
    targetShared: SharedGoalPreplanCheckpoint,
  ): List<PlanningImportMigration> {
    val manifest = requireNotNull(parent.decompositionRuntime())
    val linked = session.workflowStates.listGoalChildWorkflowIdsByParent(parent.workflowId).toSet()
    val named = manifest.subtasks.mapNotNull { it.workflowId?.takeIf(String::isNotBlank) }.toSet()
    if (!named.containsAll(linked)) migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
    return named.map { workflowId ->
      val child =
        session.workflowStates.getFeatureTaskWorkflow(workflowId)
          ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      val ownership =
        session.workflowStates.getFeatureTaskExecutionIdentity(workflowId)
          ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      val subtask = manifest.subtasks.single { it.workflowId == workflowId }
      if (ownership.routeScope != FeatureTaskRouteScope.GOAL_CHILD) {
        migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      }
      if (
        ownership.repositoryIdentity != sourceShared.identity.repositoryIdentity ||
        ownership.normalizedIssueKey != sourceShared.identity.normalizedIssueKey ||
        ownership.governedSpecPath != subtask.specPath
      ) {
        migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      }
      session.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId)?.let { worker ->
        if (worker.expiresAtInstant.isAfter(clock.instant()) ||
          supervisor.inspect(worker) != FeatureTaskRuntimeProcessInspection.NotRunning
        ) {
          migrationFailure(FeatureTaskRuntimeMigrationFailureCode.STALE_SOURCE)
        }
      }
      val source =
        plans.singleOrNull { it.first.subtaskId == subtask.id }?.first
          ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      val target = plans.single { it.first.subtaskId == subtask.id }.second
      prepareChild(child, sourceShared, source, targetShared, target)
    }
  }

  private fun prepareChild(
    sourceChild: WorkflowStateRecord,
    sourceShared: SharedGoalPreplanCheckpoint,
    source: GoalSubtaskPlanCheckpoint,
    targetShared: SharedGoalPreplanCheckpoint,
    target: GoalSubtaskPlanCheckpoint,
  ): PlanningImportMigration {
    val child = sourceChild.toSnapshot()
    val imported =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.value(child.artifacts)
        ?.let(::artifactMap) ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
    val provenance = source.provenance
    val expected =
      linkedMapOf(
        GoalPlanningPreparationPayloadKeys.SOURCE_KIND to "imported_goal_planning",
        GoalPlanningPreparationPayloadKeys.PARENT_GOAL_WORKFLOW_ID to source.identity.parentGoalWorkflowId,
        GoalPlanningPreparationPayloadKeys.NORMALIZED_ISSUE_KEY to source.identity.normalizedIssueKey,
        GoalPlanningPreparationPayloadKeys.REPOSITORY_IDENTITY to source.identity.repositoryIdentity,
        GoalPlanningPreparationPayloadKeys.PARENT_SPEC_HASH to provenance.parentSpecHash,
        GoalPlanningPreparationPayloadKeys.DECOMPOSITION_MANIFEST_HASH to provenance.decompositionManifestHash,
        GoalPlanningPreparationPayloadKeys.PLANNING_CONTRACT_ID to provenance.planningContractId,
        GoalPlanningPreparationPayloadKeys.PLANNING_CONTRACT_VERSION to provenance.planningContractVersion,
        GoalPlanningPreparationPayloadKeys.PHASE_OUTPUT_CONTRACT_ID to provenance.phaseOutputContractId,
        GoalPlanningPreparationPayloadKeys.PHASE_OUTPUT_CONTRACT_VERSION to provenance.phaseOutputContractVersion,
        SharedPayloadKeys.SUBTASK_ID to source.subtaskId,
        GoalPlanningPreparationPayloadKeys.MANIFEST_ORDER to source.manifestOrder,
        GoalPlanningPreparationPayloadKeys.GOVERNED_SUB_SPEC_PATH to source.governedSubSpecPath,
        GoalPlanningPreparationPayloadKeys.SUB_SPEC_HASH to source.subSpecHash,
        GoalPlanningPreparationPayloadKeys.PREPLAN_PAYLOAD_SHA256 to sourceShared.payloadSha256,
        GoalPlanningPreparationPayloadKeys.PLAN_PAYLOAD_SHA256 to source.payloadSha256,
      )
    if (expected.any { (key, value) -> imported[key] != value }) {
      migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
    }
    val records =
      artifactMap(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.value(child.artifacts),
      )
    val replaced = records.toMutableMap()
    listOf("preplan" to sourceShared.preplanPayload, "plan" to source.planPayload).forEach { (phase, payload) ->
      val record = artifactMap(records[phase])
      if (record[SharedPayloadKeys.PHASE_ID] != phase ||
        record[SharedPayloadKeys.STATUS] != WorkflowStepStatus.COMPLETED.wireValue ||
        record[SharedPayloadKeys.OUTPUT_ARTIFACT] != payload
      ) {
        migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      }
      if (
        record[PlanningMigrationRecordKeys.EXECUTION_ORIGIN] !=
        FeatureTaskRuntimePhaseExecutionOrigin.GOAL_PLANNING_HYDRATED.wireValue ||
        child.steps.singleOrNull { it.stepId == phase }?.status != WorkflowStepStatus.COMPLETED
      ) {
        migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      }
      replaced[phase] = record + (
        SharedPayloadKeys.OUTPUT_ARTIFACT to
          if (phase == "preplan") targetShared.preplanPayload else target.planPayload
      )
    }
    requireImportLedger(child)
    val targetVersion = target.provenance.phaseOutputContractVersion
    val updatedImport =
      imported +
        mapOf(
          GoalPlanningPreparationPayloadKeys.PHASE_OUTPUT_CONTRACT_VERSION to targetVersion,
          GoalPlanningPreparationPayloadKeys.PREPLAN_PAYLOAD_SHA256 to targetShared.payloadSha256,
          GoalPlanningPreparationPayloadKeys.PLAN_PAYLOAD_SHA256 to target.payloadSha256,
        )
    return PlanningImportMigration(sourceChild, replaced, updatedImport)
  }

  private fun requireImportLedger(child: WorkflowStateSnapshot) {
    val ledger =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.value(child.artifacts) as? List<*>
        ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
    listOf("preplan", "plan").forEachIndexed { index, phase ->
      val entry = artifactMap(ledger.getOrNull(index))
      if (entry[SharedPayloadKeys.PHASE_ID] != phase ||
        entry[DecompositionManifestPayloadKeys.ACTION] != FeatureTaskRuntimePhaseLedgerAction.COMPLETE.wireValue
      ) {
        migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      }
      if (
        (entry[PlanningMigrationRecordKeys.SEQUENCE_NUMBER] as? Number)?.toInt() != index ||
        (entry[WorkflowWirePayloadKeys.ATTEMPT_COUNT] as? Number)?.toInt() != 1
      ) {
        migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      }
    }
  }

  internal fun publish(
    session: GoalRunnerPersistenceSession,
    replacements: List<PlanningImportMigration>,
  ) {
    replacements.forEach { replacement ->
      val current = session.workflowStates.getFeatureTaskWorkflow(replacement.source.workflowId)
      if (current != replacement.source) migrationFailure(FeatureTaskRuntimeMigrationFailureCode.STALE_SOURCE)
      val artifacts =
        replacement.source.toSnapshot().artifacts +
          mapOf(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(replacement.records),
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.entry(replacement.imported),
          )
      session.workflowStates.migrateFeatureTaskArtifacts(replacement.source, JsonCodec.valueToJsonString(artifacts))
    }
  }
}

internal data class PlanningImportMigration(
  val source: WorkflowStateRecord,
  val records: Map<String, Any?>,
  val imported: Map<String, Any?>,
)

private fun artifactMap(value: Any?): Map<String, Any?> =
  (value as? Map<*, *>)?.entries
    ?.associate { (key, entry) -> key.toString() to entry }
    ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)

private object PlanningMigrationRecordKeys {
  const val EXECUTION_ORIGIN = "execution_origin"
  const val SEQUENCE_NUMBER = "sequence_number"
}
