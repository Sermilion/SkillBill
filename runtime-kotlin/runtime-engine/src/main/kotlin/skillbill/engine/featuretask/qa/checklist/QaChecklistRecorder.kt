package skillbill.engine.featuretask.qa.checklist

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.failureCodeLabel
import skillbill.error.featuretask.qa.isQaProgressFailure
import skillbill.error.featuretask.qa.qaProgressFailed
import skillbill.error.featuretask.qa.qaProgressReason
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandaloneQaChecklist
import skillbill.ports.idestatus.model.StandaloneQaChecklistWriteResult
import skillbill.ports.idestatus.model.StandaloneQaStepStatus
import skillbill.ports.idestatus.model.StandaloneQaTargetState
import skillbill.ports.qa.QaWorkspacePort
import skillbill.workflow.taskruntime.model.qa.QaRunOccurrence
import skillbill.workflow.taskruntime.model.qa.QaRunScreenshot
import skillbill.workflow.taskruntime.model.qa.QaRunStage
import skillbill.workflow.taskruntime.model.qa.QaRunStageState
import skillbill.workflow.taskruntime.model.skeleton.PhaseRunHarness
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

public sealed interface QaChecklistOutcome {
  public data class Recorded(
    public val rejectedEvidence: List<String> = emptyList(),
    public val retainedPath: String? = null,
  ) : QaChecklistOutcome

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

public data class QaStageReport(
  public val invocationId: String,
  public val name: String,
  public val state: String,
  public val detail: String? = null,
)

@Inject
public class QaChecklistRecorder(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val workspaces: QaWorkspacePort,
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

  public fun recordStage(report: QaStageReport): QaChecklistOutcome =
    catchingNonCancellation {
      database.transaction { unit ->
        val outcome = unit.featureTaskQaOutcomes.findByWorkspaceInvocation(report.invocationId)
        val occurrence = requireActiveOccurrence(outcome, report.invocationId)
        val details = checkNotNull(outcome?.details)
        val occurrenceId = occurrence.occurrenceId
        validateStage(report)
        val previous = occurrence.stages.firstOrNull { it.name == report.name }
        if (previous != null && previous.state != "running") {
          if (previous.state == report.state && previous.detail == report.detail) return@transaction
          throw qaProgressFailed("the QA stage already finished")
        }
        val now = clock.instant().toString()
        val stage =
          if (report.state == "running") {
            previous ?: QaRunStage(report.name, report.state, startedAt = now, detail = report.detail)
          } else {
            (previous ?: QaRunStage(report.name, "running")).copy(
              state = report.state,
              finishedAt = now,
              detail = report.detail,
            )
          }
        val stages = occurrence.stages.filterNot { it.name == report.name } + stage
        unit.featureTaskQaOutcomes.upsert(
          outcome.copy(
            recordedAt = now,
            details =
              details.copy(
                occurrences =
                  details.occurrences.map { current ->
                    if (current.occurrenceId == occurrenceId) current.copy(stages = stages) else current
                  },
              ),
          ),
        )
      }
    }.fold(
      onSuccess = { QaChecklistOutcome.Recorded() },
      onFailure = { failure -> outcomeOf(report.invocationId, failure) },
    )

  public fun recordScreenshot(
    repoRoot: Path,
    invocationId: String,
    sourcePath: Path,
    label: String,
    asFrame: Boolean,
  ): QaChecklistOutcome {
    val standalone =
      catchingNonCancellation {
        if (label.isBlank() || label.length > MAX_SCREENSHOT_LABEL_LENGTH) {
          throw qaProgressFailed("screenshot label is invalid")
        }
        database.read { unit ->
          val goalOutcome = unit.featureTaskQaOutcomes.findByWorkspaceInvocation(invocationId)
          if (goalOutcome == null) {
            requireActiveStandaloneQa(unit.standalonePhaseStatuses.readByInvocation(invocationId))
            true
          } else {
            requireActiveOccurrence(goalOutcome, invocationId)
            false
          }
        }
      }.getOrElse { failure -> return outcomeOf(invocationId, failure) }
    val recordedAt = clock.instant().toString()
    val retained =
      catchingNonCancellation {
        workspaces.retainScreenshot(repoRoot, invocationId, sourcePath, label, asFrame)
      }.getOrElse { failure ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=qa_panel_screenshot kind=retention_failed invocation=${bounded(invocationId)}",
        )
        return QaChecklistOutcome.Failed("screenshot could not be retained")
      }
    if (standalone) return QaChecklistOutcome.Recorded(retainedPath = retained.path)
    return persistScreenshot(invocationId, QaRunScreenshot(label, retained.path, recordedAt, retained.format), asFrame)
  }

  private fun persistScreenshot(
    invocationId: String,
    screenshot: QaRunScreenshot,
    asFrame: Boolean,
  ): QaChecklistOutcome =
    catchingNonCancellation {
      database.transaction { unit ->
        val latest = unit.featureTaskQaOutcomes.findByWorkspaceInvocation(invocationId)
        val occurrence = requireActiveOccurrence(latest, invocationId)
        val details = checkNotNull(latest?.details)
        val screenshots = (occurrence.screenshots + screenshot).distinctBy(QaRunScreenshot::path)
        unit.featureTaskQaOutcomes.upsert(
          latest.copy(
            recordedAt = screenshot.recordedAt,
            details =
              details.copy(
                occurrences =
                  details.occurrences.map { item ->
                    if (item.occurrenceId == occurrence.occurrenceId) {
                      item.copy(
                        screenshots = screenshots,
                        frameReference = if (asFrame) screenshot.path else item.frameReference,
                        frameAvailability = if (asFrame) "captured" else item.frameAvailability,
                      )
                    } else {
                      item
                    }
                  },
              ),
          ),
        )
      }
    }.fold(
      onSuccess = { QaChecklistOutcome.Recorded(retainedPath = screenshot.path) },
      onFailure = { failure ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=qa_panel_screenshot kind=uncommitted_retained_file invocation=${bounded(invocationId)}",
        )
        outcomeOf(invocationId, failure)
      },
    )

  private fun requireActiveOccurrence(
    outcome: FeatureTaskQaOutcome?,
    invocationId: String,
  ): QaRunOccurrence {
    val details = outcome?.details
    val occurrence = details?.occurrences?.firstOrNull { it.workspaceInvocationId == invocationId }
    val rejection =
      when {
        outcome == null -> "no active QA run has this invocation id"
        details == null -> "QA history is unavailable"
        occurrence == null -> "no QA occurrence has this invocation id"
        occurrence.finishedAt != null || occurrence.isTerminal() -> "the QA run already finished"
        else -> null
      }
    if (rejection != null) throw qaProgressFailed(rejection)
    return checkNotNull(occurrence)
  }

  private fun requireActiveStandaloneQa(row: StandalonePhaseStatusRecord?) {
    val rejection =
      when {
        row == null -> "no active QA run has this invocation id"
        SkeletonDefinition.runHarnessOf(row.phaseId) != PhaseRunHarness.DEVICE_QA -> "this invocation is not a QA run"
        row.finishedAt != null -> "the QA run already finished"
        else -> null
      }
    if (rejection != null) throw qaProgressFailed(rejection)
  }

  private fun validateStage(report: QaStageReport) {
    if (report.name.isBlank() || report.name.length > MAX_STAGE_NAME_LENGTH ||
      (report.detail?.length ?: 0) > MAX_STAGE_DETAIL_LENGTH
    ) {
      throw qaProgressFailed("the QA stage report is invalid")
    }
    if (report.state !in QaRunStageState.reportable.map(QaRunStageState::wireValue)) {
      throw qaProgressFailed("the QA stage state is invalid")
    }
  }

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
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
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
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=qa_progress kind=$kind invocation=${bounded(invocationId)} reason=$reason",
    )
    return QaChecklistOutcome.Rejected(reason)
  }

  private fun failed(
    invocationId: String,
    kind: String,
    reason: String,
    failure: Throwable?,
  ): QaChecklistOutcome {
    val label = failure?.let { " failure=${it.failureCodeLabel() ?: it::class.simpleName}" }.orEmpty()
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=qa_progress kind=$kind invocation=${bounded(invocationId)} reason=$reason$label",
    )
    return QaChecklistOutcome.Failed(reason)
  }

  private fun bounded(invocationId: String): String =
    invocationId.take(MAX_LOGGED_INVOCATION_LENGTH).replace(UNSAFE_LOG_CHARACTER, "_")

  private companion object {
    const val MAX_STAGE_NAME_LENGTH = 80
    const val MAX_STAGE_DETAIL_LENGTH = 512
    const val MAX_SCREENSHOT_LABEL_LENGTH = 160
    const val MAX_LOGGED_INVOCATION_LENGTH = 128
    val UNSAFE_LOG_CHARACTER = Regex("[^A-Za-z0-9._-]")
  }
}
