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
    testedTreeIdentity: String?,
  ) {
    database.transaction { unitOfWork ->
      unitOfWork.featureTaskQaOutcomes.upsert(
        FeatureTaskQaOutcome(
          workflowId,
          FeatureTaskQaOutcomeRecords.outcome(verdict),
          reason?.takeIf(String::isNotBlank),
          qaFixAttempts,
          testedTreeIdentity,
          "${clock.instant()}",
        ),
      )
    }
  }
}
