package skillbill.engine.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.goalrunner.planning.model.GoalPlanningPreparationProgress
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

  fun recheckpointSharedPreplan(
    checkpoint: SharedGoalPreplanCheckpoint,
    cascadePlanSubtaskIds: List<Int> = emptyList(),
  ) {
    gate.validateSharedPreplan(checkpoint)
    val stored = database.read { it.goalPlanningPreparations.findSharedPreplan(checkpoint.identity) }
    if (stored != null && gate.sharedPreplanIsRegenerable(stored)) {
      database.selfManagedWrite {
        it.goalPlanningPreparations.replaceSharedPreplan(checkpoint, stored.payloadSha256, cascadePlanSubtaskIds)
      }
    } else {
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
    if (stored != null && gate.subtaskPlanIsRegenerable(stored)) {
      database.selfManagedWrite { it.goalPlanningPreparations.replaceSubtaskPlan(checkpoint) }
    } else {
      database.selfManagedWrite { it.goalPlanningPreparations.checkpointSubtaskPlan(checkpoint) }
    }
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
      ?.takeIf { gate.sharedPreplanRejection(it) == null }

  fun findSubtaskPlan(
    identity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
    expectedDescriptor: GovernedGoalSubtaskDescriptor? = null,
  ): GoalSubtaskPlanCheckpoint? =
    database.read {
      it.goalPlanningPreparations.findSubtaskPlan(identity, subtaskId, governedSubSpecPath)
    }?.let { plan ->
      expectedDescriptor?.let { requireRecoverablePlan(identity, plan, it) }
      plan.takeIf { gate.subtaskPlanRejection(plan) == null }
    }

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
        else -> nonCompletedPlanPayloadReason(plan.planPayload)
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
  ): GoalPlanningPreparationProgress {
    val sharedPrepared = findSharedPreplan(identity) != null
    val prepared =
      orderedDescriptors.mapNotNull { descriptor ->
        findSubtaskPlan(
          identity,
          descriptor.subtaskId,
          descriptor.governedSubSpecPath,
          descriptor,
        )?.also { plan ->
          if (plan.provenance != expectedProvenance) {
            throw IncompatibleGoalPlanningPreparationRecoveryError(
              identity.parentGoalWorkflowId,
              descriptor.subtaskId,
              "stored plan provenance differs from the governing shared preplan",
            )
          }
        }
      }
    val preparedIds = prepared.mapTo(mutableSetOf()) { it.subtaskId }
    return GoalPlanningPreparationProgress(
      sharedPreplanPrepared = sharedPrepared,
      preparedPlanCount = prepared.size,
      expectedPlanCount = orderedDescriptors.size,
      missingSubtaskIds = orderedDescriptors.filterNot { it.subtaskId in preparedIds }.map { it.subtaskId },
    )
  }
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

  fun sharedPreplanIsRegenerable(stored: SharedGoalPreplanCheckpoint): Boolean = sharedPreplanRejection(stored) != null

  fun subtaskPlanIsRegenerable(stored: GoalSubtaskPlanCheckpoint): Boolean = subtaskPlanRejection(stored) != null
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

private fun SharedGoalPreplanCheckpoint.toEnvelopeMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.CONTRACT_VERSION to contractVersion,
    "record_type" to "shared_preplan",
    "identity" to identity.asMap(),
    "preparation_status" to preparationStatus.wireValue,
    "provenance" to provenance.asMap(),
    "payload_sha256" to payloadSha256,
    "preplan_payload" to preplanPayload,
    "repair_evidence" to repairEvidence?.asWorkflowArtifactEntry(),
  ).filterValues { it != null }

private fun GoalSubtaskPlanCheckpoint.toEnvelopeMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.CONTRACT_VERSION to contractVersion,
    "record_type" to "subtask_plan",
    "identity" to identity.asMap(),
    SharedPayloadKeys.SUBTASK_ID to subtaskId,
    "manifest_order" to manifestOrder,
    "governed_sub_spec_path" to governedSubSpecPath,
    "sub_spec_hash" to subSpecHash, "preparation_status" to preparationStatus.wireValue,
    "provenance" to provenance.asMap(), "payload_sha256" to payloadSha256, "plan_payload" to planPayload,
    "repair_evidence" to repairEvidence?.asWorkflowArtifactEntry(),
  ).filterValues { it != null }

private fun GoalPlanningIdentity.asMap() =
  linkedMapOf(
    "parent_goal_workflow_id" to parentGoalWorkflowId,
    "normalized_issue_key" to normalizedIssueKey,
    "repository_identity" to repositoryIdentity,
  )

private fun GoalPlanningContractProvenance.asMap() =
  linkedMapOf(
    "parent_spec_hash" to parentSpecHash,
    "decomposition_manifest_hash" to decompositionManifestHash,
    "planning_contract_id" to planningContractId,
    "planning_contract_version" to planningContractVersion,
    "phase_output_contract_id" to phaseOutputContractId,
    "phase_output_contract_version" to phaseOutputContractVersion,
  )
