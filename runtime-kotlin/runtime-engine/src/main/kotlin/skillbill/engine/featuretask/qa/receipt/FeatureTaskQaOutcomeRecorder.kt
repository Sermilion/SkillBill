package skillbill.engine.featuretask.qa.receipt

import me.tatarka.inject.annotations.Inject
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import java.time.Clock

@Inject
class FeatureTaskQaOutcomeRecorder(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
) {
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
            tested.receiptVerdict,
            tested.receipt,
            tested.receiptPath,
          ),
          FeatureTaskQaOutcomeRecords.criteriaReport(tested.receipt) ?: previous?.report,
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
      val recorded = outcomes.find(workflowId) ?: return@transaction
      if (recorded.reason?.contains(warning) == true) return@transaction
      outcomes.upsert(recorded.copy(reason = listOfNotNull(recorded.reason, warning).joinToString("; ")))
    }
  }
}

data class QaSettledRun(
  val treeIdentity: String?,
  val receiptVerdict: QaVerdict = QaVerdict.INCOMPLETE,
  val receipt: Map<String, Any?> = emptyMap(),
  val receiptPath: String? = null,
)
