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
      outcomes.upsert(FeatureTaskQaOutcomeRecords.attempted(workflowId, outcomes.find(workflowId), clock.instant()))
    }
  }

  internal fun recordPreviewStarted(
    workflowId: String,
    qa: QaExecutionContext,
  ) {
    val target = qa.target as? QaExecutionTarget.Supported ?: return
    database.transaction { unit ->
      val outcomes = unit.featureTaskQaOutcomes
      val recorded =
        outcomes.find(workflowId)
          ?: FeatureTaskQaOutcomeRecords.attempted(workflowId, null, clock.instant())
      val details = recorded.details ?: QaRunDetails()
      outcomes.upsert(
        recorded.copy(
          details =
            details.copy(
              preview =
                QaRunPreview(
                  qa.workspace.invocationId,
                  qa.workspace.directory.toAbsolutePath().normalize().toString(),
                  target.targets.flatMap { it.kinds }.map { it.wireValue }.distinct(),
                  active = true,
                ),
            ),
        ),
      )
    }
  }

  internal fun recordPreviewEnded(
    workflowId: String,
    invocationId: String,
  ) {
    database.transaction { unit ->
      val outcomes = unit.featureTaskQaOutcomes
      val recorded = outcomes.find(workflowId) ?: return@transaction
      val details = recorded.details ?: return@transaction
      val preview = details.preview?.takeIf { it.invocationId == invocationId } ?: return@transaction
      outcomes.upsert(recorded.copy(details = details.copy(preview = preview.copy(active = false))))
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
          QaRunDetailsMapping.goal(
            tested.report,
            tested.reportPath,
            tested.evidencePrefix,
            tested.producedReason,
            previous?.details,
          ),
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
)
