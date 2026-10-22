package skillbill.engine.diagnostics.block

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePhasePayloadKeys
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentOccurrenceKeys
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentSpoolKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind

internal data class BlockIncidentIdentity(
  val stepId: String,
  val attempt: Int?,
  val generation: Long?,
  val childWorkflowId: String?,
  val childIncidentToken: String?,
  val incidentToken: String,
  val alias: BlockIncidentAlias,
  val sourceAlias: BlockIncidentAlias? = null,
) {
  fun acceptedReference(workflowId: String): GoalRunnerAcceptedBlockReference =
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
    )

  fun artifactEntry(): Pair<String, Any?> =
    DurableWorkflowArtifactFamily.GOAL_BLOCK_IDENTITY.entry(
      linkedMapOf(
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
      ),
    )

  companion object {
    private fun invalidIdentity(): Nothing =
      throw invalidWorkflowStateSchemaError(
        "Invalid durable block incident identity.",
      )

    fun matchesBlockedAttempt(
      artifacts: Map<String, Any?>,
      stepId: String,
      attempt: Int?,
      currentAttempt: Int?,
    ): Boolean =
      currentAttempt?.takeIf { it > 0 } == attempt ||
        previousBlockedAttempt(artifacts, stepId)?.takeIf { it > 0 } == attempt

    fun acceptedFor(
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

    fun fromArtifacts(artifacts: Map<String, Any?>): BlockIncidentIdentity? {
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
      )
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

internal fun GoalRunnerAcceptedBlockReference.boundTo(
  workflowId: String,
  blockedStepId: String?,
): GoalRunnerAcceptedBlockReference? = takeIf { it.sourceWorkflowId == workflowId && it.blockedStepId == blockedStepId }

internal object OperatorBlockRetryArtifactKeys {
  const val REASON = "reason"
  const val RETRIED_AT = "retried_at"
  const val PREVIOUS_BLOCKED_REASON = "previous_blocked_reason"
  const val PREVIOUS_BLOCKED_RECORD = "previous_blocked_record"
}

private object BlockIncidentIdentityKeys {
  const val CHILD_WORKFLOW_ID = "child_workflow_id"
  const val CHILD_INCIDENT_TOKEN = "child_incident_token"
  const val INCIDENT_TOKEN = "incident_token"
  const val SOURCE_ALIAS_KIND = "source_alias_kind"
  const val SOURCE_ALIAS_VALUE = "source_alias_value"
}

internal object BlockIncidentDisplacementKeys {
  const val ACCEPTED_BLOCK_REFERENCE = "accepted_block_reference"
  const val ADDITIONAL_ACCEPTED_BLOCK_REFERENCES = "additional_accepted_block_references"
  const val EXECUTION_GENERATION = "execution_generation"
  const val ALIAS_KIND = "alias_kind"
  const val ALIAS_VALUE = "alias_value"
  const val INCIDENT_TOKEN = "incident_token"
  const val CHILD_INCIDENT_TOKEN = "child_incident_token"
  const val SOURCE_ALIAS_KIND = "source_alias_kind"
  const val SOURCE_ALIAS_VALUE = "source_alias_value"
}
