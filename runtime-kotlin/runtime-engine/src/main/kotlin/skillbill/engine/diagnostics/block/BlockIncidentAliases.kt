package skillbill.engine.diagnostics.block

import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import java.security.MessageDigest

internal object BlockIncidentAliases {
  fun featureTaskPhase(workflowId: String, phaseId: String, invocationId: String, attempt: Int) =
    BlockIncidentAlias(BlockIncidentAliasKind.PHASE_TRANSITION, "$workflowId:$phaseId:$invocationId:$attempt")

  fun standalonePhase(invocationId: String, stepId: String) =
    BlockIncidentAlias(BlockIncidentAliasKind.PHASE_TRANSITION, "$invocationId:$stepId")

  fun goal(workflowId: String, stepId: String, attemptOrGeneration: Long) =
    BlockIncidentAlias(BlockIncidentAliasKind.PHASE_TRANSITION, "$workflowId:goal:$stepId:$attemptOrGeneration")

  fun childCause(childToken: String) = BlockIncidentAlias(BlockIncidentAliasKind.CHILD_CAUSE, childToken)

  fun entryBoundary(identity: String, attempt: Int?) =
    BlockIncidentAlias(BlockIncidentAliasKind.ENTRY_BOUNDARY, "$identity:${attempt ?: 0}")

  fun fallback(observation: BlockIncidentObservation): BlockIncidentAlias = observation.alias ?: run {
    val identity = listOfNotNull(
      observation.workflowId,
      observation.phaseId,
      observation.invocationId,
      observation.executionId,
    ).joinToString(":")
    require(identity.isNotBlank()) { "A block incident requires an actual correlation identity or explicit alias." }
    if (observation.workflowId != null && observation.phaseId != null) {
      BlockIncidentAlias(BlockIncidentAliasKind.PHASE_TRANSITION, "$identity:${observation.attempt ?: 0}")
    } else {
      entryBoundary(identity, observation.attempt)
    }
  }

  fun token(alias: BlockIncidentAlias): String {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest("${alias.kind.wireValue}:${alias.value}".toByteArray())
    return "bi_" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }
}
