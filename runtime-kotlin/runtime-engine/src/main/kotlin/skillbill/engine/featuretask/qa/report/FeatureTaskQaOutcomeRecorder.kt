package skillbill.engine.featuretask.qa.report

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.ports.featuretask.model.FeatureTaskQaFinding
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.workflow.taskruntime.model.qa.QaRunDetails
import skillbill.workflow.taskruntime.model.qa.QaRunKind
import skillbill.workflow.taskruntime.model.qa.QaRunOccurrence
import skillbill.workflow.taskruntime.model.qa.QaRunPreview
import java.time.Clock

@Inject
public class FeatureTaskQaOutcomeRecorder(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
) {
  internal fun find(workflowId: String): FeatureTaskQaOutcome? =
    database.read { unitOfWork -> unitOfWork.featureTaskQaOutcomes.find(workflowId) }

  internal fun recordAttempted(workflowId: String) {
    database.transaction { unitOfWork ->
      val outcomes = unitOfWork.featureTaskQaOutcomes
      val previous = outcomes.find(workflowId)
      val attempted = FeatureTaskQaOutcomeRecords.attempted(workflowId, previous, clock.instant())
      outcomes.upsert(
        if (previous == null) attempted.copy(details = QaRunDetails(historyAvailability = "recorded")) else attempted,
      )
    }
  }

  internal fun recordPreviewStarted(
    workflowId: String,
    qa: QaExecutionContext,
    phaseAttempt: Int?,
  ) {
    database.transaction { unit ->
      val outcomes = unit.featureTaskQaOutcomes
      val previous = outcomes.find(workflowId)
      val recorded = previous ?: FeatureTaskQaOutcomeRecords.attempted(workflowId, null, clock.instant())
      val retainedDetails =
        recorded.details ?: QaRunDetails(
          historyAvailability = if (previous == null && phaseAttempt == 1) "recorded" else "not_recorded",
        )
      val details =
        if (retainedDetails.occurrences.isEmpty() && phaseAttempt != 1) {
          retainedDetails.copy(historyAvailability = "not_recorded")
        } else {
          retainedDetails
        }
      val occurrenceId = "${QaRunKind.QA.wireValue}:${qa.workspace.invocationId}"
      val occurrence = details.occurrences.firstOrNull { it.occurrenceId == occurrenceId }
      if (occurrence?.let(QaRunOccurrence::isTerminal) == true) return@transaction
      val started =
        occurrence ?: QaRunOccurrence(
          occurrenceId = occurrenceId,
          kind = QaRunKind.QA.wireValue,
          phaseAttempt = phaseAttempt,
          workspaceInvocationId = qa.workspace.invocationId,
          startedAt = clock.instant().toString(),
          testedTreeIdentity = qa.expected.treeIdentity,
          disposition = (qa.target as? QaExecutionTarget.Skipped)?.reason?.wireValue ?: "running",
          finishedAt = if (qa.target is QaExecutionTarget.Skipped) clock.instant().toString() else null,
          frameAvailability = if (qa.target is QaExecutionTarget.Skipped) "not_applicable" else "not_captured",
        )
      outcomes.upsert(
        recorded.copy(
          details =
            details.withStartedOccurrence(started).copy(
              preview =
                QaRunPreview(
                  qa.workspace.invocationId,
                  qa.workspace.directory.toAbsolutePath().normalize().toString(),
                  (qa.target as? QaExecutionTarget.Supported)?.targets.orEmpty()
                    .flatMap { it.kinds }.map { it.wireValue }.distinct(),
                  active = qa.target is QaExecutionTarget.Supported,
                ),
            ),
        ),
      )
    }
  }

  internal fun recordPreviewEnded(
    workflowId: String,
    invocationId: String,
    disposition: String,
  ) {
    database.transaction { unit ->
      val outcomes = unit.featureTaskQaOutcomes
      val recorded = outcomes.find(workflowId) ?: return@transaction
      val details = recorded.details ?: return@transaction
      val occurrenceId = "${QaRunKind.QA.wireValue}:$invocationId"
      val preview =
        details.preview?.let { current ->
          if (current.invocationId == invocationId) current.copy(active = false) else current
        }
      val finishedAt = clock.instant().toString()
      val occurrences =
        details.occurrences.map { occurrence ->
          if (occurrence.occurrenceId == occurrenceId && !occurrence.isTerminal()) {
            occurrence.finished(disposition, finishedAt)
          } else {
            occurrence
          }
        }
      outcomes.upsert(recorded.copy(details = details.copy(preview = preview, occurrences = occurrences)))
    }
  }

  internal fun recordRepairStarted(
    workflowId: String,
    attempt: Int?,
  ) {
    if (attempt == null || attempt < 1) return
    database.transaction { unit ->
      val outcomes = unit.featureTaskQaOutcomes
      val recorded = outcomes.find(workflowId) ?: return@transaction
      val details = recorded.details ?: QaRunDetails()
      val occurrenceId = "${QaRunKind.REPAIR.wireValue}:$attempt"
      val existing = details.occurrences.firstOrNull { it.occurrenceId == occurrenceId }
      if (existing?.isTerminal() == true) return@transaction
      val occurrence =
        existing ?: QaRunOccurrence(
          occurrenceId = occurrenceId,
          kind = QaRunKind.REPAIR.wireValue,
          phaseAttempt = attempt,
          workspaceInvocationId = null,
          startedAt = clock.instant().toString(),
          frameAvailability = "not_applicable",
        )
      outcomes.upsert(
        recorded.copy(
          details = details.withStartedOccurrence(occurrence),
        ),
      )
    }
  }

  internal fun recordRepairSettled(
    workflowId: String,
    attempt: Int?,
    disposition: String,
  ) {
    if (attempt == null || attempt < 1) return
    database.transaction { unit ->
      val outcomes = unit.featureTaskQaOutcomes
      val recorded = outcomes.find(workflowId) ?: return@transaction
      val details = recorded.details ?: return@transaction
      val occurrenceId = "${QaRunKind.REPAIR.wireValue}:$attempt"
      val now = clock.instant().toString()
      val occurrences =
        details.occurrences.map { occurrence ->
          if (occurrence.occurrenceId == occurrenceId && !occurrence.isTerminal()) {
            occurrence.finished(disposition, now)
          } else {
            occurrence
          }
        }
      outcomes.upsert(recorded.copy(recordedAt = now, details = details.copy(occurrences = occurrences)))
    }
  }

  internal fun recordSettled(
    workflowId: String,
    verdict: QaVerdict,
    reason: String?,
    qaFixAttempts: Int,
    tested: QaSettledRun,
  ) {
    database.transaction { unitOfWork ->
      val outcomes = unitOfWork.featureTaskQaOutcomes
      val previous = outcomes.find(workflowId)
      val reported =
        tested.report?.let { FeatureTaskQaOutcomeRecords.reportedFindings(tested.verdict, it, tested.reportPath) }
          ?: tested.legacyFindings
      outcomes.upsert(
        FeatureTaskQaOutcome(
          workflowId,
          FeatureTaskQaOutcomeRecords.outcome(verdict),
          reason?.takeIf(String::isNotBlank),
          qaFixAttempts,
          tested.treeIdentity,
          "${clock.instant()}",
          FeatureTaskQaOutcomeRecords.mergedFindings(
            previous?.findings.orEmpty(),
            FeatureTaskQaOutcomeRecords.retestsFindings(tested.report, tested.verdict),
            reported,
          ),
          tested.report?.text,
          tested.failureKind,
          QaRunDetailsMapping.goal(tested, previous?.details)?.let { mapped ->
            val activeId = "${QaRunKind.QA.wireValue}:${tested.invocationId}"
            val occurrences =
              mapped.occurrences.map { occurrence ->
                if (occurrence.occurrenceId == activeId && !occurrence.isTerminal()) {
                  occurrence.finished(verdict.wireValue, clock.instant().toString()).copy(
                    resultReference = tested.reportPath,
                    testedTreeIdentity = tested.treeIdentity,
                  )
                } else {
                  occurrence
                }
              }
            mapped.copy(occurrences = occurrences)
          },
        ),
      )
    }
  }

  internal fun recordRepairAttempts(
    workflowId: String,
    qaFixAttempts: Int,
  ) {
    database.transaction { unitOfWork ->
      val outcomes = unitOfWork.featureTaskQaOutcomes
      val recorded = outcomes.find(workflowId) ?: return@transaction
      if (recorded.qaFixAttempts >= qaFixAttempts) return@transaction
      outcomes.upsert(recorded.copy(qaFixAttempts = qaFixAttempts, recordedAt = "${clock.instant()}"))
    }
  }

  internal fun recordRetainedResources(
    workflowId: String,
    resources: List<QaRetainedResource>,
  ) {
    if (resources.isEmpty()) return
    database.transaction { unitOfWork ->
      val outcomes = unitOfWork.featureTaskQaOutcomes
      val recorded =
        outcomes.find(workflowId)
          ?: incompleteRow(workflowId, "QA retained resources before recording an outcome", null)
      val details = recorded.details ?: QaRunDetails()
      val retained = (details.retainedResources + QaRunDetailsMapping.retained(resources)).distinct()
      outcomes.upsert(
        recorded.copy(
          recordedAt = "${clock.instant()}",
          details = details.copy(retainedResources = retained),
        ),
      )
    }
  }

  public fun recordWarning(
    workflowId: String,
    warning: String,
  ) {
    database.transaction { unitOfWork ->
      val outcomes = unitOfWork.featureTaskQaOutcomes
      val recorded = outcomes.find(workflowId)
      if (recorded == null) {
        outcomes.upsert(incompleteRow(workflowId, warning, null))
        return@transaction
      }
      val reason = appendedReason(recorded.reason, warning)
      if (reason == recorded.reason) return@transaction
      outcomes.upsert(recorded.copy(outcome = StandaloneQaOutcome.INCOMPLETE, reason = reason))
    }
  }

  public fun recordContainedFailure(
    workflowId: String,
    kind: FeatureTaskQaFailureKind,
    reason: String,
  ) {
    database.transaction { unitOfWork ->
      val outcomes = unitOfWork.featureTaskQaOutcomes
      val recorded = outcomes.find(workflowId)
      outcomes.upsert(
        recorded?.copy(
          outcome = StandaloneQaOutcome.INCOMPLETE,
          reason = appendedReason(recorded.reason, reason),
          recordedAt = "${clock.instant()}",
          failureKind = kind,
        ) ?: incompleteRow(workflowId, reason, kind),
      )
    }
  }

  private fun incompleteRow(
    workflowId: String,
    reason: String,
    failureKind: FeatureTaskQaFailureKind?,
  ): FeatureTaskQaOutcome =
    FeatureTaskQaOutcome(
      workflowId,
      StandaloneQaOutcome.INCOMPLETE,
      reason,
      0,
      null,
      "${clock.instant()}",
      failureKind = failureKind,
    )

  private fun appendedReason(
    existing: String?,
    addition: String,
  ): String =
    if (existing?.contains(addition) == true) existing else listOfNotNull(existing, addition).joinToString("; ")
}

public data class QaSettledRun(
  public val treeIdentity: String?,
  public val verdict: QaVerdict = QaVerdict.INCOMPLETE,
  public val report: QaReport? = null,
  public val reportPath: String? = null,
  internal val legacyFindings: List<FeatureTaskQaFinding> = emptyList(),
  public val failureKind: FeatureTaskQaFailureKind? = null,
  public val evidencePrefix: String? = null,
  public val producedReason: String? = null,
  public val invocationId: String? = null,
)
