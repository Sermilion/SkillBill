package skillbill.engine.featuretask.phase.core

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeCommitPushPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.checkpoint.isRuntimePrivatePath
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementEnvelope
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProducerOutputRead
import skillbill.engine.featuretask.model.phase.ProducerOutputQueryArgs
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitIdentityResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.nio.file.Path

internal sealed interface RepairPublicationAssociation {
  data object NotStarted : RepairPublicationAssociation

  data class Published(val commitSha: String) : RepairPublicationAssociation

  data object Unchanged : RepairPublicationAssociation

  data object Unavailable : RepairPublicationAssociation
}

internal data class MonitorRepairStepRoles(val monitor: String, val repair: String, val publication: String)

internal class MonitorRepairAccounting(
  private val steps: MonitorRepairStepRoles,
  private val gitOperations: WorkflowGitOperations,
  private val producerOutput: (ProducerOutputQueryArgs) -> FeatureTaskRuntimeProducerOutputRead,
  private val findEnvelope: (String, String, Int) -> FeatureTaskPhaseSettlementEnvelope?,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal data class RestoredHistory(
    val outputs: List<FeatureTaskRuntimePhaseOutput>,
    val failedRepairs: Map<Int, String>,
    val evidenceUnavailable: Boolean,
  )

  fun restore(
    request: FeatureTaskRuntimeRunFacts,
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): RestoredHistory {
    var monitorEvidenceUnavailable = false
    val retainedPhaseIds =
      setOf(
        steps.monitor,
        steps.publication,
        steps.repair,
      )
    val retained =
      ledger.filter {
        it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE && it.phaseId in retainedPhaseIds
      }.sortedBy { it.sequenceNumber }.mapNotNull { completion ->
        completedOutput(request.workflowId, completion, ledger, records[completion.phaseId]).also {
          if (it == null && completion.phaseId == steps.monitor) {
            monitorEvidenceUnavailable = true
          }
        }
      }.distinctBy { it.phaseId to it.iteration }
    val failedRepairs = mutableMapOf<Int, String>()
    retained.filter { output ->
      output.phaseId == steps.monitor &&
        output.normalizedOutput?.verdict == FeatureTaskRuntimeVerdict.CI_FAILED.wireValue
    }.forEach { output ->
      val association = publishedRepairCommitSha(request, output.iteration, records, ledger, retained)
      if (association is RepairPublicationAssociation.Published &&
        confirmsPublishedFailure(output, association.commitSha)
      ) {
        failedRepairs[output.iteration] = association.commitSha
      } else {
        monitorEvidenceUnavailable = true
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Could not restore monitor failure attempt ${output.iteration}: " +
            "published repair qualification is unavailable.",
        )
      }
    }
    return RestoredHistory(retained, failedRepairs, monitorEvidenceUnavailable)
  }

  private data class RepairAttempt(
    val start: FeatureTaskRuntimePhaseLedgerEntry,
    val completion: FeatureTaskRuntimePhaseLedgerEntry,
    val record: FeatureTaskRuntimePhaseRecord?,
  )

  private data class Publication(val sha: String)

  private data class PublishedChange(val sameTree: Boolean, val addedMergeParent: Boolean)

  fun publishedRepairCommitSha(
    request: FeatureTaskRuntimeRunFacts,
    monitorAttempt: Int,
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    ledgerEntries: List<FeatureTaskRuntimePhaseLedgerEntry>,
    outputs: List<FeatureTaskRuntimePhaseOutput>,
  ): RepairPublicationAssociation {
    val ledger = ledgerEntries.sortedBy { it.sequenceNumber }
    val monitorStart =
      ledger.lastOrNull {
        it.phaseId == steps.monitor &&
          it.attemptCount == monitorAttempt && it.action == FeatureTaskRuntimePhaseLedgerAction.START
      } ?: return unavailableRepairAssociation("monitor START is missing")
    val repairStart =
      ledger.lastOrNull {
        it.phaseId == steps.repair &&
          it.sequenceNumber < monitorStart.sequenceNumber && it.action == FeatureTaskRuntimePhaseLedgerAction.START
      } ?: return RepairPublicationAssociation.NotStarted
    val repair =
      completedRepair(request.workflowId, repairStart, monitorStart, records, ledger)
        ?: return RepairPublicationAssociation.Unavailable
    val publications = publications(request.workflowId, repair, monitorStart, ledger, outputs)
    return if (publications == null) {
      RepairPublicationAssociation.Unavailable
    } else {
      qualifyChange(
        request.repoRoot,
        repair,
        publications,
        outputs.singleOrNull {
          it.phaseId == monitorStart.phaseId && it.iteration == monitorAttempt
        },
      )
    }
  }

  private fun completedRepair(
    workflowId: String,
    start: FeatureTaskRuntimePhaseLedgerEntry,
    monitorStart: FeatureTaskRuntimePhaseLedgerEntry,
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): RepairAttempt? {
    val completion =
      ledger.firstOrNull {
        it.phaseId == start.phaseId && it.attemptCount == start.attemptCount &&
          it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE &&
          it.sequenceNumber in (start.sequenceNumber + 1) until monitorStart.sequenceNumber
      }
    if (completion == null) {
      unavailableRepairAssociation("started repair has no completion")
      return null
    }
    val current = records[start.phaseId]?.takeIf { it.attemptCount == start.attemptCount }
    val accepted = readRepairCompletion(workflowId, completion, current)
    val valid = recordMatches(current, completion) && isCompletedEnvelope(accepted, start.phaseId)
    if (!valid || !producerOutputMatches(workflowId, start, completion)) {
      unavailableRepairAssociation("accepted repair completion is unavailable or contradictory")
      return null
    }
    return RepairAttempt(start, completion, current)
  }

  private fun publications(
    workflowId: String,
    repair: RepairAttempt,
    monitorStart: FeatureTaskRuntimePhaseLedgerEntry,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    outputs: List<FeatureTaskRuntimePhaseOutput>,
  ): Pair<Publication, Publication>? {
    val commitPhase = steps.publication
    val completions =
      ledger.filter {
        it.phaseId == commitPhase && it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE
      }
    val baseline =
      readPublication(
        workflowId,
        completions.lastOrNull {
          it.sequenceNumber < repair.start.sequenceNumber
        },
        ledger,
        outputs,
      )
    val published =
      readPublication(
        workflowId,
        completions.firstOrNull {
          it.sequenceNumber in (repair.start.sequenceNumber + 1) until monitorStart.sequenceNumber
        },
        ledger,
        outputs,
      )
    return if (baseline == null || published == null) null else baseline to published
  }

  private fun readPublication(
    workflowId: String,
    completion: FeatureTaskRuntimePhaseLedgerEntry?,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    outputs: List<FeatureTaskRuntimePhaseOutput>,
  ): Publication? {
    val start = completion?.let { matchingStart(it, ledger) }
    val output =
      completion?.let {
        completedPushedCommitOutput(outputs, it.attemptCount)
          ?: completedOutput(workflowId, it, ledger, null)?.let { restored ->
            completedPushedCommitOutput(listOf(restored), it.attemptCount)
          }
      }
    val sha = output?.let(::commitPushSha)
    if (completion == null || start == null || sha == null) {
      unavailableRepairAssociation("pushed publication or its START is missing")
      return null
    }
    if (!producerOutputMatches(workflowId, start, completion, output)) return null
    return Publication(sha)
  }

  private fun qualifyChange(
    repoRoot: Path,
    repair: RepairAttempt,
    publications: Pair<Publication, Publication>,
    historicalFailure: FeatureTaskRuntimePhaseOutput?,
  ): RepairPublicationAssociation {
    val baseline = publications.first.sha
    val published = publications.second.sha
    val changedPaths =
      when (
        val changes = gitOperations.runtimePhaseChangedPathsBetweenCommits(repoRoot, baseline, published)
      ) {
        is WorkflowGitNameListResult.Listed -> changes.names.filterNot(::isRuntimePrivatePath).toSet()
        is WorkflowGitNameListResult.Failed -> return gitEvidenceUnavailable("published path diff failed")
      }
    val priorQualification = historicalFailure?.let { confirmsPublishedFailure(it, published) } == true
    if (repair.record == null && !priorQualification) {
      return unavailableRepairAssociation("repair source attribution is no longer retained")
    }
    val change = publishedChange(repoRoot, baseline, published) ?: return RepairPublicationAssociation.Unavailable
    val changedSource =
      changedPaths.any(attributedPaths(repair.record)::contains) ||
        (repair.record == null && priorQualification && changedPaths.isNotEmpty())
    return if (change.addedMergeParent || (changedSource && !change.sameTree)) {
      RepairPublicationAssociation.Published(published)
    } else {
      RepairPublicationAssociation.Unchanged
    }
  }

  private fun attributedPaths(record: FeatureTaskRuntimePhaseRecord?): Set<String> =
    record?.let {
      ((it.fileManifestAfter.toSet() - it.fileManifestBefore.toSet()) + it.fileManifestIntroduced)
        .filterNot(::isRuntimePrivatePath).toSet()
    }.orEmpty()

  private fun publishedChange(
    repoRoot: Path,
    baselineSha: String,
    publishedSha: String,
  ): PublishedChange? {
    val source = gitOperations.commitIdentity(repoRoot, publishedSha) as? WorkflowGitCommitIdentityResult.Found
    val baseline = gitOperations.commitIdentity(repoRoot, baselineSha) as? WorkflowGitCommitIdentityResult.Found
    if (source == null || baseline == null) {
      gitEvidenceUnavailable("published or baseline commit identity is unavailable")
      return null
    }
    if (!descendsFrom(repoRoot, baselineSha, publishedSha)) return null
    val merge = hasNewMergeParent(repoRoot, baselineSha, publishedSha) ?: return null
    return PublishedChange(source.identity.treeSha == baseline.identity.treeSha, merge)
  }

  private fun descendsFrom(
    repoRoot: Path,
    baselineSha: String,
    publishedSha: String,
  ): Boolean =
    when (val ancestry = gitOperations.isCommitAncestor(repoRoot, baselineSha, publishedSha)) {
      is WorkflowGitOperationResult.Ok ->
        if (ancestry.value.trim() == "true") {
          true
        } else {
          gitEvidenceUnavailable("published commit does not descend from baseline")
          false
        }
      is WorkflowGitOperationResult.Failed -> {
        gitEvidenceUnavailable("baseline ancestry query failed")
        false
      }
    }

  private fun hasNewMergeParent(
    repoRoot: Path,
    baselineSha: String,
    publishedSha: String,
  ): Boolean? {
    val pending = ArrayDeque<String>().apply { add(publishedSha) }
    val visited = mutableSetOf<String>()
    while (pending.isNotEmpty()) {
      val sha = pending.removeFirst()
      if (sha != baselineSha && visited.add(sha)) {
        val read =
          gitOperations.commitIdentity(repoRoot, sha) as? WorkflowGitCommitIdentityResult.Found
            ?: return producerAssociationUnavailableForGit("commit ancestry is unreadable")
        val parents = parentsAfterBaseline(repoRoot, read.identity.parentShas, baselineSha) ?: return null
        if (read.identity.parentShas.size > 1 && parents.isNotEmpty()) return true
        pending.addAll(parents)
      }
    }
    return false
  }

  private fun parentsAfterBaseline(
    repoRoot: Path,
    parents: List<String>,
    baselineSha: String,
  ): List<String>? {
    val result = mutableListOf<String>()
    for (parent in parents) {
      when (val ancestry = gitOperations.isCommitAncestor(repoRoot, parent, baselineSha)) {
        is WorkflowGitOperationResult.Ok -> if (ancestry.value.trim() != "true") result += parent
        is WorkflowGitOperationResult.Failed -> {
          producerAssociationUnavailableForGit("merge parent lookup failed")
          return null
        }
      }
    }
    return result
  }

  private fun matchingStart(
    completion: FeatureTaskRuntimePhaseLedgerEntry,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): FeatureTaskRuntimePhaseLedgerEntry? =
    ledger.lastOrNull {
      it.phaseId == completion.phaseId && it.attemptCount == completion.attemptCount &&
        it.sequenceNumber < completion.sequenceNumber && it.action == FeatureTaskRuntimePhaseLedgerAction.START
    }

  private fun recordMatches(
    record: FeatureTaskRuntimePhaseRecord?,
    completion: FeatureTaskRuntimePhaseLedgerEntry,
  ): Boolean =
    record?.let {
      it.phaseId == completion.phaseId && it.resolvedAgentId == completion.resolvedAgentId &&
        it.status == WorkflowStepStatus.COMPLETED
    } ?: true

  private fun isCompletedEnvelope(
    output: NormalizedFeatureTaskRuntimePhaseOutput?,
    phaseId: String,
  ): Boolean = output != null && output.phaseId == phaseId && output.status == WorkflowStepStatus.COMPLETED.wireValue

  private fun producerAssociationUnavailableForGit(reason: String): Boolean? {
    RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "Monitor repair accounting could not prove $reason.")
    return null
  }

  private fun gitEvidenceUnavailable(reason: String): RepairPublicationAssociation {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Monitor repair accounting could not establish Git evidence: $reason.",
    )
    return RepairPublicationAssociation.Unavailable
  }

  private fun unavailableRepairAssociation(reason: String): RepairPublicationAssociation {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Monitor repair accounting could not establish an association: $reason.",
    )
    return RepairPublicationAssociation.Unavailable
  }

  private fun producerOutputMatches(
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

  private fun commitPushSha(output: FeatureTaskRuntimePhaseOutput): String? =
    output.normalizedOutput
      ?.envelopeWireMap()
      ?.get(SharedPayloadKeys.PRODUCED_OUTPUTS)
      .let { it as? Map<*, *> }
      ?.get(FeatureTaskRuntimeCommitPushPayloadKeys.COMMIT_PUSH_RESULT)
      .let { it as? Map<*, *> }
      ?.get(DecompositionManifestPayloadKeys.COMMIT_SHA)
      ?.toString()
      ?.takeIf(String::isNotBlank)

  private fun completedPushedCommitOutput(
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

  private fun readRepairCompletion(
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

  private fun readEnvelope(
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

  fun completedOutput(
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

  fun confirmsPublishedFailure(
    output: FeatureTaskRuntimePhaseOutput,
    commitSha: String,
  ): Boolean =
    output.normalizedOutput?.let {
      it.phaseId == steps.monitor &&
        it.status == WorkflowStepStatus.COMPLETED.wireValue &&
        it.verdict == FeatureTaskRuntimeVerdict.CI_FAILED.wireValue &&
        auditProseValue(it.envelopeWireMap())
          ?.startsWith("CI confirmed a failed published repair commit '$commitSha'") == true
    } == true
}
