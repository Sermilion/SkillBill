package skillbill.engine.featuretask.incident.investigation

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.workflow.support.workflowFamilyFor
import skillbill.error.core.SkillBillRuntimeException
import skillbill.model.RuntimeVersion
import skillbill.ports.diagnostics.model.ExactOutputRead
import skillbill.ports.diagnostics.model.ExactOutputReadRequest
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.text.Utf8Text
import skillbill.text.sha256HexUtf8
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDegradation
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisEvidence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisEvidenceKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceReference
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceReferenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import java.security.MessageDigest
import java.util.HexFormat

public data class BlockIncidentCollectedEvidence(
  val items: List<BlockIncidentDiagnosisEvidence>,
  val degradations: Set<BlockIncidentDegradation>,
  val recordedAgentId: String?,
)

@Inject
public class BlockIncidentEvidenceCollector(private val runtimeVersion: RuntimeVersion) {
  public fun collect(
    unitOfWork: UnitOfWork,
    incident: BlockIncident,
  ): BlockIncidentCollectedEvidence {
    val occurrence = incident.occurrence
    val budget = EvidenceBudget()
    budget.add(
      BlockIncidentDiagnosisEvidenceKind.ORIGINAL_FAILURE,
      occurrence.occurrenceToken,
      originalFailureText(occurrence),
      originalFailureSummary(occurrence),
      fullSha256 = occurrence.originalReasonDigest.takeIf { occurrence.reasonTruncated },
    )
    if (occurrence.reasonTruncated) budget.degradations += BlockIncidentDegradation.EVIDENCE_TRUNCATED

    val workflow = readWorkflow(unitOfWork, occurrence)
    val snapshot =
      when (workflow) {
        WorkflowRead.NotApplicable -> null
        is WorkflowRead.Unavailable -> {
          budget.unavailable(BlockIncidentDiagnosisEvidenceKind.WORKFLOW_STATE, workflow.source, workflow.reason)
          null
        }
        is WorkflowRead.Found -> {
          budget.add(
            BlockIncidentDiagnosisEvidenceKind.WORKFLOW_STATE,
            workflow.workflowId,
            workflow.snapshotText,
            workflow.snapshotSummary,
          )
          workflow.snapshot
        }
      }
    val taskRuntimeApplies =
      workflow is WorkflowRead.Found && workflow.family == WorkflowFamily.TASK_RUNTIME ||
        workflow is WorkflowRead.Unavailable && occurrence.phaseId != null
    val ledger = if (taskRuntimeApplies) readLedger(snapshot, occurrence) else LedgerRead.NotApplicable
    collectLedger(budget, occurrence, ledger, taskRuntimeApplies)
    val outputAgents = collectOutputs(budget, unitOfWork, occurrence)
    if (taskRuntimeApplies) collectBriefing(budget, snapshot, occurrence)
    budget.add(
      BlockIncidentDiagnosisEvidenceKind.RUNTIME_VERSION,
      runtimeVersion.value,
      runtimeVersion.value,
      "Injected runtime version ${runtimeVersion.value}.",
    )
    val recordedAgentId = scopedAgent(occurrence, ledger, outputAgents)
    return BlockIncidentCollectedEvidence(budget.items, budget.degradations, recordedAgentId)
  }

  private fun readWorkflow(
    unitOfWork: UnitOfWork,
    occurrence: BlockIncidentOccurrence,
  ): WorkflowRead {
    val workflowId = occurrence.workflowId ?: return WorkflowRead.NotApplicable
    return try {
      val family =
        workflowFamilyFor(unitOfWork.workflowStates, workflowId)
          ?: return WorkflowRead.Unavailable("workflow $workflowId", "saved workflow row is absent")
      val snapshot =
        unitOfWork.workflowStates.get(family, workflowId)
          ?: return WorkflowRead.Unavailable("workflow $workflowId", "saved workflow state is absent")
      WorkflowRead.Found(
        workflowId,
        family,
        snapshot,
        workflowStateText(snapshot),
        workflowStateSummary(workflowId, snapshot),
      )
    } catch (failure: SkillBillRuntimeException) {
      WorkflowRead.Unavailable("workflow $workflowId", "saved workflow state is unreadable: ${failure.code}")
    }
  }

  private fun readLedger(
    snapshot: WorkflowStateSnapshot?,
    occurrence: BlockIncidentOccurrence,
  ): LedgerRead {
    val phaseId = occurrence.phaseId ?: return LedgerRead.NotApplicable
    val attempt = occurrence.attempt
    val source = "phase ledger $phaseId attempt $attempt"
    val label = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.label()
    return when {
      attempt == null -> LedgerRead.Unavailable(source, "incident attempt is missing")
      snapshot == null -> LedgerRead.Unavailable(source, "workflow state is unavailable")
      label !in snapshot.artifacts -> LedgerRead.Unavailable(source, "saved phase ledger artifact is absent")
      else ->
        try {
          LedgerRead.Found(decodePhaseLedger(snapshot.artifacts).filter { it.phaseId == phaseId })
        } catch (failure: SkillBillRuntimeException) {
          LedgerRead.Unavailable(source, "saved phase ledger is unreadable: ${failure.code}")
        }
    }
  }

  private fun collectLedger(
    budget: EvidenceBudget,
    occurrence: BlockIncidentOccurrence,
    ledger: LedgerRead,
    taskRuntimeApplies: Boolean,
  ) {
    if (!taskRuntimeApplies) return
    val phaseId = occurrence.phaseId ?: return
    val attempt = occurrence.attempt
    when (ledger) {
      LedgerRead.NotApplicable -> Unit
      is LedgerRead.Unavailable -> {
        budget.unavailable(BlockIncidentDiagnosisEvidenceKind.PHASE_LEDGER, ledger.source, ledger.reason)
        if (attempt != null && attempt > 1) {
          budget.unavailable(
            BlockIncidentDiagnosisEvidenceKind.ATTEMPT_HISTORY,
            "attempt history $phaseId before $attempt",
            "phase ledger is unavailable: ${ledger.reason}",
          )
        }
      }
      is LedgerRead.Found -> {
        val current = ledger.entries.filter { it.attemptCount == attempt }
        if (current.isEmpty()) {
          budget.unavailable(
            BlockIncidentDiagnosisEvidenceKind.PHASE_LEDGER,
            "phase ledger $phaseId attempt $attempt",
            "no retained entries match the incident phase and attempt",
          )
        } else {
          budget.add(
            BlockIncidentDiagnosisEvidenceKind.PHASE_LEDGER,
            "$phaseId:attempt:$attempt",
            current.joinToString("\n") { it.text(includeReason = false) },
            ledgerSummary("Incident phase $phaseId attempt $attempt", current),
          )
        }
        if (attempt != null && attempt > 1) {
          val history = ledger.entries.filter { it.attemptCount < attempt }
          budget.add(
            BlockIncidentDiagnosisEvidenceKind.ATTEMPT_HISTORY,
            "$phaseId:before:$attempt",
            history.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text(includeReason = true) }
              ?: "No earlier retained attempts for phase $phaseId before attempt $attempt.",
            attemptHistorySummary(phaseId, attempt, history),
          )
        }
      }
    }
  }

  private fun collectOutputs(
    budget: EvidenceBudget,
    unitOfWork: UnitOfWork,
    occurrence: BlockIncidentOccurrence,
  ): List<String> {
    val agents = mutableListOf<String>()
    occurrence.evidenceReferences.forEach { reference ->
      val kind =
        when (reference.kind) {
          BlockIncidentEvidenceKind.REJECTED_OUTPUT_DIAGNOSTIC -> BlockIncidentDiagnosisEvidenceKind.REJECTED_OUTPUT
          BlockIncidentEvidenceKind.PRODUCER_OUTPUT -> BlockIncidentDiagnosisEvidenceKind.PRODUCER_OUTPUT
          BlockIncidentEvidenceKind.OTHER -> return@forEach
        }
      if (reference.availability == BlockIncidentEvidenceReferenceAvailability.UNAVAILABLE) {
        budget.unavailable(
          kind,
          outputSource(occurrence, reference),
          "reference was recorded unavailable",
          outputReference(reference),
        )
        return@forEach
      }
      val request = exactRequest(occurrence, reference)
      val result =
        try {
          unitOfWork.rejectedOutputDiagnostics.readProducerOutputExact(request)
        } catch (failure: SkillBillRuntimeException) {
          ExactOutputRead.Unavailable("retained row is unreadable: ${failure.code}")
        }
      when (result) {
        is ExactOutputRead.Unavailable ->
          budget.unavailable(kind, outputSource(occurrence, reference), result.reason, outputReference(reference))
        is ExactOutputRead.Found -> {
          val content = result.payload.decodeToString()
          budget.add(
            kind,
            outputReference(reference),
            content,
            outputSummary(occurrence, reference, result.payload.size),
            fullSha256 = sha256(result.payload),
          )
          if (result.sourceIdentity.isNotBlank()) agents += result.sourceIdentity
        }
      }
    }
    return agents
  }

  private fun exactRequest(
    occurrence: BlockIncidentOccurrence,
    reference: BlockIncidentEvidenceReference,
  ): ExactOutputReadRequest =
    when (reference.kind) {
      BlockIncidentEvidenceKind.PRODUCER_OUTPUT ->
        ExactOutputReadRequest.Producer(
          workflowId = occurrence.workflowId,
          phaseId = occurrence.phaseId,
          attempt = occurrence.attempt,
          agentId = reference.sourceIdentity,
          generation = reference.outputGeneration,
          repairTurn = reference.repairTurn,
          expectedDigest = reference.digest,
          expectedByteSize = reference.byteSize,
          retainedIdentity = reference.retainedReferenceId,
        )
      BlockIncidentEvidenceKind.REJECTED_OUTPUT_DIAGNOSTIC ->
        ExactOutputReadRequest.Rejected(
          diagnosticIdentity = reference.retainedReferenceId,
          workflowId = occurrence.workflowId,
          phaseId = occurrence.phaseId,
          attempt = occurrence.attempt,
          sourceIdentity = reference.sourceIdentity,
          repairTurn = reference.repairTurn,
          expectedDigest = reference.digest,
          expectedByteSize = reference.byteSize,
        )
      BlockIncidentEvidenceKind.OTHER -> error("Other evidence has no retained-output request.")
    }

  private fun outputReference(reference: BlockIncidentEvidenceReference): String =
    sha256HexUtf8(
      listOf(
        reference.kind.wireValue,
        reference.retainedReferenceId,
        reference.reference,
        reference.sourceIdentity,
        reference.outputGeneration,
        reference.repairTurn,
        reference.digest,
      ).joinToString("|") { it?.toString().orEmpty() },
    )

  private fun outputSource(
    occurrence: BlockIncidentOccurrence,
    reference: BlockIncidentEvidenceReference,
  ): String =
    "${reference.kind.wireValue} workflow=${occurrence.workflowId ?: NONE_LABEL} " +
      "phase=${occurrence.phaseId ?: NONE_LABEL} attempt=${occurrence.attempt ?: NONE_LABEL} " +
      "retained=${reference.retainedReferenceId ?: reference.reference ?: NONE_LABEL} " +
      "agent=${reference.sourceIdentity ?: NONE_LABEL} generation=${reference.outputGeneration ?: NONE_LABEL} " +
      "repair_turn=${reference.repairTurn ?: NONE_LABEL} digest=${reference.digest ?: NONE_LABEL}"

  private fun collectBriefing(
    budget: EvidenceBudget,
    snapshot: WorkflowStateSnapshot?,
    occurrence: BlockIncidentOccurrence,
  ) {
    val phaseId = occurrence.phaseId ?: return
    when (val retained = readBriefing(snapshot, phaseId)) {
      is BriefingRead.Unavailable ->
        budget.unavailable(
          BlockIncidentDiagnosisEvidenceKind.PHASE_BRIEFING,
          "phase briefing $phaseId",
          retained.reason,
        )
      is BriefingRead.Found -> {
        val briefing = retained.briefing
        budget.add(
          BlockIncidentDiagnosisEvidenceKind.PHASE_BRIEFING,
          phaseId,
          JsonCodec.valueToJsonString(briefing.asBriefingArtifactEntry()),
          "Validated briefing for phase $phaseId, feature size ${briefing.featureSize}, " +
            "${briefing.acceptanceCriteria.size} criteria, ${briefing.mandatesAndOverrides.size} mandates, " +
            "${briefing.derivedContextKeys.size} derived context entries.",
        )
      }
    }
  }

  private fun readBriefing(
    snapshot: WorkflowStateSnapshot?,
    phaseId: String,
  ): BriefingRead {
    if (snapshot == null) return BriefingRead.Unavailable("workflow state is unavailable")
    val label = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS.label()
    if (!snapshot.artifacts.containsKey(label)) return BriefingRead.Unavailable("saved briefing artifact is absent")
    return try {
      val raw = JsonCodec.anyToStringAnyMap(snapshot.artifacts[label])
      when {
        raw == null -> BriefingRead.Unavailable("saved briefing map is malformed")
        !raw.containsKey(
          phaseId,
        ) -> BriefingRead.Unavailable("no retained briefing entry exists for the incident phase")
        else -> decodeBriefing(raw[phaseId], phaseId)
      }
    } catch (failure: SkillBillRuntimeException) {
      BriefingRead.Unavailable("saved briefing is unreadable: ${failure.code}")
    }
  }

  private fun decodeBriefing(
    raw: Any?,
    phaseId: String,
  ): BriefingRead {
    val map = JsonCodec.anyToStringAnyMap(raw) ?: return BriefingRead.Unavailable("saved briefing entry is malformed")
    val briefing = FeatureTaskRuntimePhaseLaunchBriefing.fromBriefingArtifactWire(map)
    return if (briefing.phaseId == phaseId) {
      BriefingRead.Found(briefing)
    } else {
      BriefingRead.Unavailable("saved briefing entry belongs to phase ${briefing.phaseId}")
    }
  }

  private sealed interface BriefingRead {
    data class Unavailable(val reason: String) : BriefingRead

    data class Found(val briefing: FeatureTaskRuntimePhaseLaunchBriefing) : BriefingRead
  }

  private fun scopedAgent(
    occurrence: BlockIncidentOccurrence,
    ledger: LedgerRead,
    outputAgents: List<String>,
  ): String? {
    val phaseId = occurrence.phaseId ?: return null
    val attempt = occurrence.attempt ?: return null
    val entries = (ledger as? LedgerRead.Found)?.entries.orEmpty()
    val agent =
      entries.asSequence()
        .filter { it.phaseId == phaseId && it.attemptCount == attempt }
        .sortedBy(FeatureTaskRuntimePhaseLedgerEntry::sequenceNumber)
        .mapNotNull { it.resolvedAgentId?.takeIf(String::isNotBlank) }
        .lastOrNull()
    if (agent != null) return agent
    return outputAgents.distinct().singleOrNull()
  }

  private fun originalFailureText(occurrence: BlockIncidentOccurrence): String =
    "origin: ${occurrence.origin.wireValue}\n" +
      "failure code: ${occurrence.failureCode ?: NONE_LABEL}\n" +
      "disposition: ${occurrence.failureDisposition ?: NONE_LABEL}\n" +
      "reason: ${occurrence.reason}"

  private fun originalFailureSummary(occurrence: BlockIncidentOccurrence): String =
    "Saved origin ${occurrence.origin.wireValue}, failure code ${occurrence.failureCode ?: NONE_LABEL}, " +
      "disposition ${occurrence.failureDisposition ?: NONE_LABEL}; original reason " +
      if (occurrence.reasonTruncated) "was truncated." else "is retained but omitted from this summary."

  private fun workflowStateText(snapshot: WorkflowStateSnapshot): String =
    "workflow: ${snapshot.workflowName}\n" +
      "status: ${snapshot.workflowStatus.wireValue}\n" +
      "current step: ${snapshot.currentStepId}\n" +
      snapshot.steps.joinToString("\n") { "step ${it.stepId}: ${it.status.wireValue} attempt ${it.attemptCount}" }

  private fun workflowStateSummary(
    workflowId: String,
    snapshot: WorkflowStateSnapshot,
  ): String {
    val steps = snapshot.steps.take(SUMMARY_STEP_LIMIT)
    val omitted = snapshot.steps.size - steps.size
    return "Saved workflow $workflowId (${snapshot.workflowName}) is ${snapshot.workflowStatus.wireValue}; " +
      "current step ${snapshot.currentStepId}. Steps: " +
      steps.joinToString(", ") { "${it.stepId}=${it.status.wireValue}/attempt-${it.attemptCount}" } +
      if (omitted > 0) "; $omitted additional step records omitted." else "."
  }

  private fun ledgerSummary(
    label: String,
    entries: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): String {
    val selected = entries.take(SUMMARY_ENTRY_LIMIT)
    val sequenceRange =
      entries.minOfOrNull { it.sequenceNumber }?.let { first ->
        val last = entries.maxOf { it.sequenceNumber }
        "sequence range $first-$last"
      } ?: "no sequence range"
    val agents = entries.mapNotNull { it.resolvedAgentId?.takeIf(String::isNotBlank) }.distinct()
    val actions = selected.joinToString(", ") { it.action.wireValue }
    val omitted = entries.size - selected.size
    val omittedAgents = (agents.size - SUMMARY_AGENT_LIMIT).coerceAtLeast(0)
    return "$label has ${entries.size} retained entries ($sequenceRange); actions: $actions; " +
      "recorded agents: ${agents.take(SUMMARY_AGENT_LIMIT).joinToString().ifEmpty { NONE_LABEL }}" +
      if (omitted > 0 || omittedAgents > 0) {
        listOfNotNull(
          "$omitted entry details omitted.".takeIf { omitted > 0 },
          "$omittedAgents additional agent IDs omitted.".takeIf { omittedAgents > 0 },
        ).joinToString(prefix = "; ", separator = "; ")
      } else {
        "."
      }
  }

  private fun attemptHistorySummary(
    phaseId: String,
    attempt: Int,
    entries: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): String {
    val selected = entries.take(SUMMARY_ENTRY_LIMIT)
    val attemptCounts = selected.map { it.attemptCount }.distinct().sorted().joinToString().ifEmpty { NONE_LABEL }
    val actions = selected.map { it.action.wireValue }.distinct().joinToString().ifEmpty { NONE_LABEL }
    val omitted = entries.size - selected.size
    return "Earlier retained attempts for phase $phaseId before attempt $attempt: ${entries.size} entries, " +
      "attempt counts $attemptCounts, actions $actions" +
      if (omitted > 0) "; $omitted additional entries omitted." else "."
  }

  private fun outputSummary(
    occurrence: BlockIncidentOccurrence,
    reference: BlockIncidentEvidenceReference,
    payloadBytes: Int,
  ): String =
    "${reference.kind.wireValue} is untrusted retained output for workflow " +
      "${occurrence.workflowId ?: NONE_LABEL}, phase ${occurrence.phaseId ?: NONE_LABEL}, " +
      "attempt ${occurrence.attempt ?: NONE_LABEL}; source ${reference.sourceIdentity ?: NONE_LABEL}, " +
      "generation ${reference.outputGeneration ?: NONE_LABEL}, repair turn ${reference.repairTurn ?: NONE_LABEL}, " +
      "retained reference ${reference.retainedReferenceId ?: reference.reference ?: NONE_LABEL}, " +
      "digest ${reference.digest ?: NONE_LABEL}; exact read returned $payloadBytes payload bytes."

  public fun FeatureTaskRuntimePhaseLedgerEntry.text(includeReason: Boolean): String =
    "#$sequenceNumber ${action.wireValue} phase $phaseId attempt $attemptCount at $timestamp" +
      " agent ${resolvedAgentId ?: NONE_LABEL}" +
      if (includeReason && blockedReason != null) " blocked: $blockedReason" else ""

  private sealed interface WorkflowRead {
    data object NotApplicable : WorkflowRead

    data class Unavailable(val source: String, val reason: String) : WorkflowRead

    data class Found(
      val workflowId: String,
      val family: WorkflowFamily,
      val snapshot: WorkflowStateSnapshot,
      val snapshotText: String,
      val snapshotSummary: String,
    ) : WorkflowRead
  }

  private sealed interface LedgerRead {
    data object NotApplicable : LedgerRead

    data class Unavailable(val source: String, val reason: String) : LedgerRead

    data class Found(val entries: List<FeatureTaskRuntimePhaseLedgerEntry>) : LedgerRead
  }

  private class EvidenceBudget {
    val items = mutableListOf<BlockIncidentDiagnosisEvidence>()
    val degradations = mutableSetOf<BlockIncidentDegradation>()
    private var totalBytes = 0
    private val references = mutableSetOf<String>()

    fun add(
      kind: BlockIncidentDiagnosisEvidenceKind,
      id: String,
      content: String?,
      summary: String,
      fullSha256: String? = null,
    ) {
      if (content == null) {
        unavailable(kind, id, "saved content is absent")
        return
      }
      val reference = evidenceReference(kind, id)
      if (reference in references) return
      if (!admit(kind)) return
      val bounded = Utf8Text.truncateToUtf8Bytes(content, minOf(MAX_ITEM_BYTES, MAX_TOTAL_BYTES - totalBytes))
      if (bounded.length < content.length) degradations += BlockIncidentDegradation.EVIDENCE_TRUNCATED
      val size = Utf8Text.utf8Size(bounded)
      val summarySuffix = " Content budget retained $size of ${Utf8Text.utf8Size(content)} UTF-8 bytes."
      val boundedSummary = summary.take((SUMMARY_MAX - summarySuffix.length).coerceAtLeast(0)) + summarySuffix
      totalBytes += size
      references += reference
      items +=
        BlockIncidentDiagnosisEvidence(
          kind,
          reference,
          boundedSummary,
          fullSha256 ?: sha256HexUtf8(content),
          kind in TRUSTED_EVIDENCE_KINDS,
        )
    }

    fun unavailable(
      kind: BlockIncidentDiagnosisEvidenceKind,
      source: String,
      reason: String,
      id: String = source,
    ) {
      degradations += BlockIncidentDegradation.EVIDENCE_UNAVAILABLE
      val reference = evidenceReference(kind, id)
      if (reference in references) return
      if (!admit(kind)) return
      references += reference
      items +=
        BlockIncidentDiagnosisEvidence(
          kind,
          reference,
          "${kind.wireValue} source $source is unavailable: $reason".take(SUMMARY_MAX),
          EMPTY_SHA256,
          kind in TRUSTED_EVIDENCE_KINDS,
        )
    }

    private fun admit(kind: BlockIncidentDiagnosisEvidenceKind): Boolean {
      val limit = if (kind == BlockIncidentDiagnosisEvidenceKind.RUNTIME_VERSION) MAX_ITEMS else MAX_ITEMS - 1
      if (items.size >= limit) {
        degradations += BlockIncidentDegradation.EVIDENCE_TRUNCATED
        return false
      }
      return true
    }

    private fun evidenceReference(
      kind: BlockIncidentDiagnosisEvidenceKind,
      id: String,
    ): String = "${kind.wireValue}:$id".take(BlockIncidentDiagnosisRecord.EVIDENCE_REFERENCE_MAX)
  }

  private companion object {
    val TRUSTED_EVIDENCE_KINDS =
      setOf(
        BlockIncidentDiagnosisEvidenceKind.WORKFLOW_STATE,
        BlockIncidentDiagnosisEvidenceKind.PHASE_LEDGER,
        BlockIncidentDiagnosisEvidenceKind.RUNTIME_VERSION,
      )
    const val MAX_ITEMS = BlockIncidentDiagnosisRecord.EVIDENCE_MAX
    const val MAX_ITEM_BYTES = 8 * 1024
    const val MAX_TOTAL_BYTES = 64 * 1024
    const val SUMMARY_MAX = BlockIncidentDiagnosisRecord.EVIDENCE_SUMMARY_MAX
    const val SUMMARY_STEP_LIMIT = 8
    const val SUMMARY_ENTRY_LIMIT = 8
    const val SUMMARY_AGENT_LIMIT = 4
    const val NONE_LABEL = "none"
    const val EMPTY_SHA256 = BlockIncidentDiagnosisRecord.EMPTY_CONTENT_SHA256

    fun sha256(payload: ByteArray): String =
      HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))
  }
}
