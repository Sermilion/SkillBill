package skillbill.engine.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.goal.GOAL_SHARED_PREPLAN_DISCARDED_PAYLOAD
import skillbill.contracts.workflow.goal.GoalPlanningPreparationPayloadKeys
import skillbill.engine.goalrunner.planning.model.GoalPlanningPreparationProgress
import skillbill.engine.goalrunner.planning.model.GoalPlanningRecoveryProgress
import skillbill.engine.goalrunner.planning.model.expectedProvenance
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.text.sha256HexUtf8
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

@Inject
class GoalPlanningPreparationCheckpoint(
  private val database: DatabaseSessionFactory,
  private val envelopeValidator: FeatureTaskRuntimeWireArtifactValidator,
) {
  private val gate = GoalPlanningPreparationProjectionGate(envelopeValidator)
  private val preparationValidator = GoalPlanningPreparationValidator()

  fun checkpoint(record: GoalPlanningPreparationRecord) {
    val canonical = preparationValidator.canonicalize(record)
    envelopeValidator.validate(
      FeatureTaskRuntimeWireArtifactKind.GOAL_PLANNING_PREPARATION_ENVELOPE,
      FeatureTaskRuntimeWorkflowArtifactMap.from(canonical.toEnvelopeMap()),
      "${canonical.parentGoalWorkflowId}#${canonical.subtaskId}",
    )
    database.selfManagedWrite { unitOfWork ->
      unitOfWork.goalPlanningPreparations.markPrepared(canonical)
    }
  }

  fun validate(record: GoalPlanningPreparationRecord) {
    val sourceLabel = "${record.parentGoalWorkflowId}#${record.subtaskId}"
    envelopeValidator.validate(
      FeatureTaskRuntimeWireArtifactKind.GOAL_PLANNING_PREPARATION_ENVELOPE,
      FeatureTaskRuntimeWorkflowArtifactMap.from(record.toEnvelopeMap()),
      sourceLabel,
    )
    preparationValidator.validate(record)
  }

  fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) {
    gate.validateSharedPreplan(checkpoint)
    database.selfManagedWrite { it.goalPlanningPreparations.checkpointSharedPreplan(checkpoint) }
  }

  fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    gate.validateSubtaskPlan(checkpoint)
    database.selfManagedWrite { it.goalPlanningPreparations.checkpointSubtaskPlan(checkpoint) }
  }

  fun recheckpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) {
    gate.validateSharedPreplan(checkpoint)
    val stored = database.read { it.goalPlanningPreparations.findSharedPreplan(checkpoint.identity) }
    if (stored?.isExplicitlyDiscarded() == true) {
      database.selfManagedWrite {
        it.goalPlanningPreparations.replaceSharedPreplan(checkpoint, stored.payloadSha256, emptyList())
      }
    } else {
      stored?.let(gate::validateSharedPreplan)
      database.selfManagedWrite { it.goalPlanningPreparations.checkpointSharedPreplan(checkpoint) }
    }
  }

  val sharedPreplanRefresh: GoalPlanningSharedPreplanRefresh =
    GoalPlanningSharedPreplanRefresh(database, gate)

  fun recheckpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    gate.validateSubtaskPlan(checkpoint)
    val stored =
      findStoredSubtaskPlan(
        checkpoint.identity,
        checkpoint.subtaskId,
        checkpoint.governedSubSpecPath,
      )
    stored?.let(gate::validateSubtaskPlan)
    database.selfManagedWrite { it.goalPlanningPreparations.checkpointSubtaskPlan(checkpoint) }
  }

  fun findStoredSubtaskPlan(
    identity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint? =
    database.read {
      it.goalPlanningPreparations.findSubtaskPlan(identity, subtaskId, governedSubSpecPath)
    }

  fun findSharedPreplan(identity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint? =
    database.read { it.goalPlanningPreparations.findSharedPreplan(identity) }
      ?.takeUnless { it.isExplicitlyDiscarded() }
      ?.also(gate::validateSharedPreplan)

  fun findSubtaskPlan(
    identity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint? =
    database.read {
      it.goalPlanningPreparations.findSubtaskPlan(identity, subtaskId, governedSubSpecPath)
    }?.also(gate::validateSubtaskPlan)

  private fun requireRecoverablePlan(
    identity: GoalPlanningIdentity,
    plan: GoalSubtaskPlanCheckpoint,
    expectedDescriptor: GovernedGoalSubtaskDescriptor,
  ) {
    val divergence =
      when {
        plan.manifestOrder != expectedDescriptor.manifestOrder ->
          "stored manifest order differs from the authoritative decomposition manifest"
        plan.subSpecHash != expectedDescriptor.subSpecHash ->
          "stored governed sub-spec hash differs from the current governed sub-spec"
        else -> null
      } ?: return
    throw IncompatibleGoalPlanningPreparationRecoveryError(identity.parentGoalWorkflowId, plan.subtaskId, divergence)
  }

  private fun nonCompletedPlanPayloadReason(planPayload: String): String? {
    val parsed =
      JsonCodec.parseObjectOrNull(planPayload)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?: return null
    val status = parsed[SharedPayloadKeys.STATUS]?.toString()
    if (status.workflowStepStatus() == WorkflowStepStatus.COMPLETED) return null
    return "stored plan payload has status '$status' but must be completed with non-empty produced_outputs"
  }

  fun recoveryProgress(
    identity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
    expectedProvenance: GoalPlanningContractProvenance,
  ): GoalPlanningRecoveryProgress {
    val sharedPrepared = findSharedPreplan(identity) != null
    val prepared = mutableListOf<GoalSubtaskPlanCheckpoint>()
    orderedDescriptors.forEach { descriptor ->
      when (val read = readPlanForRecovery(identity, descriptor, expectedProvenance)) {
        is PlanRecoveryRead.Incomplete ->
          return GoalPlanningRecoveryProgress.IncompletePlan(
            identity.parentGoalWorkflowId,
            read.subtaskId,
            read.reason,
          )
        is PlanRecoveryRead.Prepared -> prepared += read.plan
        PlanRecoveryRead.Absent -> Unit
      }
    }
    val preparedIds = prepared.mapTo(mutableSetOf()) { it.subtaskId }
    return GoalPlanningRecoveryProgress.Ready(
      GoalPlanningPreparationProgress(
        sharedPreplanPrepared = sharedPrepared,
        preparedPlanCount = prepared.size,
        expectedPlanCount = orderedDescriptors.size,
        missingSubtaskIds = orderedDescriptors.filterNot { it.subtaskId in preparedIds }.map { it.subtaskId },
      ),
    )
  }

  private fun readPlanForRecovery(
    identity: GoalPlanningIdentity,
    descriptor: GovernedGoalSubtaskDescriptor,
    expectedProvenance: GoalPlanningContractProvenance,
  ): PlanRecoveryRead {
    val plan =
      database.read {
        it.goalPlanningPreparations.findSubtaskPlan(identity, descriptor.subtaskId, descriptor.governedSubSpecPath)
      } ?: return PlanRecoveryRead.Absent
    requireRecoverablePlan(identity, plan, descriptor)
    val incompleteReason = nonCompletedPlanPayloadReason(plan.planPayload)
    if (incompleteReason != null) return PlanRecoveryRead.Incomplete(plan.subtaskId, incompleteReason)
    gate.validateSubtaskPlan(plan)
    return when {
      plan.provenance != expectedProvenance ->
        throw IncompatibleGoalPlanningPreparationRecoveryError(
          identity.parentGoalWorkflowId,
          descriptor.subtaskId,
          "stored plan provenance differs from the governing shared preplan",
        )
      else -> PlanRecoveryRead.Prepared(plan)
    }
  }
}

private sealed interface PlanRecoveryRead {
  data object Absent : PlanRecoveryRead

  data class Prepared(val plan: GoalSubtaskPlanCheckpoint) : PlanRecoveryRead

  data class Incomplete(val subtaskId: Int, val reason: String) : PlanRecoveryRead
}

class GoalPlanningSharedPreplanRefresh(
  private val database: DatabaseSessionFactory,
  private val gate: GoalPlanningPreparationProjectionGate,
) {
  fun listPreparedPlanSubtaskIds(parentGoalWorkflowId: String): List<Int> =
    database.read {
      it.goalPlanningPreparations.listPreparedPlanSubtaskIds(parentGoalWorkflowId)
    }

  fun advanceSharedPreplanProvenance(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
    provenance: GoalPlanningContractProvenance,
  ) {
    database.selfManagedWrite {
      it.goalPlanningPreparations.advanceSharedPreplanProvenance(identity, expectedPayloadSha256, provenance)
    }
  }

  fun replaceSharedPreplanForRefresh(
    checkpoint: SharedGoalPreplanCheckpoint,
    expectedPayloadSha256: String,
    cascadePlanSubtaskIds: List<Int>,
  ): SharedGoalPreplanCheckpoint {
    gate.validateSharedPreplan(checkpoint)
    database.selfManagedWrite {
      it.goalPlanningPreparations.replaceSharedPreplan(checkpoint, expectedPayloadSha256, cascadePlanSubtaskIds)
    }
    return checkpoint
  }
}

class GoalPlanningPreparationProjectionGate(
  private val envelopeValidator: FeatureTaskRuntimeWireArtifactValidator,
) {
  fun validateSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) {
    val label = checkpoint.identity.parentGoalWorkflowId
    envelopeValidator.validate(
      FeatureTaskRuntimeWireArtifactKind.GOAL_PLANNING_PREPARATION_ENVELOPE,
      FeatureTaskRuntimeWorkflowArtifactMap.from(checkpoint.toEnvelopeMap()),
      label,
    )
    requirePlanningPayloadHash(checkpoint.payloadSha256, checkpoint.preplanPayload, label)
    readStoredPlanningRecord(checkpoint.preplanPayload, "preplan", label)
  }

  fun validateSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    val label = "${checkpoint.identity.parentGoalWorkflowId}#${checkpoint.subtaskId}"
    envelopeValidator.validate(
      FeatureTaskRuntimeWireArtifactKind.GOAL_PLANNING_PREPARATION_ENVELOPE,
      FeatureTaskRuntimeWorkflowArtifactMap.from(checkpoint.toEnvelopeMap()),
      label,
    )
    requirePlanningPayloadHash(checkpoint.payloadSha256, checkpoint.planPayload, label)
    readStoredPlanningRecord(checkpoint.planPayload, "plan", label)
  }

  fun sharedPreplanRejection(checkpoint: SharedGoalPreplanCheckpoint): String? =
    planningRecordRejection { validateSharedPreplan(checkpoint) }

  fun subtaskPlanRejection(checkpoint: GoalSubtaskPlanCheckpoint): String? =
    planningRecordRejection { validateSubtaskPlan(checkpoint) }
}

private fun requirePlanningPayloadHash(
  expected: String,
  payload: String,
  label: String,
) {
  if (sha256HexUtf8(payload) != expected) {
    throw InvalidGoalPlanningPreparationSchemaError(
      label,
      "payload_sha256",
      "payload_sha256 does not match the exact UTF-8 payload bytes",
    )
  }
}

private fun planningRecordRejection(compute: () -> Unit): String? =
  try {
    compute()
    null
  } catch (error: InvalidGoalPlanningPreparationSchemaError) {
    "stored record failed its durable contract: ${error.message.orEmpty()}"
  } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
    "stored record failed its durable contract: ${error.message.orEmpty()}"
  }

internal fun SharedGoalPreplanCheckpoint.toEnvelopeMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.CONTRACT_VERSION to contractVersion,
    GoalPlanningPreparationPayloadKeys.RECORD_TYPE to "shared_preplan",
    GoalPlanningPreparationPayloadKeys.IDENTITY to identity.asMap(),
    GoalPlanningPreparationPayloadKeys.PREPARATION_STATUS to preparationStatus.wireValue,
    GoalPlanningPreparationPayloadKeys.PROVENANCE to provenance.asMap(),
    GoalPlanningPreparationPayloadKeys.PAYLOAD_SHA256 to payloadSha256,
    GoalPlanningPreparationPayloadKeys.PREPLAN_PAYLOAD to preplanPayload,
    GoalPlanningPreparationPayloadKeys.REPAIR_EVIDENCE to repairEvidence?.asWorkflowArtifactEntry(),
  ).filterValues { it != null }

internal fun GoalSubtaskPlanCheckpoint.toEnvelopeMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.CONTRACT_VERSION to contractVersion,
    GoalPlanningPreparationPayloadKeys.RECORD_TYPE to "subtask_plan",
    GoalPlanningPreparationPayloadKeys.IDENTITY to identity.asMap(),
    SharedPayloadKeys.SUBTASK_ID to subtaskId,
    GoalPlanningPreparationPayloadKeys.MANIFEST_ORDER to manifestOrder,
    GoalPlanningPreparationPayloadKeys.GOVERNED_SUB_SPEC_PATH to governedSubSpecPath,
    GoalPlanningPreparationPayloadKeys.SUB_SPEC_HASH to subSpecHash,
    GoalPlanningPreparationPayloadKeys.PREPARATION_STATUS to preparationStatus.wireValue,
    GoalPlanningPreparationPayloadKeys.PROVENANCE to provenance.asMap(),
    GoalPlanningPreparationPayloadKeys.PAYLOAD_SHA256 to payloadSha256,
    GoalPlanningPreparationPayloadKeys.PLAN_PAYLOAD to planPayload,
    GoalPlanningPreparationPayloadKeys.REPAIR_EVIDENCE to repairEvidence?.asWorkflowArtifactEntry(),
  ).filterValues { it != null }

private fun GoalPlanningIdentity.asMap() =
  linkedMapOf(
    GoalPlanningPreparationPayloadKeys.PARENT_GOAL_WORKFLOW_ID to parentGoalWorkflowId,
    GoalPlanningPreparationPayloadKeys.NORMALIZED_ISSUE_KEY to normalizedIssueKey,
    GoalPlanningPreparationPayloadKeys.REPOSITORY_IDENTITY to repositoryIdentity,
  )

private fun GoalPlanningContractProvenance.asMap() =
  linkedMapOf(
    GoalPlanningPreparationPayloadKeys.PARENT_SPEC_HASH to parentSpecHash,
    GoalPlanningPreparationPayloadKeys.DECOMPOSITION_MANIFEST_HASH to decompositionManifestHash,
    GoalPlanningPreparationPayloadKeys.PLANNING_CONTRACT_ID to planningContractId,
    GoalPlanningPreparationPayloadKeys.PLANNING_CONTRACT_VERSION to planningContractVersion,
    GoalPlanningPreparationPayloadKeys.PHASE_OUTPUT_CONTRACT_ID to phaseOutputContractId,
    GoalPlanningPreparationPayloadKeys.PHASE_OUTPUT_CONTRACT_VERSION to phaseOutputContractVersion,
  )

internal fun SharedGoalPreplanCheckpoint.isExplicitlyDiscarded(): Boolean =
  preplanPayload == GOAL_SHARED_PREPLAN_DISCARDED_PAYLOAD && payloadSha256 == sha256HexUtf8(preplanPayload)
