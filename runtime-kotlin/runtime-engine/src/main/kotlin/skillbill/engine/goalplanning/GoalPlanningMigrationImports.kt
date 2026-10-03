package skillbill.engine.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePhasePayloadKeys
import skillbill.contracts.workflow.goal.GoalPlanningPreparationPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimePhaseOutputMigrationResult
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.phaseLedger
import skillbill.workflow.taskruntime.artifact.phaseRecords
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseExecutionOrigin
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import java.time.Clock

@Inject
class GoalPlanningMigrationImports(
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val clock: Clock,
  private val outputs: FeatureTaskRuntimePhaseOutputMigration,
  private val snapshotValidator: WorkflowSnapshotValidator,
) {
  internal fun prepare(
    session: GoalRunnerPersistenceSession,
    parent: WorkflowStateSnapshot,
    sourceShared: SharedGoalPreplanCheckpoint,
    plans: List<Pair<GoalSubtaskPlanCheckpoint, GoalSubtaskPlanCheckpoint>>,
    targetShared: SharedGoalPreplanCheckpoint,
  ): List<PlanningImportMigration> = prepareImports(session, parent, sourceShared to targetShared, plans, true)

  internal fun validateCurrent(
    session: GoalRunnerPersistenceSession,
    parent: WorkflowStateSnapshot,
    shared: SharedGoalPreplanCheckpoint,
    plans: List<GoalSubtaskPlanCheckpoint>,
  ) {
    prepareImports(session, parent, shared to shared, plans.map { it to it }, false)
  }

  private fun prepareImports(
    session: GoalRunnerPersistenceSession,
    parent: WorkflowStateSnapshot,
    shared: Pair<SharedGoalPreplanCheckpoint, SharedGoalPreplanCheckpoint>,
    plans: List<Pair<GoalSubtaskPlanCheckpoint, GoalSubtaskPlanCheckpoint>>,
    migrating: Boolean,
  ): List<PlanningImportMigration> {
    val sourceShared = shared.first
    val manifest = requireNotNull(parent.decompositionRuntime())
    val linked = session.workflowStates.listGoalChildWorkflowIdsByParent(parent.workflowId).toSet()
    val named = manifest.subtasks.mapNotNull { it.workflowId?.takeIf(String::isNotBlank) }.toSet()
    if (named != linked) migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
    return named.mapNotNull { workflowId ->
      val child =
        session.workflowStates.getFeatureTaskWorkflow(workflowId)
          ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      val ownership =
        session.workflowStates.getFeatureTaskExecutionIdentity(workflowId)
          ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      val subtask = manifest.subtasks.single { it.workflowId == workflowId }
      if (ownership.mode != FeatureTaskWorkflowMode.RUNTIME ||
        ownership.routeScope != FeatureTaskRouteScope.GOAL_CHILD
      ) {
        migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      }
      if (
        ownership.repositoryIdentity != sourceShared.identity.repositoryIdentity ||
        ownership.normalizedIssueKey != sourceShared.identity.normalizedIssueKey ||
        !FeatureTaskExecutionIdentityPolicy.sameGovernedSpecPath(
          ownership.governedSpecPath,
          subtask.specPath,
          ownership.repositoryIdentity,
        )
      ) {
        migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      }
      if (migrating) {
        session.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId)?.let { worker ->
          if (worker.expiresAtInstant.isAfter(clock.instant()) ||
            supervisor.inspect(worker) != FeatureTaskRuntimeProcessInspection.NotRunning
          ) {
            migrationFailure(FeatureTaskRuntimeMigrationFailureCode.STALE_SOURCE)
          }
        }
      }
      val source =
        plans.singleOrNull { it.first.subtaskId == subtask.id }?.first
          ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      val target = plans.single { it.first.subtaskId == subtask.id }.second
      prepareChild(child, shared, source to target, migrating)
    }
  }

  private fun prepareChild(
    sourceChild: WorkflowStateRecord,
    shared: Pair<SharedGoalPreplanCheckpoint, SharedGoalPreplanCheckpoint>,
    plan: Pair<GoalSubtaskPlanCheckpoint, GoalSubtaskPlanCheckpoint>,
    migrating: Boolean,
  ): PlanningImportMigration? {
    val sourceShared = shared.first
    val targetShared = shared.second
    val source = plan.first
    val target = plan.second
    val child = sourceChild.toSnapshot()
    try {
      snapshotValidator.validate(child, sourceChild.workflowName)
      child.artifacts.phaseRecords()
      child.artifacts.phaseLedger()
    } catch (error: InvalidWorkflowStateSchemaError) {
      throw SkillBillRuntimeException(
        FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT,
        "Imported child workflow failed its source snapshot contract. Preserve the original record.",
        error,
      )
    }
    val imported = requireMatchingImport(child, sourceShared, source)
    val replaced = migrateRecords(child, shared, plan, migrating)
    requireImportLedger(child)
    if (!migrating) return null
    val targetVersion = target.provenance.phaseOutputContractVersion
    val updatedImport =
      imported +
        mapOf(
          GoalPlanningPreparationPayloadKeys.PHASE_OUTPUT_CONTRACT_VERSION to targetVersion,
          GoalPlanningPreparationPayloadKeys.PREPLAN_PAYLOAD_SHA256 to targetShared.payloadSha256,
          GoalPlanningPreparationPayloadKeys.PLAN_PAYLOAD_SHA256 to target.payloadSha256,
        )
    val targetArtifacts =
      child.artifacts +
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(requireNotNull(replaced)),
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.entry(updatedImport),
        )
    try {
      snapshotValidator.validate(
        child.copy(artifacts = DurableWorkflowArtifacts.fromMap(targetArtifacts)),
        sourceChild.workflowName,
      )
      DurableWorkflowArtifacts.fromMap(targetArtifacts).phaseRecords()
      DurableWorkflowArtifacts.fromMap(targetArtifacts).phaseLedger()
    } catch (error: InvalidWorkflowStateSchemaError) {
      throw SkillBillRuntimeException(
        FeatureTaskRuntimeMigrationFailureCode.INVALID_TARGET,
        "Migrated child workflow failed target snapshot validation. Preserve the source transaction.",
        error,
      )
    }
    return PlanningImportMigration(sourceChild, replaced, updatedImport)
  }

  private fun migrateRecords(
    child: WorkflowStateSnapshot,
    shared: Pair<SharedGoalPreplanCheckpoint, SharedGoalPreplanCheckpoint>,
    plan: Pair<GoalSubtaskPlanCheckpoint, GoalSubtaskPlanCheckpoint>,
    migrating: Boolean,
  ): MutableMap<String, Any?>? {
    val sourceShared = shared.first
    val targetShared = shared.second
    val source = plan.first
    val target = plan.second
    val records =
      artifactMap(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.value(child.artifacts),
      )
    val replaced = if (migrating) records.toMutableMap() else null
    listOf(
      GoalPlanningSweepConstants.PHASE_PREPLAN to sourceShared.preplanPayload,
      GoalPlanningSweepConstants.PHASE_PLAN to source.planPayload,
    ).forEach { (phase, payload) ->
      val record = artifactMap(records[phase])
      if (record[SharedPayloadKeys.PHASE_ID] != phase ||
        record[SharedPayloadKeys.STATUS] != WorkflowStepStatus.COMPLETED.wireValue ||
        record[SharedPayloadKeys.OUTPUT_ARTIFACT] != payload
      ) {
        migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      }
      if (
        record[FeatureTaskRuntimePhasePayloadKeys.EXECUTION_ORIGIN] !=
        FeatureTaskRuntimePhaseExecutionOrigin.GOAL_PLANNING_HYDRATED.wireValue ||
        child.steps.singleOrNull { it.stepId == phase }?.status != WorkflowStepStatus.COMPLETED
      ) {
        migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      }
      val parentRepairEvidence =
        if (phase == GoalPlanningSweepConstants.PHASE_PREPLAN) sourceShared.repairEvidence else source.repairEvidence
      if (
        parentRepairEvidence?.asWorkflowArtifactEntry() !=
        record[FeatureTaskRuntimePhasePayloadKeys.REPAIR_EVIDENCE]
      ) {
        migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      }
      if (migrating) {
        replaced?.set(
          phase,
          record + (
            SharedPayloadKeys.OUTPUT_ARTIFACT to
              if (phase == GoalPlanningSweepConstants.PHASE_PREPLAN) targetShared.preplanPayload else target.planPayload
          ),
        )
      }
    }
    if (migrating) migrateIndependentRecords(records, requireNotNull(replaced))
    return replaced
  }

  private fun migrateIndependentRecords(
    records: Map<String, Any?>,
    replaced: MutableMap<String, Any?>,
  ) {
    records.forEach { (phase, value) ->
      if (phase in setOf(GoalPlanningSweepConstants.PHASE_PREPLAN, GoalPlanningSweepConstants.PHASE_PLAN)) {
        return@forEach
      }
      val record = artifactMap(value)
      if (record[SharedPayloadKeys.PHASE_ID] != phase) {
        migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      }
      val output = record[SharedPayloadKeys.OUTPUT_ARTIFACT] as? String ?: return@forEach
      val envelope = JsonCodec.parseObjectOrNull(output)
      if (envelope != null &&
        envelope[SharedPayloadKeys.PHASE_ID]?.let(JsonCodec::jsonElementToValue) != phase
      ) {
        migrationFailure(FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT)
      }
      when (
        val result =
          outputs.migrate(output)
      ) {
        is FeatureTaskRuntimePhaseOutputMigrationResult.Current -> Unit
        is FeatureTaskRuntimePhaseOutputMigrationResult.Migrated ->
          replaced.set(phase, record + (SharedPayloadKeys.OUTPUT_ARTIFACT to result.payload))
        is FeatureTaskRuntimePhaseOutputMigrationResult.Refused ->
          migrationFailure(
            when (result.result) {
              FeatureTaskRuntimePhaseOutputMigrationResult.Refusal.UNSUPPORTED ->
                FeatureTaskRuntimeMigrationFailureCode.SOURCE_UNSUPPORTED
              FeatureTaskRuntimePhaseOutputMigrationResult.Refusal.CORRUPT ->
                FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT
              FeatureTaskRuntimePhaseOutputMigrationResult.Refusal.NON_CONVERTIBLE ->
                FeatureTaskRuntimeMigrationFailureCode.TARGET_NON_CONVERTIBLE
            },
          )
      }
    }
  }

  private fun requireMatchingImport(
    child: WorkflowStateSnapshot,
    sourceShared: SharedGoalPreplanCheckpoint,
    source: GoalSubtaskPlanCheckpoint,
  ): Map<String, Any?> {
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
    return imported
  }

  private fun requireImportLedger(child: WorkflowStateSnapshot) {
    val ledger =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.value(child.artifacts) as? List<*>
        ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
    listOf(GoalPlanningSweepConstants.PHASE_PREPLAN, GoalPlanningSweepConstants.PHASE_PLAN)
      .forEachIndexed { index, phase ->
        val entry = artifactMap(ledger.getOrNull(index))
        if (entry[SharedPayloadKeys.PHASE_ID] != phase ||
          entry[DecompositionManifestPayloadKeys.ACTION] != FeatureTaskRuntimePhaseLedgerAction.COMPLETE.wireValue
        ) {
          migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
        }
        if (
          (entry[FeatureTaskRuntimePhasePayloadKeys.SEQUENCE_NUMBER] as? Number)?.toInt() != index ||
          (entry[WorkflowWirePayloadKeys.ATTEMPT_COUNT] as? Number)?.toInt() != 1 ||
          entry[FeatureTaskRuntimePhasePayloadKeys.RESOLVED_AGENT_ID] != null
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
      val sourceSnapshot = replacement.source.toSnapshot()
      val sourceRecords =
        artifactMap(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.value(sourceSnapshot.artifacts))
      val sourceImport =
        artifactMap(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.value(sourceSnapshot.artifacts),
        )
      if (sourceRecords != replacement.records || sourceImport != replacement.imported) {
        val artifacts =
          sourceSnapshot.artifacts +
            mapOf(
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(replacement.records),
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.entry(replacement.imported),
            )
        try {
          session.workflowStates.migrateFeatureTaskArtifacts(replacement.source, JsonCodec.valueToJsonString(artifacts))
        } catch (error: InvalidWorkflowStateSchemaError) {
          throw SkillBillRuntimeException(
            FeatureTaskRuntimeMigrationFailureCode.INVALID_TARGET,
            "Migrated child workflow failed target snapshot validation. Preserve the source transaction.",
            error,
          )
        }
      }
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
