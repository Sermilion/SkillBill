package skillbill.engine.featuretask.phase.core

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementBlockRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementCompleteRequest
import skillbill.engine.featuretask.runner.facts
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.text.sha256HexUtf8

private val SETTLEMENT_TARGET = Regex("""workflow_id "([^"]+)",\s+phase_id "[^"]+",\s+attempt (\d+)""")
private const val SETTLED_RECAP = "Settled through the phase settlement tool."

internal fun settleScriptedEnvelope(
  outcome: AgentRunLaunchOutcome,
  prompt: String?,
  settlement: FeatureTaskPhaseSettlementService?,
): AgentRunLaunchOutcome {
  val facts = (outcome as? AgentRunLaunchFacts)?.takeIf { it.termination == AgentRunTermination.Exited(0) }
  val record = facts?.let { scriptedPhaseRecord(it.stdout) }
  val phaseId =
    (record?.get("phase_id") as? String)?.takeIf { FeatureTaskPhaseSettlementService.isSettleablePhase(it) }
  if (facts == null || record == null || phaseId == null) return outcome
  val target = prompt?.let(::settlementTargetFrom)
  val stdout =
    if (settlement != null && target != null) {
      settleRecord(settlement, target, phaseId, record)
      SETTLED_RECAP
    } else {
      scriptedValue(record)
    }
  return facts.copy(
    stdout = stdout,
    stdoutByteSize = stdout.encodeToByteArray().size.toLong(),
    stdoutSha256 = sha256HexUtf8(stdout),
  )
}

private data class ScriptedSettlementTarget(val workflowId: String, val attempt: Int)

private fun settlementTargetFrom(prompt: String): ScriptedSettlementTarget? {
  val match = SETTLEMENT_TARGET.find(prompt) ?: return null
  val attempt = match.groupValues[2].toIntOrNull() ?: return null
  return ScriptedSettlementTarget(match.groupValues[1], attempt)
}

private fun scriptedPhaseRecord(stdout: String): Map<String, Any?>? {
  val parsed = JsonCodec.parseObjectOrNull(stdout.trim()) ?: return null
  return JsonCodec
    .anyToStringAnyMap(JsonCodec.jsonElementToValue(parsed))
    ?.takeIf { "contract_version" in it && "phase_id" in it }
}

private fun producedOutputsOf(record: Map<String, Any?>): Map<String, Any?> =
  record["produced_outputs"]?.let(JsonCodec::anyToStringAnyMap).orEmpty()

private fun scriptedValue(record: Map<String, Any?>): String {
  val produced = producedOutputsOf(record)
  return (produced["value"] as? String)?.takeIf(String::isNotBlank)
    ?: produced.takeIf { it.isNotEmpty() }?.let(JsonCodec::mapToJsonString)
    ?: (record["summary"] as? String).orEmpty()
}

private fun settleRecord(
  settlement: FeatureTaskPhaseSettlementService,
  target: ScriptedSettlementTarget,
  phaseId: String,
  record: Map<String, Any?>,
) {
  val verdict = (record["verdict"] ?: producedOutputsOf(record)["verdict"]) as? String
  if (record["status"] == "blocked" || record["status"] == "failed") {
    settlement.block(
      FeatureTaskPhaseSettlementBlockRequest(
        workflowId = target.workflowId,
        phaseId = phaseId,
        attempt = target.attempt,
        reason = scriptedValue(record).ifBlank { "Scripted block." },
        failureDisposition = record["failure_disposition"] as? String ?: "process_failure",
        verdict = verdict,
      ),
    )
  } else {
    settlement.complete(
      FeatureTaskPhaseSettlementCompleteRequest(
        workflowId = target.workflowId,
        phaseId = phaseId,
        attempt = target.attempt,
        value = scriptedValue(record),
        prompt = producedOutputsOf(record)["prompt"] as? String,
        summary = record["summary"] as? String,
        verdict = verdict,
      ),
    )
  }
}
