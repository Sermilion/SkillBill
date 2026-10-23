package skillbill.engine.diagnostics.block.report

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.ports.blockincident.BlockIncidentTelemetryPort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCertainty
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentResponsibleCategory
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportSanitizer
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportStatus
import java.time.Clock
import java.util.concurrent.CancellationException

@Inject
class ProductReportScheduler(
  private val database: DatabaseSessionFactory,
  private val runtimeFacts: ProductReportRuntimeFactsCollector,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val telemetry: BlockIncidentTelemetryPort?,
) {
  fun schedule(
    incident: BlockIncidentOccurrence,
    diagnosis: BlockIncidentDiagnosisRecord,
    recoveryAttempts: List<BlockIncidentRecoveryAttempt>,
  ) {
    runCatching {
      val eligible =
        database.transaction { unit ->
          val marker = unit.blockIncidents.rolloutActivatedAt()
          if (marker == null || incident.observedAt < marker) return@transaction false
          if (diagnosis.responsibleCategory != BlockIncidentResponsibleCategory.SKILL_BILL_DEFECT) {
            return@transaction false
          }
          diagnosis.certainty == BlockIncidentCertainty.DIAGNOSED ||
            diagnosis.certainty == BlockIncidentCertainty.SUSPECTED && diagnosis.evidence.isNotEmpty()
        }
      if (!eligible) return
      val draft =
        ProductReportSanitizer.sanitize(incident, diagnosis, recoveryAttempts, runtimeFacts.collect(diagnosis))
      val created =
        database.transaction { unit ->
          unit.productReports.createOrLink(
            draft,
            incident.occurrenceToken,
            clock.instant(),
            recoveryAttempts.filter { it.status in TERMINAL_RECOVERY_STATUSES }.map { it.attemptId }.toSet(),
          )
        }
      val result = created.report
      if (result.status in TERMINAL_REPORT_STATUSES && (created.reportInserted || created.linkInserted)) {
        runCatching {
          telemetry?.reported(incident, result)
        }.getOrElse { error ->
          error.rethrowIfCancellation()
          RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "Product report creation telemetry failed.", error)
        }
      }
    }.getOrElse { error ->
      error.rethrowIfCancellation()
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Product report scheduling failed; the diagnosis remains settled.",
        error,
      )
    }
  }

  private fun Throwable.rethrowIfCancellation() {
    if (this !is Exception) throw this
    if (this is CancellationException || this is InterruptedException || Thread.currentThread().isInterrupted) {
      throw this
    }
  }

  private companion object {
    val TERMINAL_REPORT_STATUSES =
      setOf(
        ProductReportStatus.LOCAL_ONLY,
        ProductReportStatus.DELIVERED,
        ProductReportStatus.LINKED_EXISTING,
        ProductReportStatus.FAILED,
      )
    val TERMINAL_RECOVERY_STATUSES =
      setOf(
        BlockIncidentRecoveryAttemptStatus.FAILED,
        BlockIncidentRecoveryAttemptStatus.RESUMED,
      )
  }
}
