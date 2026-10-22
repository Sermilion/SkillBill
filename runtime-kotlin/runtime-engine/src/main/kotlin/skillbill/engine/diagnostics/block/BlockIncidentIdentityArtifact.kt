package skillbill.engine.diagnostics.block

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentOccurrenceKeys
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentSpoolKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference

internal data class BlockIncidentIdentity(
  val stepId: String,
  val attempt: Int,
  val generation: Long?,
  val childWorkflowId: String?,
  val childIncidentToken: String?,
  val incidentToken: String,
  val aliasSequence: Long,
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
      aliasSequence = aliasSequence,
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
        BlockIncidentIdentityKeys.ALIAS_SEQUENCE to aliasSequence,
        BlockIncidentSpoolKeys.ALIAS_KIND to alias.kind.wireValue,
        BlockIncidentSpoolKeys.ALIAS_VALUE to alias.value,
        BlockIncidentIdentityKeys.SOURCE_ALIAS_KIND to sourceAlias?.kind?.wireValue,
        BlockIncidentIdentityKeys.SOURCE_ALIAS_VALUE to sourceAlias?.value,
      ),
    )

  companion object {
    private fun invalidIdentity(): Nothing = throw invalidWorkflowStateSchemaError("Invalid durable block incident identity.")

    fun fromArtifacts(artifacts: Map<String, Any?>): BlockIncidentIdentity? {
      val raw = DurableWorkflowArtifactFamily.GOAL_BLOCK_IDENTITY.value(artifacts) ?: return null
      val value = raw as? Map<*, *> ?: invalidIdentity()
      val stepId = value[SharedPayloadKeys.STEP_ID] as? String ?: invalidIdentity()
      val attempt = (value[SharedPayloadKeys.ATTEMPT] as? Number)?.toInt() ?: invalidIdentity()
      val sourceAliasKind = value[BlockIncidentIdentityKeys.SOURCE_ALIAS_KIND] as? String
      val sourceAliasValue = value[BlockIncidentIdentityKeys.SOURCE_ALIAS_VALUE] as? String
      if ((sourceAliasKind == null) != (sourceAliasValue == null) ||
        (sourceAliasKind != null && BlockIncidentAliasKind.entries.none { it.wireValue == sourceAliasKind })
      ) invalidIdentity()
      return BlockIncidentIdentity(
        stepId = stepId,
        attempt = attempt,
        generation = (value[BlockIncidentOccurrenceKeys.GENERATION] as? Number)?.toLong(),
        childWorkflowId = value[BlockIncidentIdentityKeys.CHILD_WORKFLOW_ID] as? String,
        childIncidentToken = value[BlockIncidentIdentityKeys.CHILD_INCIDENT_TOKEN] as? String,
        incidentToken = value[BlockIncidentIdentityKeys.INCIDENT_TOKEN] as? String ?: invalidIdentity(),
        aliasSequence = (value[BlockIncidentIdentityKeys.ALIAS_SEQUENCE] as? Number)?.toLong() ?: invalidIdentity(),
        alias = BlockIncidentAlias(
          BlockIncidentAliasKind.entries.firstOrNull { it.wireValue == value[BlockIncidentSpoolKeys.ALIAS_KIND] }
            ?: invalidIdentity(),
          value[BlockIncidentSpoolKeys.ALIAS_VALUE] as? String ?: invalidIdentity(),
        ),
        sourceAlias = sourceAliasKind?.let { rawKind ->
          val kind = BlockIncidentAliasKind.entries.firstOrNull { it.wireValue == rawKind } ?: invalidIdentity()
          BlockIncidentAlias(kind, sourceAliasValue ?: invalidIdentity())
        },
      )
    }

    fun acceptedReferenceFromDisplacement(artifacts: Map<String, Any?>): GoalRunnerAcceptedBlockReference? {
      val rawDisplacement = DurableWorkflowArtifactFamily.GOAL_CONTINUATION_OUTCOME_DISPLACEMENT.value(artifacts)
        ?: return null
      val displacement = rawDisplacement as? Map<*, *> ?: invalidIdentity()
      val raw = (displacement[BlockIncidentDisplacementKeys.ADDITIONAL_ACCEPTED_BLOCK_REFERENCES] as? List<*>)
        ?.lastOrNull()
        ?: displacement[BlockIncidentDisplacementKeys.ACCEPTED_BLOCK_REFERENCE]
        ?: return null
      val value = raw as? Map<*, *> ?: invalidIdentity()
      val workflowId = value[SharedPayloadKeys.WORKFLOW_ID] as? String ?: invalidIdentity()
      val aliasKind = value[BlockIncidentDisplacementKeys.ALIAS_KIND] as? String ?: invalidIdentity()
      if (BlockIncidentAliasKind.entries.none { it.wireValue == aliasKind }) invalidIdentity()
      val sourceAliasKind = value[BlockIncidentDisplacementKeys.SOURCE_ALIAS_KIND] as? String
      val sourceAliasValue = value[BlockIncidentDisplacementKeys.SOURCE_ALIAS_VALUE] as? String
      if ((sourceAliasKind == null) != (sourceAliasValue == null) ||
        (sourceAliasKind != null && BlockIncidentAliasKind.entries.none { it.wireValue == sourceAliasKind })
      ) invalidIdentity()
      return GoalRunnerAcceptedBlockReference(
        sourceWorkflowId = workflowId,
        blockedStepId = value[SharedPayloadKeys.STEP_ID] as? String ?: invalidIdentity(),
        attempt = (value[SharedPayloadKeys.ATTEMPT] as? Number)?.toInt() ?: invalidIdentity(),
        executionGeneration = (value[BlockIncidentDisplacementKeys.EXECUTION_GENERATION] as? Number)?.toLong(),
        aliasKind = aliasKind,
        aliasValue = value[BlockIncidentDisplacementKeys.ALIAS_VALUE] as? String ?: invalidIdentity(),
        aliasSequence = (value[BlockIncidentDisplacementKeys.ALIAS_SEQUENCE] as? Number)?.toLong() ?: invalidIdentity(),
        incidentToken = value[BlockIncidentDisplacementKeys.INCIDENT_TOKEN] as? String ?: invalidIdentity(),
        childIncidentToken = value[BlockIncidentDisplacementKeys.CHILD_INCIDENT_TOKEN] as? String,
        sourceAliasKind = sourceAliasKind,
        sourceAliasValue = sourceAliasValue,
      )
    }
  }
}

private object BlockIncidentIdentityKeys {
  const val CHILD_WORKFLOW_ID = "child_workflow_id"
  const val CHILD_INCIDENT_TOKEN = "child_incident_token"
  const val INCIDENT_TOKEN = "incident_token"
  const val ALIAS_SEQUENCE = "alias_sequence"
  const val SOURCE_ALIAS_KIND = "source_alias_kind"
  const val SOURCE_ALIAS_VALUE = "source_alias_value"
}

internal object BlockIncidentDisplacementKeys {
  const val ACCEPTED_BLOCK_REFERENCE = "accepted_block_reference"
  const val ADDITIONAL_ACCEPTED_BLOCK_REFERENCES = "additional_accepted_block_references"
  const val EXECUTION_GENERATION = "execution_generation"
  const val ALIAS_KIND = "alias_kind"
  const val ALIAS_VALUE = "alias_value"
  const val ALIAS_SEQUENCE = "alias_sequence"
  const val INCIDENT_TOKEN = "incident_token"
  const val CHILD_INCIDENT_TOKEN = "child_incident_token"
  const val SOURCE_ALIAS_KIND = "source_alias_kind"
  const val SOURCE_ALIAS_VALUE = "source_alias_value"
}
