package skillbill.engine.featuretask.phase.core

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.checkpoint.isRuntimePrivatePath
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.execution.RecordedMonitorExecutionSemantics
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementEnvelope
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProducerOutputRead
import skillbill.engine.featuretask.model.phase.ProducerOutputQueryArgs
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitIdentityResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import java.nio.file.Path

internal sealed interface RepairPublicationAssociation {
  data object NotStarted : RepairPublicationAssociation

  data class Published(val commitSha: String) : RepairPublicationAssociation

  data object Unchanged : RepairPublicationAssociation

  data object Unavailable : RepairPublicationAssociation
}

internal class MonitorRepairAccounting(
  private val steps: MonitorRepairStepRoles,
  private val gitOperations: WorkflowGitOperations,
  producerOutput: (ProducerOutputQueryArgs) -> FeatureTaskRuntimeProducerOutputRead,
  findEnvelope: (String, String, Int) -> FeatureTaskPhaseSettlementEnvelope?,
  private val diagnostics: RuntimeDiagnostics,
) {
  private val evidenceReader = MonitorRepairProducerEvidence(steps, producerOutput, findEnvelope, diagnostics)

  internal data class RestoredHistory(
    val outputs: List<FeatureTaskRuntimePhaseOutput>,
    val failedRepairs: Map<Int, String>,
  )

  fun restore(
    request: FeatureTaskRuntimeRunFacts,
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    recordedSemantics: RecordedMonitorExecutionSemantics?,
    executionPlan: ResolvedPhaseExecutionPlan,
  ): RestoredHistory {
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
        evidenceReader.completedOutput(request.workflowId, completion, ledger, records[completion.phaseId])
      }.distinctBy { it.phaseId to it.iteration }
    val failedRepairs = mutableMapOf<Int, String>()
    retained.filter { output ->
      output.phaseId == steps.monitor &&
        output.normalizedOutput?.verdict == FeatureTaskRuntimeVerdict.CI_FAILED.wireValue
    }.forEach { output ->
      if (!steps.hasCorrectedProducerSemantics(recordedSemantics, executionPlan)) {
        unqualifiedMonitorFailure(output.iteration)
      } else {
        when (
          val association =
            historicalFailureCommitSha(request, output.iteration, records, ledger, retained)
        ) {
          is RepairPublicationAssociation.Published -> failedRepairs[output.iteration] = association.commitSha
          RepairPublicationAssociation.NotStarted -> unqualifiedMonitorFailure(output.iteration)
          RepairPublicationAssociation.Unavailable, RepairPublicationAssociation.Unchanged ->
            unqualifiedMonitorFailure(output.iteration)
        }
      }
    }
    return RestoredHistory(retained, failedRepairs)
  }

  fun publishedHeadBeforeMonitor(
    request: FeatureTaskRuntimeRunFacts,
    monitorAttempt: Int,
    ledgerEntries: List<FeatureTaskRuntimePhaseLedgerEntry>,
    outputs: List<FeatureTaskRuntimePhaseOutput>,
  ): String? {
    val ledger = ledgerEntries.sortedBy { it.sequenceNumber }
    val monitorStart =
      ledger.lastOrNull {
        it.phaseId == steps.monitor &&
          it.attemptCount == monitorAttempt && it.action == FeatureTaskRuntimePhaseLedgerAction.START
      } ?: return unavailablePublicationHead("monitor START is missing")
    val publication =
      ledger.lastOrNull {
        it.phaseId == steps.publication &&
          it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE &&
          it.sequenceNumber < monitorStart.sequenceNumber
      } ?: return unavailablePublicationHead("completed publication is missing")
    val qualified = readPublication(request.workflowId, publication, ledger, outputs)
    return qualified?.sha ?: unavailablePublicationHead("completed pushed publication is unavailable")
  }

  private fun unavailablePublicationHead(reason: String): String? {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Monitor repair accounting could not identify the published head: $reason.",
    )
    return null
  }

  private fun unqualifiedMonitorFailure(attempt: Int) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Monitor failure attempt $attempt has no qualified published-repair failure evidence.",
    )
  }

  private data class RepairAttempt(
    val start: FeatureTaskRuntimePhaseLedgerEntry,
    val completion: FeatureTaskRuntimePhaseLedgerEntry,
    val record: FeatureTaskRuntimePhaseRecord?,
  )

  private data class Publication(val sha: String)

  private data class PublishedChange(val sameTree: Boolean, val addedMergeParent: Boolean)

  private data class RepairPublicationEvidence(
    val repair: RepairAttempt,
    val publications: Pair<Publication, Publication>,
  )

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
      } ?: return if (hasUnmatchedRepairCompletionBefore(monitorStart, ledger)) {
        RepairPublicationAssociation.Unavailable
      } else {
        RepairPublicationAssociation.NotStarted
      }
    val evidence =
      repairPublicationEvidence(request.workflowId, RepairWindow(repairStart, monitorStart), records, ledger, outputs)
        ?: return RepairPublicationAssociation.Unavailable
    return qualifyChange(request.repoRoot, evidence.repair, evidence.publications)
  }

  private fun historicalFailureCommitSha(
    request: FeatureTaskRuntimeRunFacts,
    monitorAttempt: Int,
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    ledgerEntries: List<FeatureTaskRuntimePhaseLedgerEntry>,
    outputs: List<FeatureTaskRuntimePhaseOutput>,
  ): RepairPublicationAssociation {
    val ledger = ledgerEntries.sortedBy { it.sequenceNumber }
    val monitorStart =
      ledger.lastOrNull {
        it.phaseId == steps.monitor && it.attemptCount == monitorAttempt &&
          it.action == FeatureTaskRuntimePhaseLedgerAction.START
      } ?: return unavailableRepairAssociation("monitor START is missing")
    val repairStart =
      ledger.lastOrNull {
        it.phaseId == steps.repair && it.sequenceNumber < monitorStart.sequenceNumber &&
          it.action == FeatureTaskRuntimePhaseLedgerAction.START
      } ?: return if (hasUnmatchedRepairCompletionBefore(monitorStart, ledger)) {
        RepairPublicationAssociation.Unavailable
      } else {
        RepairPublicationAssociation.NotStarted
      }
    val evidence =
      repairPublicationEvidence(request.workflowId, RepairWindow(repairStart, monitorStart), records, ledger, outputs)
        ?: return RepairPublicationAssociation.Unavailable
    return publishedChange(request.repoRoot, evidence.publications.first.sha, evidence.publications.second.sha)?.let {
        change ->
      if (change.addedMergeParent || !change.sameTree) {
        RepairPublicationAssociation.Published(evidence.publications.second.sha)
      } else {
        RepairPublicationAssociation.Unchanged
      }
    } ?: RepairPublicationAssociation.Unavailable
  }

  private data class RepairWindow(
    val repairStart: FeatureTaskRuntimePhaseLedgerEntry,
    val monitorStart: FeatureTaskRuntimePhaseLedgerEntry,
  )

  private fun repairPublicationEvidence(
    workflowId: String,
    window: RepairWindow,
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    outputs: List<FeatureTaskRuntimePhaseOutput>,
  ): RepairPublicationEvidence? {
    val repair = completedRepair(workflowId, window.repairStart, window.monitorStart, records, ledger) ?: return null
    val publications = publications(workflowId, repair, window.monitorStart, ledger, outputs) ?: return null
    return RepairPublicationEvidence(repair, publications)
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
    val accepted = evidenceReader.readRepairCompletion(workflowId, completion, current)
    val valid =
      evidenceReader.recordMatches(
        current,
        completion,
      ) && evidenceReader.isCompletedEnvelope(accepted, start.phaseId)
    if (!valid || !evidenceReader.producerOutputMatches(workflowId, start, completion)) {
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
          it.sequenceNumber in (repair.completion.sequenceNumber + 1) until monitorStart.sequenceNumber
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
    val start = completion?.let { evidenceReader.matchingStart(it, ledger) }
    val output = completion?.let { evidenceReader.completedPushedCommitOutput(outputs, it.attemptCount) }
    val sha = output?.let(evidenceReader::commitPushSha)
    if (completion == null || start == null || sha == null) {
      unavailableRepairAssociation("pushed publication or its START is missing")
      return null
    }
    if (!evidenceReader.producerOutputMatches(workflowId, start, completion, output)) return null
    return Publication(sha)
  }

  private fun qualifyChange(
    repoRoot: Path,
    repair: RepairAttempt,
    publications: Pair<Publication, Publication>,
  ): RepairPublicationAssociation {
    val baseline = publications.first.sha
    val published = publications.second.sha
    val change = publishedChange(repoRoot, baseline, published) ?: return RepairPublicationAssociation.Unavailable
    if (change.addedMergeParent) return RepairPublicationAssociation.Published(published)
    if (change.sameTree) return RepairPublicationAssociation.Unchanged
    return repair.record?.let { record -> qualifySourceChange(repoRoot, record, publications) }
      ?: unavailableRepairAssociation("repair source attribution is no longer retained")
  }

  private fun qualifySourceChange(
    repoRoot: Path,
    repairRecord: FeatureTaskRuntimePhaseRecord,
    publications: Pair<Publication, Publication>,
  ): RepairPublicationAssociation {
    val changedPaths =
      when (
        val changes =
          gitOperations.runtimePhaseChangedPathsBetweenCommits(
            repoRoot,
            publications.first.sha,
            publications.second.sha,
          )
      ) {
        is WorkflowGitNameListResult.Listed -> changes.names.filterNot(::isRuntimePrivatePath).toSet()
        is WorkflowGitNameListResult.Failed -> return gitEvidenceUnavailable("published path diff failed")
      }
    val changedSource = changedPaths.any(attributedPaths(repairRecord)::contains)
    return if (changedSource) {
      RepairPublicationAssociation.Published(publications.second.sha)
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

  private fun hasUnmatchedRepairCompletionBefore(
    monitorStart: FeatureTaskRuntimePhaseLedgerEntry,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): Boolean =
    ledger.any { completion ->
      completion.phaseId == steps.repair && completion.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE &&
        completion.sequenceNumber < monitorStart.sequenceNumber && evidenceReader.matchingStart(
          completion,
          ledger,
        ) == null
    }

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
}
