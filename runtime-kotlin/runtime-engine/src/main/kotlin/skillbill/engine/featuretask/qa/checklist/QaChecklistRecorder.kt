package skillbill.engine.featuretask.qa.checklist

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.failureCodeLabel
import skillbill.error.featuretask.qa.isQaProgressFailure
import skillbill.error.featuretask.qa.qaProgressFailed
import skillbill.error.featuretask.qa.qaProgressReason
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandaloneQaChecklist
import skillbill.ports.idestatus.model.StandaloneQaChecklistWriteResult
import skillbill.ports.idestatus.model.StandaloneQaStepStatus
import skillbill.ports.idestatus.model.StandaloneQaTargetState
import skillbill.workflow.taskruntime.model.skeleton.PhaseRunHarness
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.time.Clock
import java.time.Instant

public sealed interface QaChecklistOutcome {
  public data class Recorded(public val rejectedEvidence: List<String> = emptyList()) : QaChecklistOutcome

  public data class Rejected(public val reason: String) : QaChecklistOutcome

  public data class Failed(public val reason: String) : QaChecklistOutcome
}

public data class QaChecklistSeed(
  public val invocationId: String,
  public val invocationDirectory: String,
  public val reportPath: String,
  public val testedCommit: String,
  public val targets: List<String>,
  public val criteriaSource: String?,
)

public data class QaStepReport(
  public val invocationId: String,
  public val caseId: String,
  public val stepNumber: Int,
  public val status: StandaloneQaStepStatus,
  public val note: String? = null,
  public val findingId: String? = null,
  public val findingTitle: String? = null,
  public val evidence: List<String> = emptyList(),
)

public data class QaTargetReport(
  public val invocationId: String,
  public val name: String,
  public val state: StandaloneQaTargetState,
  public val detail: String? = null,
)

@Inject
public class QaChecklistRecorder(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun seed(seed: QaChecklistSeed): QaChecklistOutcome =
    record(seed.invocationId) { current, _ -> QaChecklistMerge.seeded(current, seed) }

  public fun registerPlan(
    invocationId: String,
    planJson: String,
  ): QaChecklistOutcome {
    val plan =
      catchingNonCancellation { QaChecklistPlanCodec.decode(planJson) }
        .getOrElse { failure -> return outcomeOf(invocationId, failure) }
    return record(invocationId) { current, now -> QaChecklistMerge.withPlan(current, plan, now) }
  }

  public fun recordStep(report: QaStepReport): QaChecklistOutcome =
    record(report.invocationId) { current, now -> QaChecklistMerge.withStep(current, report, now) }

  public fun recordTarget(report: QaTargetReport): QaChecklistOutcome =
    record(report.invocationId) { current, now -> QaChecklistMerge.withTarget(current, report, now) }

  public fun planFileUnreadable(invocationId: String): QaChecklistOutcome =
    failed(invocationId, "plan_unreadable", "the plan file could not be read", null)

  public fun planFileOversized(invocationId: String): QaChecklistOutcome =
    rejected(invocationId, "plan_oversized", "the plan file exceeds the size limit")

  private fun record(
    invocationId: String,
    merge: (StandaloneQaChecklist, Instant) -> QaChecklistMerged,
  ): QaChecklistOutcome =
    catchingNonCancellation {
      database.transaction { unit ->
        val statuses = unit.standalonePhaseStatuses
        val row = statuses.readByInvocation(invocationId) ?: throw qaProgressFailed("no QA run has this invocation id")
        if (SkeletonDefinition.runHarnessOf(row.phaseId) != PhaseRunHarness.DEVICE_QA) {
          throw qaProgressFailed("this invocation is not a QA run")
        }
        val merged = merge(row.qaChecklist ?: StandaloneQaChecklist(), clock.instant())
        when (statuses.writeQaChecklist(invocationId, merged.checklist)) {
          StandaloneQaChecklistWriteResult.WRITTEN -> merged.rejectedEvidence
          StandaloneQaChecklistWriteResult.MISSING -> throw qaProgressFailed("no QA run has this invocation id")
          StandaloneQaChecklistWriteResult.FINISHED -> throw qaProgressFailed("the QA run already finished")
        }
      }
    }.fold(
      onSuccess = { rejectedEvidence ->
        if (rejectedEvidence.isNotEmpty()) {
          diagnostics.warning(
            "seam=qa_progress kind=evidence_rejected invocation=${bounded(invocationId)} " +
              "count=${rejectedEvidence.size}",
          )
        }
        QaChecklistOutcome.Recorded(rejectedEvidence)
      },
      onFailure = { failure -> outcomeOf(invocationId, failure) },
    )

  private fun outcomeOf(
    invocationId: String,
    failure: Throwable,
  ): QaChecklistOutcome {
    val progressFailure = (failure as? SkillBillRuntimeException)?.takeIf { it.isQaProgressFailure }
    return if (progressFailure != null) {
      rejected(invocationId, "rejected", progressFailure.qaProgressReason)
    } else {
      failed(invocationId, "failed", "database write failed", failure)
    }
  }

  private fun rejected(
    invocationId: String,
    kind: String,
    reason: String,
  ): QaChecklistOutcome {
    diagnostics.warning("seam=qa_progress kind=$kind invocation=${bounded(invocationId)} reason=$reason")
    return QaChecklistOutcome.Rejected(reason)
  }

  private fun failed(
    invocationId: String,
    kind: String,
    reason: String,
    failure: Throwable?,
  ): QaChecklistOutcome {
    val label = failure?.let { " failure=${it.failureCodeLabel() ?: it::class.simpleName}" }.orEmpty()
    diagnostics.warning("seam=qa_progress kind=$kind invocation=${bounded(invocationId)} reason=$reason$label")
    return QaChecklistOutcome.Failed(reason)
  }

  private fun bounded(invocationId: String): String =
    invocationId.take(MAX_LOGGED_INVOCATION_LENGTH).replace(UNSAFE_LOG_CHARACTER, "_")

  private companion object {
    const val MAX_LOGGED_INVOCATION_LENGTH = 128
    val UNSAFE_LOG_CHARACTER = Regex("[^A-Za-z0-9._-]")
  }
}
