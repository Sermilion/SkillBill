package skillbill.engine.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.parentSpecPath
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_MIGRATION_PATHS
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedContextPacket
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.ports.goalrunner.GoalPlanningPreparationSourceValidator
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.taskruntime.model.FeatureTaskRuntimePhaseOutputMigrationResult
import skillbill.ports.workflow.model.toSnapshot
import skillbill.text.sha256HexUtf8
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.time.Clock

@Inject
class GoalPlanningMigration(
  private val sourceValidator: GoalPlanningPreparationSourceValidator,
  private val outputs: FeatureTaskRuntimePhaseOutputMigration,
  private val envelopeValidator: FeatureTaskRuntimeWireArtifactValidator,
  private val imports: GoalPlanningMigrationImports,
  private val clock: Clock,
) {
  fun migrate(
    session: GoalRunnerPersistenceSession,
    parentWorkflowId: String,
    repositoryIdentity: String,
    normalizedIssueKey: String,
  ): Boolean {
    val identity = GoalPlanningIdentity(parentWorkflowId, normalizedIssueKey, repositoryIdentity)
    val repository = session.goalPlanningPreparations
    val shared = repository.findSharedPreplan(identity) ?: return false
    if (shared.isExplicitlyDiscarded()) return false
    val plans = repository.listSubtaskPlansForMigration(identity)
    val historical =
      shared.provenance.phaseOutputContractVersion != FEATURE_TASK_RUNTIME_CONTRACT_VERSION ||
        plans.any { it.provenance.phaseOutputContractVersion != FEATURE_TASK_RUNTIME_CONTRACT_VERSION }
    if (!historical) {
      val gate = GoalPlanningPreparationProjectionGate(envelopeValidator)
      gate.validateSharedPreplan(shared)
      plans.forEach(gate::validateSubtaskPlan)
      return false
    }
    if (session.goalRunnerControls.controlState(parentWorkflowId).executionLease?.expiresAtInstant
        ?.isAfter(clock.instant()) == true
    ) {
      migrationFailure(FeatureTaskRuntimeMigrationFailureCode.STALE_SOURCE)
    }
    val parent =
      session.workflowStates.getFeatureTaskWorkflow(parentWorkflowId)?.toSnapshot()
        ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
    val manifest =
      parent.decompositionRuntime()
        ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
    if (manifest.issueKey.trim().uppercase() != normalizedIssueKey) {
      migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
    }
    val targetShared = migrateShared(shared)
    val targetPlans = plans.map(::migratePlan)
    validateTopology(shared, plans, manifest)
    val replacements = imports.prepare(session, parent, shared, plans.zip(targetPlans), targetShared)
    if (targetShared != shared) repository.migrateSharedPreplan(shared, targetShared)
    plans.zip(targetPlans).forEach { (source, target) ->
      if (source != target) repository.migrateSubtaskPlan(source, target)
    }
    imports.publish(session, replacements)
    val gate = GoalPlanningPreparationProjectionGate(envelopeValidator)
    gate.validateSharedPreplan(requireNotNull(repository.findSharedPreplan(identity)))
    repository.listSubtaskPlansForMigration(identity).forEach(gate::validateSubtaskPlan)
    return true
  }

  private fun validateTopology(
    shared: SharedGoalPreplanCheckpoint,
    plans: List<GoalSubtaskPlanCheckpoint>,
    manifest: DecompositionManifest,
  ) {
    val packet =
      JsonCodec.parseObjectOrNull(shared.preplanPayload)
        ?.get(SharedPayloadKeys.PRODUCED_OUTPUTS)
        ?.let(JsonCodec::jsonElementToValue)?.let(JsonCodec::anyToStringAnyMap)
        ?.get(GoalPlanningSweepConstants.SHARED_CONTEXT_FIELD)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.TARGET_NON_CONVERTIBLE)
    GoalPlanningSharedContextPacket.validate(
      GoalPlanningSharedContextPacket.migrate(packet),
      shared.identity.repositoryIdentity,
      shared.identity.normalizedIssueKey,
      manifest.parentSpecPath,
      manifest.subtasks,
    )
    plans.forEach { plan ->
      val subtask =
        manifest.subtasks.singleOrNull { it.id == plan.subtaskId }
          ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      if (subtask.specPath != plan.governedSubSpecPath ||
        manifest.subtasks.indexOf(subtask) != plan.manifestOrder || plan.provenance != shared.provenance
      ) {
        migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      }
    }
  }

  private fun migrateShared(source: SharedGoalPreplanCheckpoint): SharedGoalPreplanCheckpoint {
    requireSource(
      source.contractVersion,
      source.provenance,
      source.payloadSha256,
      source.preplanPayload,
      FeatureTaskRuntimeWorkflowArtifactMap.from(source.toEnvelopeMap()),
    )
    val payload = convert(source.preplanPayload, "preplan")
    return source.copy(
      provenance =
        source.provenance.copy(
          phaseOutputContractVersion = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        ),
      payloadSha256 = sha256HexUtf8(payload),
      preplanPayload = payload,
    ).also(GoalPlanningPreparationProjectionGate(envelopeValidator)::validateSharedPreplan)
  }

  private fun migratePlan(source: GoalSubtaskPlanCheckpoint): GoalSubtaskPlanCheckpoint {
    requireSource(
      source.contractVersion,
      source.provenance,
      source.payloadSha256,
      source.planPayload,
      FeatureTaskRuntimeWorkflowArtifactMap.from(source.toEnvelopeMap()),
    )
    val payload = convert(source.planPayload, "plan")
    return source.copy(
      provenance =
        source.provenance.copy(
          phaseOutputContractVersion = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        ),
      payloadSha256 = sha256HexUtf8(payload),
      planPayload = payload,
    ).also(GoalPlanningPreparationProjectionGate(envelopeValidator)::validateSubtaskPlan)
  }

  private fun requireSource(
    preparationVersion: String,
    provenance: GoalPlanningContractProvenance,
    digest: String,
    payload: String,
    envelope: FeatureTaskRuntimeWorkflowArtifactMap,
  ) {
    if (sha256HexUtf8(payload) != digest) migrationFailure(FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT)
    val phaseVersion =
      JsonCodec.parseObjectOrNull(payload)?.get(SharedPayloadKeys.CONTRACT_VERSION)
        ?.let(JsonCodec::jsonElementToValue)
    if (phaseVersion != provenance.phaseOutputContractVersion) {
      migrationFailure(FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT)
    }
    if (provenance.phaseOutputContractVersion == FEATURE_TASK_RUNTIME_CONTRACT_VERSION) return
    if (GOAL_PLANNING_PREPARATION_MIGRATION_PATHS.none {
        it.sourcePreparationVersion == preparationVersion &&
          it.sourcePlanningVersion == provenance.planningContractVersion &&
          it.sourcePhaseOutputVersion == provenance.phaseOutputContractVersion
      }
    ) {
      migrationFailure(FeatureTaskRuntimeMigrationFailureCode.SOURCE_UNSUPPORTED)
    }
    sourceValidator.validateHistoricalPhaseOutput06(envelope, "migration")
  }

  private fun convert(
    payload: String,
    phaseId: String,
  ): String {
    val result = outputs.migrate(payload)
    val converted =
      when (result) {
        is FeatureTaskRuntimePhaseOutputMigrationResult.Current -> result.payload
        is FeatureTaskRuntimePhaseOutputMigrationResult.Migrated -> result.payload
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
    readStoredPlanningRecord(converted, phaseId, "migration")
    return converted
  }
}

internal fun migrationFailure(code: FeatureTaskRuntimeMigrationFailureCode): Nothing =
  throw SkillBillRuntimeException(
    code,
    "Durable planning migration was refused with ${code.name.lowercase()}. " +
      "Preserve the original records. Restore matching evidence or use a runtime with a supported conversion, " +
      "then retry.",
  )
