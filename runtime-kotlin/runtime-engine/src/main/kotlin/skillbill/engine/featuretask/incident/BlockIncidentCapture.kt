package skillbill.engine.featuretask.incident

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.block.BlockIncidentAcceptedReferenceResolver
import skillbill.engine.diagnostics.block.BlockIncidentAliases
import skillbill.engine.diagnostics.block.BlockIncidentObservation
import skillbill.engine.diagnostics.block.BlockIncidentService
import skillbill.engine.diagnostics.block.bestEffort
import skillbill.engine.diagnostics.block.recoverableFailureOf
import skillbill.error.blockincident.BlockIncidentFailureCode
import skillbill.ports.blockincident.BlockIncidentSpool
import skillbill.ports.blockincident.model.BlockIncidentSpoolAppend
import skillbill.ports.blockincident.model.BlockIncidentSpoolRecord
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import java.time.Clock
import java.time.Instant

@Inject
class BlockIncidentCapture(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val spool: BlockIncidentSpool,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun capture(observation: BlockIncidentObservation): BlockIncidentCaptureOutcome {
    val alias =
      try {
        BlockIncidentAliases.fallback(observation)
      } catch (failure: IllegalArgumentException) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Block incident observation has no alias or identity.",
          failure,
        )
        return unsaved("The block observation has no alias or identity to spool under.")
      }
    val observedAt = clock.instant()
    val exists =
      diagnostics.bestEffort("Block incident database availability check failed.", false) {
        database.databaseExists()
      }
    if (!exists) return spool(observation, alias, observedAt, BlockIncidentFailureCode.FALLBACK_SPOOL)
    var resolved = observation
    var entered = false
    var occurrenceToken: String? = null
    val failure =
      recoverableFailureOf {
        val incident =
          database.transaction { unit ->
            entered = true
            observation.linkedAcceptedBlockReference?.let {
              BlockIncidentAcceptedReferenceResolver.validate(it, unit.blockIncidents)
            }
            resolved = observation.withAcceptedFeatureTaskPhaseLink(unit.workflowStates)
            BlockIncidentService.observeBlock(unit.blockIncidents, clock, resolved)
          }
        occurrenceToken = incident.occurrence.occurrenceToken
      }
    occurrenceToken?.let { return BlockIncidentCaptureOutcome.Captured(it) }
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Block incident capture failed; retaining the block and spooling its facts.",
      failure,
    )
    val code = if (entered) BlockIncidentFailureCode.PERSISTENCE else BlockIncidentFailureCode.FALLBACK_SPOOL
    return spool(resolved, alias, observedAt, code)
  }

  private fun BlockIncidentObservation.withAcceptedFeatureTaskPhaseLink(
    states: WorkflowStateRepository,
  ): BlockIncidentObservation {
    val workflow = workflowId?.takeIf { linkAcceptedFeatureTaskPhase && linkOccurrenceToken == null } ?: return this
    val phase = phaseId ?: return this
    val token = BlockIncidentFeatureTaskCorrelation.acceptedPhaseIncidentToken(states, workflow, phase) ?: return this
    return copy(linkOccurrenceToken = token)
  }

  private fun spool(
    observation: BlockIncidentObservation,
    alias: BlockIncidentAlias,
    observedAt: Instant,
    code: BlockIncidentFailureCode,
  ): BlockIncidentCaptureOutcome {
    val record =
      BlockIncidentSpoolRecord(
        occurrenceToken = observation.linkOccurrenceToken ?: BlockIncidentAliases.token(alias),
        alias = alias,
        workflowId = observation.workflowId,
        parentWorkflowId = observation.parentWorkflowId,
        phaseId = observation.phaseId,
        invocationId = observation.invocationId,
        attempt = observation.attempt,
        origin = observation.origin,
        reason = observation.reason.take(MAX_SPOOLED_REASON_CHARS),
        failureCode = observation.failureCode,
        failureDisposition = observation.failureDisposition,
        throwableType = observation.throwableType,
        exitCode = observation.exitCode,
        observedAt = observedAt,
        captureFailureCode = code,
        additionalAliases = observation.additionalAliases,
      )
    val result =
      diagnostics.bestEffort("Block incident spool append threw an exception.", null) { spool.append(record) }
        ?: return unsaved("The block incident spool could not save the record.")
    return when (result) {
      is BlockIncidentSpoolAppend.Appended -> BlockIncidentCaptureOutcome.Spooled(record.occurrenceToken)
      BlockIncidentSpoolAppend.DroppedFull -> unsaved("The block incident spool is full.")
      is BlockIncidentSpoolAppend.Failed ->
        unsaved("The block incident spool could not save the record: ${result.message}")
    }
  }

  private fun unsaved(message: String): BlockIncidentCaptureOutcome {
    RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "Block incident evidence availability UNSAVED. $message")
    return BlockIncidentCaptureOutcome.Unsaved
  }

  private companion object {
    const val MAX_SPOOLED_REASON_CHARS = 8192
  }
}

internal sealed interface BlockIncidentCaptureOutcome {
  data class Captured(val occurrenceToken: String) : BlockIncidentCaptureOutcome

  data class Spooled(val occurrenceToken: String) : BlockIncidentCaptureOutcome

  data object Unsaved : BlockIncidentCaptureOutcome
}
