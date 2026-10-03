package skillbill.engine.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.parentSpecPath
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_MIGRATION_PATHS
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedContextPacket
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedContextPacketPayloadKeys
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.engine.migration.RuntimeMigrationReceipt
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
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
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
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
    requirePreparation: Boolean = false,
  ): RuntimeMigrationReceipt {
    val identity = GoalPlanningIdentity(parentWorkflowId, normalizedIssueKey, repositoryIdentity)
    val repository = session.goalPlanningPreparations
    val shared =
      findAdmittedShared(session, identity, requirePreparation)
        ?: return RuntimeMigrationReceipt(
          "unknown",
          FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
          RuntimeMigrationReceipt.Result.CURRENT,
        )
    val plans = repository.listSubtaskPlansForMigration(identity)
    val historical =
      shared.provenance.phaseOutputContractVersion != FEATURE_TASK_RUNTIME_CONTRACT_VERSION ||
        plans.any { it.provenance.phaseOutputContractVersion != FEATURE_TASK_RUNTIME_CONTRACT_VERSION }
    val parent =
      session.workflowStates.getFeatureTaskWorkflow(parentWorkflowId)?.toSnapshot()
        ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
    val manifest =
      parent.decompositionRuntime()
        ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
    if (manifest.issueKey.trim().uppercase() != normalizedIssueKey) {
      migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
    }
    if (!historical) {
      return validateCurrent(session, parent, shared, plans, manifest)
    }
    if (session.goalRunnerControls.controlState(parentWorkflowId).executionLease?.expiresAtInstant
        ?.isAfter(clock.instant()) == true
    ) {
      migrationFailure(FeatureTaskRuntimeMigrationFailureCode.STALE_SOURCE)
    }
    validateTopology(shared, plans, manifest, verifySavedParentSpec = true)
    val currentGate = GoalPlanningPreparationProjectionGate(envelopeValidator)
    validateCurrentSource {
      if (shared.provenance.phaseOutputContractVersion == FEATURE_TASK_RUNTIME_CONTRACT_VERSION) {
        currentGate.validateSharedPreplan(shared)
      }
      plans.filter { it.provenance.phaseOutputContractVersion == FEATURE_TASK_RUNTIME_CONTRACT_VERSION }
        .forEach(currentGate::validateSubtaskPlan)
    }
    val targetShared = migrateShared(shared)
    val targetPlans = plans.map(::migratePlan)
    val replacements = imports.prepare(session, parent, shared, plans.zip(targetPlans), targetShared)
    if (repository.findSharedPreplan(identity) != shared ||
      repository.listSubtaskPlansForMigration(identity) != plans
    ) {
      migrationFailure(FeatureTaskRuntimeMigrationFailureCode.STALE_SOURCE)
    }
    if (targetShared != shared) repository.migrateSharedPreplan(shared, targetShared)
    plans.zip(targetPlans).forEach { (source, target) ->
      if (source != target) repository.migrateSubtaskPlan(source, target)
    }
    imports.publish(session, replacements)
    val gate = GoalPlanningPreparationProjectionGate(envelopeValidator)
    gate.validateSharedPreplan(requireNotNull(repository.findSharedPreplan(identity)))
    repository.listSubtaskPlansForMigration(identity).forEach(gate::validateSubtaskPlan)
    return RuntimeMigrationReceipt(
      sourceVersion = shared.provenance.phaseOutputContractVersion,
      targetVersion = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      result = RuntimeMigrationReceipt.Result.CONVERTED,
    )
  }

  private fun validateTopology(
    shared: SharedGoalPreplanCheckpoint,
    plans: List<GoalSubtaskPlanCheckpoint>,
    manifest: DecompositionManifest,
    verifySavedParentSpec: Boolean = false,
  ) {
    val packet =
      JsonCodec.parseObjectOrNull(shared.preplanPayload)
        ?.get(SharedPayloadKeys.PRODUCED_OUTPUTS)
        ?.let(JsonCodec::jsonElementToValue)?.let(JsonCodec::anyToStringAnyMap)
        ?.get(GoalPlanningSweepConstants.SHARED_CONTEXT_FIELD)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT)
    val packetVersion =
      packet[GoalPlanningSharedContextPacketPayloadKeys.PACKET_VERSION] as? String
        ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT)
    if (packetVersion !in
      setOf(
        GoalPlanningSharedContextPacket.VERSION,
        GoalPlanningSharedContextPacket.LEGACY_VERSION_0_3,
        GoalPlanningSharedContextPacket.LEGACY_VERSION_0_2,
        GoalPlanningSharedContextPacket.LEGACY_VERSION_0_1,
      )
    ) {
      migrationFailure(FeatureTaskRuntimeMigrationFailureCode.SOURCE_UNSUPPORTED)
    }
    try {
      GoalPlanningSharedContextPacket.validateSource(
        packet,
        shared.identity.repositoryIdentity,
        shared.identity.normalizedIssueKey,
        manifest.parentSpecPath,
        manifest.subtasks,
      )
      if (verifySavedParentSpec) requireSavedParentSpec(packet, shared)
      GoalPlanningSharedContextPacket.validate(
        GoalPlanningSharedContextPacket.migrate(packet),
        shared.identity.repositoryIdentity,
        shared.identity.normalizedIssueKey,
        manifest.parentSpecPath,
        manifest.subtasks,
      )
    } catch (error: InvalidGoalPlanningPreparationSchemaError) {
      throw SkillBillRuntimeException(
        FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT,
        "Persisted shared planning context failed its source or target packet contract. Preserve the original record.",
        error,
      )
    }
    plans.forEach { plan ->
      val subtask =
        manifest.subtasks.singleOrNull { it.id == plan.subtaskId }
          ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      if (!FeatureTaskExecutionIdentityPolicy.sameGovernedSpecPath(
          subtask.specPath,
          plan.governedSubSpecPath,
          shared.identity.repositoryIdentity,
        ) ||
        manifest.subtasks.indexOf(subtask) != plan.manifestOrder ||
        plan.provenance != shared.provenance
      ) {
        migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      }
    }
  }

  private fun requireSavedParentSpec(
    packet: Map<String, Any?>,
    shared: SharedGoalPreplanCheckpoint,
  ) {
    val savedParentSpec =
      packet[GoalPlanningSharedContextPacketPayloadKeys.PARENT_SPEC] as? String
        ?: migrationFailure(FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT)
    if (sha256HexUtf8(savedParentSpec) != shared.provenance.parentSpecHash) {
      if (savedParentSpec.length >= GoalPlanningSharedContextPacket.MAX_GOVERNED_CONTEXT_CHARS) {
        throw SkillBillRuntimeException(
          FeatureTaskRuntimeMigrationFailureCode.TARGET_NON_CONVERTIBLE,
          "Saved parent-spec evidence was truncated and cannot prove its provenance hash. " +
            "Restore complete matching " +
            "saved evidence or resume with a runtime that can verify the original record.",
        )
      }
      migrationFailure(FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT)
    }
  }

  private fun findAdmittedShared(
    session: GoalRunnerPersistenceSession,
    identity: GoalPlanningIdentity,
    requirePreparation: Boolean,
  ): SharedGoalPreplanCheckpoint? {
    val repository = session.goalPlanningPreparations
    val shared =
      repository.findSharedPreplan(identity) ?: run {
        if (repository.listSubtaskPlansForMigration(identity).isEmpty() && !requirePreparation) {
          return null
        }
        migrationFailure(
          if (requirePreparation) {
            FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT
          } else {
            FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT
          },
        )
      }
    if (shared.isExplicitlyDiscarded()) {
      if (requirePreparation) migrationFailure(FeatureTaskRuntimeMigrationFailureCode.UNSAFE_IMPORT)
      return null
    }
    return shared
  }

  private fun validateCurrent(
    session: GoalRunnerPersistenceSession,
    parent: WorkflowStateSnapshot,
    shared: SharedGoalPreplanCheckpoint,
    plans: List<GoalSubtaskPlanCheckpoint>,
    manifest: DecompositionManifest,
  ): RuntimeMigrationReceipt {
    val gate = GoalPlanningPreparationProjectionGate(envelopeValidator)
    validateCurrentSource {
      gate.validateSharedPreplan(shared)
      plans.forEach(gate::validateSubtaskPlan)
    }
    validateTopology(shared, plans, manifest)
    imports.validateCurrent(session, parent, shared, plans)
    val sourceVersion =
      (
        listOf(shared.provenance.phaseOutputContractVersion) +
          plans.map {
            it.provenance.phaseOutputContractVersion
          }
      ).distinct().singleOrNull()?.takeIf { it.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}")) } ?: "unknown"
    return RuntimeMigrationReceipt(
      sourceVersion,
      FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      RuntimeMigrationReceipt.Result.CURRENT,
    )
  }

  private fun migrateShared(source: SharedGoalPreplanCheckpoint): SharedGoalPreplanCheckpoint {
    requireSource(
      source.provenance,
      source.payloadSha256,
      source.preplanPayload,
      GoalPlanningSweepConstants.PHASE_PREPLAN,
      FeatureTaskRuntimeWorkflowArtifactMap.from(source.toEnvelopeMap()),
    )
    val payload = convert(source.preplanPayload, GoalPlanningSweepConstants.PHASE_PREPLAN)
    return source.copy(
      provenance =
        source.provenance.copy(
          phaseOutputContractVersion = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        ),
      payloadSha256 = sha256HexUtf8(payload),
      preplanPayload = payload,
    ).also { target ->
      validateTarget { GoalPlanningPreparationProjectionGate(envelopeValidator).validateSharedPreplan(target) }
    }
  }

  private fun migratePlan(source: GoalSubtaskPlanCheckpoint): GoalSubtaskPlanCheckpoint {
    requireSource(
      source.provenance,
      source.payloadSha256,
      source.planPayload,
      GoalPlanningSweepConstants.PHASE_PLAN,
      FeatureTaskRuntimeWorkflowArtifactMap.from(source.toEnvelopeMap()),
    )
    val payload = convert(source.planPayload, GoalPlanningSweepConstants.PHASE_PLAN)
    return source.copy(
      provenance =
        source.provenance.copy(
          phaseOutputContractVersion = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        ),
      payloadSha256 = sha256HexUtf8(payload),
      planPayload = payload,
    ).also { target ->
      validateTarget { GoalPlanningPreparationProjectionGate(envelopeValidator).validateSubtaskPlan(target) }
    }
  }

  private fun requireSource(
    provenance: GoalPlanningContractProvenance,
    digest: String,
    payload: String,
    phaseId: String,
    envelope: FeatureTaskRuntimeWorkflowArtifactMap,
  ) {
    if (sha256HexUtf8(payload) != digest) migrationFailure(FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT)
    val phaseVersion =
      JsonCodec.parseObjectOrNull(payload)?.get(SharedPayloadKeys.CONTRACT_VERSION)
        ?.let(JsonCodec::jsonElementToValue)
    if (phaseVersion != provenance.phaseOutputContractVersion) {
      migrationFailure(FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT)
    }
    val preparationVersion = envelope[SharedPayloadKeys.CONTRACT_VERSION]
    val path =
      GOAL_PLANNING_PREPARATION_MIGRATION_PATHS.singleOrNull {
        it.sourcePreparationVersion == preparationVersion &&
          it.sourcePlanningVersion == provenance.planningContractVersion &&
          it.sourcePhaseOutputVersion == provenance.phaseOutputContractVersion
      }
    if (provenance.phaseOutputContractVersion != FEATURE_TASK_RUNTIME_CONTRACT_VERSION && path == null) {
      migrationFailure(FeatureTaskRuntimeMigrationFailureCode.SOURCE_UNSUPPORTED)
    }
    if (path != null) {
      try {
        sourceValidator.validateHistoricalPhaseOutput06(envelope, "migration")
      } catch (error: InvalidGoalPlanningPreparationSchemaError) {
        throw SkillBillRuntimeException(
          FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT,
          "Persisted planning preparation failed its declared source contract. Restore the original record and retry.",
          error,
        )
      }
    }
    try {
      readStoredPlanningRecord(payload, phaseId, "migration")
    } catch (error: InvalidGoalPlanningPreparationSchemaError) {
      throw SkillBillRuntimeException(
        FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT,
        "Persisted planning output lacks valid source evidence. Restore the original record and retry.",
        error,
      )
    }
  }

  private inline fun validateTarget(validate: () -> Unit) {
    try {
      validate()
    } catch (error: InvalidGoalPlanningPreparationSchemaError) {
      throw SkillBillRuntimeException(
        FeatureTaskRuntimeMigrationFailureCode.INVALID_TARGET,
        "Converted planning preparation failed the target contract. The transaction must preserve the source records.",
        error,
      )
    }
  }

  private inline fun validateCurrentSource(validate: () -> Unit) {
    try {
      validate()
    } catch (error: InvalidGoalPlanningPreparationSchemaError) {
      throw SkillBillRuntimeException(
        FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT,
        "Persisted planning preparation failed its declared current contract. Preserve the original records.",
        error,
      )
    }
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
    try {
      readStoredPlanningRecord(converted, phaseId, "migration")
    } catch (error: InvalidGoalPlanningPreparationSchemaError) {
      throw SkillBillRuntimeException(
        FeatureTaskRuntimeMigrationFailureCode.INVALID_TARGET,
        "Converted phase output does not contain the required planning evidence. Preserve the source record.",
        error,
      )
    }
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
