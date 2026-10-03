package skillbill.engine.goalrunner.planning.recovery

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.goalrunner.planning.context.GoalPlanningSpecCanonicalization
import skillbill.engine.goalrunner.telemetry.GoalRunnerBestEffortEmission
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.text.sha256HexUtf8

internal sealed interface GoalPlanningProvenanceRecoverability {
  class Reuse(val provenance: GoalPlanningContractProvenance) : GoalPlanningProvenanceRecoverability

  class StaleValid(val provenance: GoalPlanningContractProvenance) : GoalPlanningProvenanceRecoverability

  class Irrecoverable(val recoveryKind: GoalPlanningRecoveryKind) : GoalPlanningProvenanceRecoverability
}

internal fun classifyGoalPlanningProvenanceRecoverability(
  existing: SharedGoalPreplanCheckpoint?,
  current: GoalPlanningContractProvenance,
  savedParentSpec: String?,
  currentParentSpec: String,
): GoalPlanningProvenanceRecoverability {
  if (existing == null) return GoalPlanningProvenanceRecoverability.Reuse(current)
  val saved = existing.provenance
  val contractCompatible =
    saved.planningContractId == current.planningContractId &&
      saved.planningContractVersion == current.planningContractVersion &&
      saved.phaseOutputContractId == current.phaseOutputContractId &&
      saved.phaseOutputContractVersion == current.phaseOutputContractVersion
  val sourceValid =
    savedParentSpec != null && sha256HexUtf8(savedParentSpec) == saved.parentSpecHash &&
      sha256HexUtf8(existing.preplanPayload) == existing.payloadSha256
  if (!contractCompatible || !sourceValid) {
    return GoalPlanningProvenanceRecoverability.Irrecoverable(GoalPlanningRecoveryKind.BLOCKED)
  }
  if (saved.decompositionManifestHash != current.decompositionManifestHash) {
    return GoalPlanningProvenanceRecoverability.Irrecoverable(GoalPlanningRecoveryKind.SCOPED_REPLAN)
  }
  val fresh =
    GoalPlanningSpecCanonicalization.canonical(requireNotNull(savedParentSpec)) ==
      GoalPlanningSpecCanonicalization.canonical(currentParentSpec)
  return if (fresh) {
    GoalPlanningProvenanceRecoverability.Reuse(saved)
  } else {
    GoalPlanningProvenanceRecoverability.StaleValid(saved)
  }
}

fun preplanProseValue(preplanPayload: String): String =
  preplanProducedOutputs(preplanPayload)[SharedPayloadKeys.VALUE] as? String
    ?: throw InvalidGoalPlanningPreparationSchemaError(
      PREPLAN_PAYLOAD_SOURCE_LABEL,
      "${SharedPayloadKeys.PRODUCED_OUTPUTS}.${SharedPayloadKeys.VALUE}",
      "must be a string.",
    )

fun preplanProsePrompt(preplanPayload: String): String? =
  preplanProducedOutputs(preplanPayload)[SharedPayloadKeys.PROMPT]
    ?.toString()
    ?.takeIf(String::isNotBlank)

private const val PREPLAN_PAYLOAD_SOURCE_LABEL = "shared preplan payload"

private fun preplanProducedOutputs(preplanPayload: String): Map<String, Any?> =
  preplanPayloadObject(preplanPayload)[SharedPayloadKeys.PRODUCED_OUTPUTS]?.let(JsonCodec::anyToStringAnyMap)
    ?: throw InvalidGoalPlanningPreparationSchemaError(
      PREPLAN_PAYLOAD_SOURCE_LABEL,
      SharedPayloadKeys.PRODUCED_OUTPUTS,
      "must be an object.",
    )

private fun preplanPayloadObject(preplanPayload: String): Map<String, Any?> =
  GoalRunnerBestEffortEmission.runCancellable {
    JsonCodec.parseObjectOrNull(preplanPayload)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
  }.getOrElse { error ->
    GoalRunnerBestEffortEmission.rethrowIfCancellation(error)
    throw preplanPayloadNotAnObject(error)
  } ?: throw preplanPayloadNotAnObject(null)

private fun preplanPayloadNotAnObject(cause: Throwable?) =
  InvalidGoalPlanningPreparationSchemaError(
    PREPLAN_PAYLOAD_SOURCE_LABEL,
    "",
    "must be a JSON object.",
    cause,
  )

fun preplanProseValueHash(preplanPayload: String): String = sha256HexUtf8(preplanProseValue(preplanPayload))

fun preplanProsePromptHash(preplanPayload: String): String = sha256HexUtf8(preplanProsePrompt(preplanPayload).orEmpty())
