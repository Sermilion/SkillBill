package skillbill.engine.featuretask.phase.core

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeCommitPushPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementEnvelope
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProducerOutputRead
import skillbill.engine.featuretask.model.phase.ProducerOutputQueryArgs
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

public class MonitorRepairProducerEvidence(
  public val steps: MonitorRepairStepRoles,
  public val producerOutput: (ProducerOutputQueryArgs) -> FeatureTaskRuntimeProducerOutputRead,
  public val findEnvelope: (String, String, Int) -> FeatureTaskPhaseSettlementEnvelope?,
  public val diagnostics: RuntimeDiagnostics,
) {
  internal fun matchingStart(
    completion: FeatureTaskRuntimePhaseLedgerEntry,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): FeatureTaskRuntimePhaseLedgerEntry? =
    ledger.lastOrNull {
      it.phaseId == completion.phaseId && it.attemptCount == completion.attemptCount &&
        it.sequenceNumber < completion.sequenceNumber && it.action == FeatureTaskRuntimePhaseLedgerAction.START
    }

  internal fun recordMatches(
    record: FeatureTaskRuntimePhaseRecord?,
    completion: FeatureTaskRuntimePhaseLedgerEntry,
  ): Boolean =
    record?.let {
      it.phaseId == completion.phaseId && it.resolvedAgentId == completion.resolvedAgentId &&
        it.status == WorkflowStepStatus.COMPLETED
    } ?: true

  internal fun isCompletedEnvelope(
    output: NormalizedFeatureTaskRuntimePhaseOutput?,
    phaseId: String,
  ): Boolean = output != null && output.phaseId == phaseId && output.status == WorkflowStepStatus.COMPLETED.wireValue

  internal fun producerOutputMatches(
    workflowId: String,
    start: FeatureTaskRuntimePhaseLedgerEntry,
    completion: FeatureTaskRuntimePhaseLedgerEntry,
    expectedOutput: FeatureTaskRuntimePhaseOutput? = null,
  ): Boolean {
    val agentId =
      completion.resolvedAgentId
        ?: return producerAssociationUnavailable(completion, "missing resolved agent")
    val read =
      producerOutput(
        ProducerOutputQueryArgs(
          workflowId = workflowId,
          phaseId = completion.phaseId,
          attempt = completion.attemptCount,
          agentId = agentId,
          generation = 0,
        ),
      )
    val evidence =
      (read as? FeatureTaskRuntimeProducerOutputRead.Found)?.evidence
        ?: return producerAssociationUnavailable(completion, "producer output is absent or unreadable")
    return when (
      val association =
        associateMonitorProducer(
          ProducerOutputQueryArgs(workflowId, completion.phaseId, completion.attemptCount, agentId, 0),
          start,
          completion,
          evidence,
        )
    ) {
      FeatureTaskMonitorProducerAssociation.Matched -> {
        if (expectedOutput == null) return true
        val normalized = readEnvelope(completion, evidence.payload?.decodeToString())
        if (normalized == null || normalized.canonicalJson != expectedOutput.normalizedOutput?.canonicalJson) {
          producerAssociationUnavailable(completion, "retained producer output contradicts the selected output")
        } else {
          true
        }
      }
      is FeatureTaskMonitorProducerAssociation.Unavailable ->
        producerAssociationUnavailable(completion, association.reason)
    }
  }

  private fun producerAssociationUnavailable(
    completion: FeatureTaskRuntimePhaseLedgerEntry,
    reason: String,
  ): Boolean {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Monitor repair accounting could not associate ${completion.phaseId} attempt " +
        "${completion.attemptCount}: $reason.",
    )
    return false
  }

  internal fun commitPushSha(output: FeatureTaskRuntimePhaseOutput): String? =
    output.normalizedOutput
      ?.envelopeWireMap()
      ?.get(SharedPayloadKeys.PRODUCED_OUTPUTS)
      .let { it as? Map<*, *> }
      ?.get(FeatureTaskRuntimeCommitPushPayloadKeys.COMMIT_PUSH_RESULT)
      .let { it as? Map<*, *> }
      ?.get(DecompositionManifestPayloadKeys.COMMIT_SHA)
      ?.toString()
      ?.takeIf(String::isNotBlank)

  internal fun completedPushedCommitOutput(
    outputs: List<FeatureTaskRuntimePhaseOutput>,
    attempt: Int,
  ): FeatureTaskRuntimePhaseOutput? {
    val output =
      outputs
        .singleOrNull {
          it.phaseId == steps.publication && it.iteration == attempt
        }
        ?: return null
    val payload = output.normalizedOutput?.envelopeWireMap()?.get(SharedPayloadKeys.PRODUCED_OUTPUTS) as? Map<*, *>
    val result = payload?.get(FeatureTaskRuntimeCommitPushPayloadKeys.COMMIT_PUSH_RESULT) as? Map<*, *>
    return output.takeIf {
      it.normalizedOutput?.phaseId == steps.publication &&
        it.normalizedOutput?.status == WorkflowStepStatus.COMPLETED.wireValue &&
        result?.get(FeatureTaskRuntimeCommitPushPayloadKeys.PUSHED) == true
    }
  }

  internal fun readRepairCompletion(
    workflowId: String,
    completion: FeatureTaskRuntimePhaseLedgerEntry,
    record: FeatureTaskRuntimePhaseRecord?,
  ): NormalizedFeatureTaskRuntimePhaseOutput? {
    val text =
      if (record != null) {
        record.outputArtifact
      } else {
        findEnvelope(workflowId, completion.phaseId, completion.attemptCount)?.envelope?.let(JsonCodec::mapToJsonString)
      }
    return readEnvelope(completion, text)
  }

  public fun readEnvelope(
    completion: FeatureTaskRuntimePhaseLedgerEntry,
    text: String?,
  ): NormalizedFeatureTaskRuntimePhaseOutput? =
    text?.let {
      try {
        NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(it, completion.phaseId)
      } catch (error: SkillBillRuntimeException) {
        producerAssociationUnavailable(completion, "accepted output is unreadable: ${error.message}")
        null
      }
    }

  public fun completedOutput(
    workflowId: String,
    completion: FeatureTaskRuntimePhaseLedgerEntry,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    currentRecord: FeatureTaskRuntimePhaseRecord?,
  ): FeatureTaskRuntimePhaseOutput? {
    val start = matchingStart(completion, ledger)
    if (start == null) {
      producerAssociationUnavailable(completion, "matching START is missing")
      return null
    }
    val record = currentRecord?.takeIf { it.attemptCount == completion.attemptCount }
    if (!recordMatches(record, completion) || !producerOutputMatches(workflowId, start, completion)) {
      producerAssociationUnavailable(completion, "phase record or producer contradicts the selected completion")
      return null
    }
    val output = readCompletedEnvelope(workflowId, completion, record) ?: return null
    return FeatureTaskRuntimePhaseOutput(completion.phaseId, completion.attemptCount, output.canonicalJson, output)
  }

  private fun readCompletedEnvelope(
    workflowId: String,
    completion: FeatureTaskRuntimePhaseLedgerEntry,
    record: FeatureTaskRuntimePhaseRecord?,
  ): NormalizedFeatureTaskRuntimePhaseOutput? {
    val output =
      if (completion.phaseId == steps.repair) {
        readRepairCompletion(workflowId, completion, record)
      } else {
        val agent = completion.resolvedAgentId ?: return null
        val read =
          producerOutput(
            ProducerOutputQueryArgs(workflowId, completion.phaseId, completion.attemptCount, agent, 0),
          )
        readEnvelope(
          completion,
          (read as? FeatureTaskRuntimeProducerOutputRead.Found)?.evidence?.payload?.decodeToString(),
        )
      }
    if (!isCompletedEnvelope(output, completion.phaseId)) {
      producerAssociationUnavailable(completion, "accepted completed output is unavailable")
      return null
    }
    if (record?.outputArtifact != null &&
      readEnvelope(completion, record.outputArtifact)?.canonicalJson != output?.canonicalJson
    ) {
      producerAssociationUnavailable(completion, "phase record output contradicts the retained output")
      return null
    }
    return output
  }
}
