package skillbill.engine.featuretask.qa.report

import me.tatarka.inject.annotations.Inject
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.ports.featuretask.model.FeatureTaskQaFinding
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import java.time.Clock

@Inject
class FeatureTaskQaOutcomeRecorder(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
) {
  fun find(workflowId: String): FeatureTaskQaOutcome? =
    database.read { unitOfWork -> unitOfWork.featureTaskQaOutcomes.find(workflowId) }

  fun recordAttempted(workflowId: String) {
    database.transaction { unitOfWork ->
      val outcomes = unitOfWork.featureTaskQaOutcomes
      outcomes.upsert(FeatureTaskQaOutcomeRecords.attempted(workflowId, outcomes.find(workflowId), clock.instant()))
    }
  }

  fun recordSettled(
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
        ),
      )
    }
  }

  fun recordRepairAttempts(
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

  fun recordWarning(
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

  fun recordContainedFailure(
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

data class QaSettledRun(
  val treeIdentity: String?,
  val verdict: QaVerdict = QaVerdict.INCOMPLETE,
  val report: QaReport? = null,
  val reportPath: String? = null,
  val legacyFindings: List<FeatureTaskQaFinding> = emptyList(),
  val failureKind: FeatureTaskQaFailureKind? = null,
)
