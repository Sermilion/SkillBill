package skillbill.engine.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.goal.GOAL_SHARED_PREPLAN_DISCARDED_PAYLOAD
import skillbill.contracts.workflow.goal.GoalPlanningPreparationPayloadKeys
import skillbill.error.shellcontent.invalidGoalPlanningPreparationSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

@Inject
public class GoalPlanningPreparationCheckpoint(
  private val database: DatabaseSessionFactory,
  envelopeValidator: FeatureTaskRuntimeWireArtifactValidator,
) {
  private val gate = GoalPlanningPreparationProjectionGate(envelopeValidator)

  internal fun findStoredSubtaskPlan(
    identity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint? =
    database.read {
      it.goalPlanningPreparations.findSubtaskPlan(identity, subtaskId, governedSubSpecPath)
    }.planOrThrow()

  internal fun findSharedPreplan(identity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint? =
    when (val result = findSharedPreplanResult(identity)) {
      is SharedGoalPreplanLookupResult.Found -> result.checkpoint
      is SharedGoalPreplanLookupResult.Conflicted -> throw result.conflict.toFailure()
    }

  internal fun findSharedPreplanResult(identity: GoalPlanningIdentity): SharedGoalPreplanLookupResult =
    when (val result = database.read { it.goalPlanningPreparations.findSharedPreplan(identity) }) {
      is SharedGoalPreplanLookupResult.Found ->
        SharedGoalPreplanLookupResult.Found(
          result.checkpoint?.takeUnless {
            it.isExplicitlyDiscarded()
          }?.also(gate::validateSharedPreplan),
        )
      is SharedGoalPreplanLookupResult.Conflicted -> result
    }

  internal fun findSubtaskPlan(
    identity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint? =
    database.read {
      it.goalPlanningPreparations.findSubtaskPlan(identity, subtaskId, governedSubSpecPath)
    }.planOrThrow()?.also(gate::validateSubtaskPlan)
}

internal class GoalPlanningPreparationProjectionGate(
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
}

private fun requirePlanningPayloadHash(
  expected: String,
  payload: String,
  label: String,
) {
  if (sha256HexUtf8(payload) != expected) {
    throw invalidGoalPlanningPreparationSchemaError(
      label,
      "payload_sha256",
      "payload_sha256 does not match the exact UTF-8 payload bytes",
    )
  }
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
