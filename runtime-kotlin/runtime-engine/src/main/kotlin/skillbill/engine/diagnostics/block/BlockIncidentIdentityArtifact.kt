package skillbill.engine.diagnostics.block

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePhasePayloadKeys
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentAcceptedChildCauseKeys
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentOccurrenceKeys
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentSpoolKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

public data class BlockIncidentFailureFacts(
  public val failureCode: String?,
  public val failureDisposition: FeatureTaskRuntimeFailureDisposition?,
)

public data class BlockIncidentAcceptedChildCause(
  public val parentIncidentToken: String,
  public val reference: GoalRunnerAcceptedBlockReference,
) {
  public fun artifactEntry(): Pair<String, Any?> =
    DurableWorkflowArtifactFamily.GOAL_BLOCK_ACCEPTED_CHILD_CAUSE.entry(
      mapOf(
        BlockIncidentAcceptedChildCauseKeys.PARENT_INCIDENT_TOKEN to parentIncidentToken,
        BlockIncidentAcceptedChildCauseKeys.CHILD_REFERENCE to reference.toArtifactMap().toMap(),
      ),
    )

  public companion object {
    private fun invalidChildCause(): Nothing =
      throw invalidWorkflowStateSchemaError(
        "Invalid accepted child block cause.",
      )

    public fun fromArtifacts(artifacts: Map<String, Any?>): BlockIncidentAcceptedChildCause? {
      val raw = DurableWorkflowArtifactFamily.GOAL_BLOCK_ACCEPTED_CHILD_CAUSE.value(artifacts) ?: return null
      val value = raw as? Map<*, *> ?: invalidChildCause()
      val parentToken =
        (value[BlockIncidentAcceptedChildCauseKeys.PARENT_INCIDENT_TOKEN] as? String)
          ?.takeIf(String::isNotBlank)
          ?: invalidChildCause()
      val reference =
        GoalRunnerAcceptedBlockReference.fromArtifactMap(
          value[BlockIncidentAcceptedChildCauseKeys.CHILD_REFERENCE],
        ) ?: invalidChildCause()
      return BlockIncidentAcceptedChildCause(parentToken, reference)
    }
  }
}

public data class FailurePhaseEvidence(public val phaseId: String?)

public data class BlockIncidentIdentity(
  public val stepId: String,
  public val attempt: Int?,
  public val generation: Long?,
  public val childWorkflowId: String?,
  public val childIncidentToken: String?,
  public val incidentToken: String,
  public val alias: BlockIncidentAlias,
  public val sourceAlias: BlockIncidentAlias? = null,
  public val failureFacts: BlockIncidentFailureFacts? = null,
  public val blockCause: String? = null,
  public val failurePhaseEvidence: FailurePhaseEvidence? = null,
) {
  public val failureCode: String? get() = failureFacts?.failureCode
  public val failureDisposition: FeatureTaskRuntimeFailureDisposition? get() = failureFacts?.failureDisposition

  public fun acceptedReference(workflowId: String): GoalRunnerAcceptedBlockReference =
    GoalRunnerAcceptedBlockReference(
      sourceWorkflowId = workflowId,
      blockedStepId = stepId,
      attempt = attempt,
      executionGeneration = generation,
      aliasKind = alias.kind.wireValue,
      aliasValue = alias.value,
      incidentToken = incidentToken,
      childIncidentToken = childIncidentToken,
      sourceAliasKind = sourceAlias?.kind?.wireValue,
      sourceAliasValue = sourceAlias?.value,
      blockCause = blockCause,
    )

  public fun artifactEntry(): Pair<String, Any?> =
    DurableWorkflowArtifactFamily.GOAL_BLOCK_IDENTITY.entry(
      linkedMapOf<String, Any?>(
        SharedPayloadKeys.STEP_ID to stepId,
        SharedPayloadKeys.ATTEMPT to attempt,
        BlockIncidentOccurrenceKeys.GENERATION to generation,
        BlockIncidentIdentityKeys.CHILD_WORKFLOW_ID to childWorkflowId,
        BlockIncidentIdentityKeys.CHILD_INCIDENT_TOKEN to childIncidentToken,
        BlockIncidentIdentityKeys.INCIDENT_TOKEN to incidentToken,
        BlockIncidentSpoolKeys.ALIAS_KIND to alias.kind.wireValue,
        BlockIncidentSpoolKeys.ALIAS_VALUE to alias.value,
        BlockIncidentIdentityKeys.SOURCE_ALIAS_KIND to sourceAlias?.kind?.wireValue,
        BlockIncidentIdentityKeys.SOURCE_ALIAS_VALUE to sourceAlias?.value,
      ).apply {
        failureFacts?.let { put(BlockIncidentIdentityKeys.FAILURE_FACTS, it.toArtifactMap()) }
        blockCause?.let { put(BlockIncidentIdentityKeys.BLOCK_CAUSE, it) }
        failurePhaseEvidence?.let {
          put(BlockIncidentIdentityKeys.FAILURE_PHASE_EVIDENCE, mapOf(SharedPayloadKeys.PHASE_ID to it.phaseId))
        }
      },
    )

  private fun BlockIncidentFailureFacts.toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      BlockIncidentIdentityKeys.FAILURE_CODE to failureCode,
      BlockIncidentIdentityKeys.FAILURE_DISPOSITION to failureDisposition?.wireValue,
    )

  public companion object {
    private fun invalidIdentity(): Nothing =
      throw invalidWorkflowStateSchemaError(
        "Invalid durable block incident identity.",
      )

    internal fun matchesBlockedAttempt(
      artifacts: Map<String, Any?>,
      stepId: String,
      attempt: Int?,
      currentAttempt: Int?,
    ): Boolean =
      currentAttempt?.takeIf { it > 0 } == attempt ||
        previousBlockedAttempt(artifacts, stepId)?.takeIf { it > 0 } == attempt

    public fun acceptedFor(
      artifacts: Map<String, Any?>,
      steps: List<WorkflowStepState>,
      blockedStepId: String?,
      workflowId: String,
    ): GoalRunnerAcceptedBlockReference? =
      fromArtifacts(artifacts)
        ?.takeIf { identity ->
          identity.stepId == blockedStepId &&
            matchesBlockedAttempt(
              artifacts,
              identity.stepId,
              identity.attempt,
              steps.firstOrNull { step -> step.stepId == identity.stepId }?.attemptCount,
            )
        }
        ?.acceptedReference(workflowId)

    private fun previousBlockedAttempt(
      artifacts: Map<String, Any?>,
      stepId: String,
    ): Int? {
      val retry =
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY.value(artifacts) as? Map<*, *>
          ?: return null
      val previous = retry[OperatorBlockRetryArtifactKeys.PREVIOUS_BLOCKED_RECORD] as? Map<*, *> ?: return null
      if (previous[SharedPayloadKeys.PHASE_ID] != stepId) return null
      return (previous[FeatureTaskRuntimePhasePayloadKeys.ATTEMPT_COUNT] as? Number)?.toInt()
    }

    public fun fromArtifacts(artifacts: Map<String, Any?>): BlockIncidentIdentity? {
      val raw = DurableWorkflowArtifactFamily.GOAL_BLOCK_IDENTITY.value(artifacts) ?: return null
      val value = raw as? Map<*, *> ?: invalidIdentity()
      val attempt =
        value[SharedPayloadKeys.ATTEMPT]?.let {
          (it as? Number)?.toInt()?.takeIf { attempt -> attempt > 0 } ?: invalidIdentity()
        }
      return BlockIncidentIdentity(
        stepId = value[SharedPayloadKeys.STEP_ID] as? String ?: invalidIdentity(),
        attempt = attempt,
        generation = (value[BlockIncidentOccurrenceKeys.GENERATION] as? Number)?.toLong(),
        childWorkflowId = value[BlockIncidentIdentityKeys.CHILD_WORKFLOW_ID] as? String,
        childIncidentToken = value[BlockIncidentIdentityKeys.CHILD_INCIDENT_TOKEN] as? String,
        incidentToken = value[BlockIncidentIdentityKeys.INCIDENT_TOKEN] as? String ?: invalidIdentity(),
        alias =
          BlockIncidentAlias(
            aliasKind(value[BlockIncidentSpoolKeys.ALIAS_KIND]) ?: invalidIdentity(),
            value[BlockIncidentSpoolKeys.ALIAS_VALUE] as? String ?: invalidIdentity(),
          ),
        sourceAlias = sourceAlias(value),
        failurePhaseEvidence = failurePhaseEvidence(value),
        failureFacts = failureFacts(value),
        blockCause =
          value[BlockIncidentIdentityKeys.BLOCK_CAUSE]?.let {
            (it as? String)?.takeIf(String::isNotBlank) ?: invalidIdentity()
          },
      )
    }

    private fun failurePhaseEvidence(value: Map<*, *>): FailurePhaseEvidence? {
      if (!value.containsKey(BlockIncidentIdentityKeys.FAILURE_PHASE_EVIDENCE)) return null
      val evidence = value[BlockIncidentIdentityKeys.FAILURE_PHASE_EVIDENCE] as? Map<*, *> ?: invalidIdentity()
      if (evidence.keys != setOf(SharedPayloadKeys.PHASE_ID)) invalidIdentity()
      val phaseId =
        evidence[SharedPayloadKeys.PHASE_ID]?.let {
          (it as? String)?.takeIf(String::isNotBlank) ?: invalidIdentity()
        }
      return FailurePhaseEvidence(phaseId)
    }

    private fun failureFacts(value: Map<*, *>): BlockIncidentFailureFacts? {
      if (!value.containsKey(BlockIncidentIdentityKeys.FAILURE_FACTS)) return null
      val raw = value[BlockIncidentIdentityKeys.FAILURE_FACTS] as? Map<*, *> ?: invalidIdentity()
      val expectedKeys = setOf(BlockIncidentIdentityKeys.FAILURE_CODE, BlockIncidentIdentityKeys.FAILURE_DISPOSITION)
      if (raw.keys.any { it !is String } || raw.keys != expectedKeys) {
        invalidIdentity()
      }
      val failureCode = raw[BlockIncidentIdentityKeys.FAILURE_CODE]?.let { it as? String ?: invalidIdentity() }
      val failureDisposition =
        raw[BlockIncidentIdentityKeys.FAILURE_DISPOSITION]?.let { rawDisposition ->
          FeatureTaskRuntimeFailureDisposition.entries.firstOrNull { it.wireValue == rawDisposition }
            ?: invalidIdentity()
        }
      return BlockIncidentFailureFacts(failureCode, failureDisposition)
    }

    private fun sourceAlias(value: Map<*, *>): BlockIncidentAlias? {
      val kind = value[BlockIncidentIdentityKeys.SOURCE_ALIAS_KIND] as? String
      val aliasValue = value[BlockIncidentIdentityKeys.SOURCE_ALIAS_VALUE] as? String
      if ((kind == null) != (aliasValue == null)) invalidIdentity()
      if (kind == null || aliasValue == null) return null
      return BlockIncidentAlias(aliasKind(kind) ?: invalidIdentity(), aliasValue)
    }

    private fun aliasKind(raw: Any?): BlockIncidentAliasKind? =
      BlockIncidentAliasKind.entries.firstOrNull {
        it.wireValue == raw
      }
  }
}

public fun GoalRunnerAcceptedBlockReference.boundTo(
  workflowId: String,
  blockedStepId: String?,
): GoalRunnerAcceptedBlockReference? = takeIf { it.sourceWorkflowId != workflowId || it.blockedStepId == blockedStepId }

public object OperatorBlockRetryArtifactKeys {
  public const val REASON: String = "reason"
  public const val RETRIED_AT: String = "retried_at"
  public const val PREVIOUS_BLOCKED_REASON: String = "previous_blocked_reason"
  public const val PREVIOUS_BLOCKED_RECORD: String = "previous_blocked_record"
}

private object BlockIncidentIdentityKeys {
  const val CHILD_WORKFLOW_ID = "child_workflow_id"
  const val CHILD_INCIDENT_TOKEN = "child_incident_token"
  const val INCIDENT_TOKEN = "incident_token"
  const val SOURCE_ALIAS_KIND = "source_alias_kind"
  const val SOURCE_ALIAS_VALUE = "source_alias_value"
  const val FAILURE_PHASE_EVIDENCE = "failure_phase_evidence"
  const val FAILURE_FACTS = "failure_facts"
  const val FAILURE_CODE = "failure_code"
  const val FAILURE_DISPOSITION = "failure_disposition"
  const val BLOCK_CAUSE = "block_cause"
}

public object BlockIncidentDisplacementKeys {
  public const val ACCEPTED_BLOCK_REFERENCE: String = "accepted_block_reference"
  public const val ADDITIONAL_ACCEPTED_BLOCK_REFERENCES: String = "additional_accepted_block_references"
  public const val EXECUTION_GENERATION: String = "execution_generation"
  public const val ALIAS_KIND: String = "alias_kind"
  public const val ALIAS_VALUE: String = "alias_value"
  public const val INCIDENT_TOKEN: String = "incident_token"
  public const val CHILD_INCIDENT_TOKEN: String = "child_incident_token"
  public const val SOURCE_ALIAS_KIND: String = "source_alias_kind"
  public const val SOURCE_ALIAS_VALUE: String = "source_alias_value"
}
