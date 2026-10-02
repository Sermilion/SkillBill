package skillbill.application.diagnostics

import me.tatarka.inject.annotations.Inject
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticDeletion
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticInspectionResult
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticMetadata
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRawRead
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticSelection
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import skillbill.ports.persistence.UnitOfWork
import java.time.Clock

@Inject
class RejectedOutputDiagnosticInspection(
  private val database: DatabaseSessionFactory,
  private val metadataValidator: RejectedOutputDiagnosticMetadataValidator,
  private val clock: Clock,
) {
  fun inspect(
    selector: RejectedOutputDiagnosticSelector,
    rawOutput: Boolean,
  ): RejectedOutputDiagnosticInspectionResult =
    database.selfManagedWrite { unitOfWork ->
      val service = unitOfWork.diagnosticService()
      when (val selection = service.inspect(selector)) {
        is RejectedOutputDiagnosticSelection.InvalidRequest ->
          RejectedOutputDiagnosticInspectionResult.InvalidRequest(selection.reason)
        is RejectedOutputDiagnosticSelection.Selected ->
          inspectSelected(service, selector, selection.diagnostics, rawOutput)
      }
    }

  fun cleanup(selector: RejectedOutputDiagnosticSelector): RejectedOutputDiagnosticDeletion =
    database.transaction { unitOfWork ->
      unitOfWork.diagnosticService().delete(selector)
    }

  private fun inspectSelected(
    service: RejectedOutputDiagnosticService,
    selector: RejectedOutputDiagnosticSelector,
    matches: List<RejectedOutputDiagnostic>,
    rawOutput: Boolean,
  ): RejectedOutputDiagnosticInspectionResult =
    when {
      matches.isEmpty() -> RejectedOutputDiagnosticInspectionResult.Absent(selector.workflowId)
      !rawOutput -> RejectedOutputDiagnosticInspectionResult.Metadata(matches.map { it.toMetadata() })
      matches.size != 1 -> RejectedOutputDiagnosticInspectionResult.AmbiguousSelector(matches.size)
      else ->
        when (val raw = service.readRaw(matches.single().identity)) {
          is RejectedOutputDiagnosticRawRead.Payload -> RejectedOutputDiagnosticInspectionResult.RawBytes(raw.bytes)
          is RejectedOutputDiagnosticRawRead.Absent -> RejectedOutputDiagnosticInspectionResult.Absent(raw.identity)
          is RejectedOutputDiagnosticRawRead.Expired -> RejectedOutputDiagnosticInspectionResult.Expired(raw.identity)
          is RejectedOutputDiagnosticRawRead.Oversized ->
            RejectedOutputDiagnosticInspectionResult.Oversized(raw.identity)
        }
    }

  private fun UnitOfWork.diagnosticService(): RejectedOutputDiagnosticService =
    RejectedOutputDiagnosticService(
      rejectedOutputDiagnostics,
      rejectedOutputDiagnosticPermissions,
      metadataValidator,
      clock = clock,
    )
}

private fun RejectedOutputDiagnostic.toMetadata(): RejectedOutputDiagnosticMetadata =
  RejectedOutputDiagnosticMetadata(
    identity = identity,
    workflowId = workflowId,
    phaseId = phaseId,
    attempt = attempt,
    repairTurn = repairTurn,
    rule = rule,
    path = path,
    reason = reason,
    agentId = agentId,
    model = model,
    recordedAt = recordedAt,
    byteSize = byteSize,
    sha256 = sha256,
    lifecycle = lifecycle,
  )
