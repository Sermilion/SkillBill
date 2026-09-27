package skillbill.engine.featuretask.phase.prompt.directives

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeSchemaFailureCorrections
import skillbill.workflow.taskruntime.model.repair.task.FeatureTaskRuntimeCorrectiveRepairContext

fun retryCorrectionDirective(
  briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  priorSchemaFailure: String?,
  correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext?,
  shape: PhaseRetryShape = PhaseRetryShape(),
  stepCorrection: ((String) -> String)? = null,
): String {
  if (priorSchemaFailure.isNullOrBlank()) {
    return ""
  }
  val base =
    """
    ## Previous attempt was REJECTED by the schema gate — salvage the capture
    Programmatic extraction and shape repair could not accept the previous output. Reason:
    $priorSchemaFailure
    This is the last salvage attempt. Rewrite the untrusted prior output into exactly one JSON object
    matching the expected shape below. Keep facts already in that capture; do not redo the phase work
    and do not emit a second envelope. The runtime will extract and validate the result the same way;
    if it still fails, the run blocks.

    Expected shape:
    """.trimIndent() + "\n" + retrySkeleton(briefing, shape)
  val structuralRepairNote =
    correctiveRepairContext?.structuralRepairEvidence?.let { evidence ->
      "\nDeterministic syntax repair previously succeeded on this capture (delimiter-only; " +
        "original_digest=${evidence.originalDigest} repaired_digest=${evidence.repairedDigest} " +
        "source=${evidence.sourceLocation.sourceLabel}:" +
        "${evidence.sourceLocation.line}:${evidence.sourceLocation.column}). " +
        "That does not mean the phase schema accepted it; correct the named schema or semantic violation."
    } ?: if (correctiveRepairContext?.acceptedAfterStructuralRepair == true) {
      "\nDeterministic syntax repair previously succeeded on this capture (delimiter-only). " +
        "That does not mean the phase schema accepted it; correct the named schema or semantic violation."
    } else {
      ""
    }
  val repairProjection =
    correctiveRepairContext?.let { context ->
      "\n\n" + context.promptProjection().renderAuthorizedRepairSection()
    }.orEmpty()
  return base + structuralRepairNote + repairProjection +
    unparseableRootCorrection(priorSchemaFailure) +
    FeatureTaskRuntimeSchemaFailureCorrections.lengthViolation(priorSchemaFailure) +
    stepCorrection?.invoke(priorSchemaFailure).orEmpty()
}

private fun unparseableRootCorrection(priorSchemaFailure: String): String {
  val rootNotParseable =
    priorSchemaFailure.contains("<root> must be an object") ||
      priorSchemaFailure.contains("Phase output is malformed")
  if (!rootNotParseable) {
    return ""
  }
  return "\nThe runtime could NOT parse a single JSON object out of your previous output — you likely " +
    "answered\nwith prose, a Markdown table, or a JSON array. None of those can advance the gate. Salvage " +
    "that capture into the expected shape above."
}

data class PhaseRetryShape(
  val verdictLine: String? = null,
  val producedOutputsEntry: String = "\"result\": \"<concrete output for downstream phases>\"",
)

private fun retrySkeleton(
  briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  shape: PhaseRetryShape,
): String =
  buildList {
    add("```json")
    add("{")
    add("  \"contract_version\": \"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION\",")
    add("  \"phase_id\": \"${briefing.phaseId}\",")
    add("  \"status\": \"completed\",")
    shape.verdictLine?.let(::add)
    add("  \"summary\": \"<one sentence describing what this phase did>\",")
    add("  \"produced_outputs\": { ${shape.producedOutputsEntry} }")
    add("}")
    add("```")
  }.joinToString(separator = "\n")
