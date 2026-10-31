package skillbill.engine.diagnostics.block

import skillbill.engine.diagnostics.block.BlockIncidentAliases.withBlockCause
import skillbill.error.blockincident.BlockIncidentFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.ports.blockincident.BlockIncidentRepository
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind

internal object BlockIncidentAcceptedReferenceResolver {
  fun validate(
    reference: GoalRunnerAcceptedBlockReference,
    repository: BlockIncidentRepository,
  ) {
    val kind =
      BlockIncidentAliasKind.entries.firstOrNull { it.wireValue == reference.aliasKind }
        ?: conflict("Accepted block reference contains an unknown alias kind.")
    val alias = BlockIncidentAlias(kind, reference.aliasValue)
    val canonical =
      BlockIncidentAliases.goal(
        reference.sourceWorkflowId,
        reference.blockedStepId,
        reference.executionGeneration,
        reference.attempt,
        reference.childIncidentToken,
      ).withBlockCause(reference.blockCause)
    val sourceAliasValue = reference.sourceAliasValue
    val sourceAlias =
      when {
        reference.sourceAliasKind != null && sourceAliasValue != null -> {
          val sourceKind =
            BlockIncidentAliasKind.entries.firstOrNull { it.wireValue == reference.sourceAliasKind }
              ?: conflict("Accepted block reference contains an unknown source alias kind.")
          BlockIncidentAlias(sourceKind, sourceAliasValue)
        }
        alias != canonical -> alias
        else -> null
      }
    val tokenOwner =
      repository.find(reference.incidentToken)
        ?: conflict("Accepted block reference does not resolve to a durable incident.")
    val aliasOwner =
      repository.findByAlias(alias)
        ?: conflict("Accepted block reference alias does not resolve to a durable incident.")
    val sourceAliasOwner = sourceAlias?.let(repository::findByAlias)
    val ownerToken = tokenOwner.occurrence.occurrenceToken
    val sourceAliasMatches = sourceAlias == null || sourceAliasOwner?.occurrence?.occurrenceToken == ownerToken
    if (ownerToken != reference.incidentToken || aliasOwner.occurrence.occurrenceToken != ownerToken ||
      !sourceAliasMatches
    ) {
      conflict("Accepted block reference alias and token have conflicting ownership.")
    }
  }

  private fun conflict(message: String): Nothing =
    throw SkillBillRuntimeException(BlockIncidentFailureCode.IMMUTABLE_CONFLICT, message)
}
