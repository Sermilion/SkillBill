package skillbill.engine.diagnostics.block

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.BlockIncidentFailureCode
import skillbill.ports.blockincident.BlockIncidentSpool
import skillbill.ports.blockincident.BlockIncidentSpoolAppend
import skillbill.ports.blockincident.BlockIncidentSpoolRecord
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import java.time.Instant
import java.time.Clock
import java.util.concurrent.CancellationException

@Inject
class BlockIncidentCapture(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val spool: BlockIncidentSpool,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun capture(observation: BlockIncidentObservation): BlockIncidentCaptureOutcome {
    val alias = BlockIncidentAliases.fallback(observation)
    val observedAt = clock.instant()
    val exists = try {
      database.databaseExists()
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: Exception) {
      diagnostics.warning("Block incident database availability check failed.", failure)
      false
    }
    if (!exists) return spool(observation, alias, observedAt, BlockIncidentFailureCode.FALLBACK_SPOOL)
    return try {
      val incident = database.transaction { unit ->
        BlockIncidentService.observeBlock(unit.blockIncidents, clock, observation)
      }
      BlockIncidentCaptureOutcome.Captured(incident.occurrence.occurrenceToken)
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: Exception) {
      diagnostics.warning("Block incident capture failed; retaining the block and spooling its facts.", failure)
      spool(observation, alias, observedAt, BlockIncidentFailureCode.PERSISTENCE)
    }
  }

  private fun spool(
    observation: BlockIncidentObservation,
    alias: BlockIncidentAlias,
    observedAt: Instant,
    code: BlockIncidentFailureCode,
  ): BlockIncidentCaptureOutcome {
    val record = BlockIncidentSpoolRecord(
      occurrenceToken = BlockIncidentAliases.token(alias),
      alias = alias,
      workflowId = observation.workflowId,
      parentWorkflowId = observation.parentWorkflowId,
      phaseId = observation.phaseId,
      invocationId = observation.invocationId,
      attempt = observation.attempt,
      origin = observation.origin,
      reason = observation.reason.take(8192),
      failureCode = observation.failureCode,
      failureDisposition = observation.failureDisposition,
      throwableType = observation.throwableType,
      exitCode = observation.exitCode,
      observedAt = observedAt,
      captureFailureCode = code,
    )
    val result = try {
      spool.append(record)
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: Exception) {
      diagnostics.warning("Block incident spool append threw an exception.", failure)
      return unsaved("The block incident spool could not save the record.")
    }
    return when (result) {
      is BlockIncidentSpoolAppend.Appended -> BlockIncidentCaptureOutcome.Spooled(record.occurrenceToken)
      BlockIncidentSpoolAppend.DroppedFull -> unsaved("The block incident spool is full.")
      is BlockIncidentSpoolAppend.Failed -> unsaved("The block incident spool could not save the record: ${result.message}")
    }
  }

  private fun unsaved(message: String): BlockIncidentCaptureOutcome {
    diagnostics.warning("Block incident evidence availability UNSAVED. $message")
    return BlockIncidentCaptureOutcome.Unsaved
  }
}

internal sealed interface BlockIncidentCaptureOutcome {
  data class Captured(val occurrenceToken: String) : BlockIncidentCaptureOutcome
  data class Spooled(val occurrenceToken: String) : BlockIncidentCaptureOutcome
  data object Unsaved : BlockIncidentCaptureOutcome
}
