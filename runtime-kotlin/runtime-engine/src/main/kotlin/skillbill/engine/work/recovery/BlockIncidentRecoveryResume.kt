package skillbill.engine.work.recovery

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.incident.recovery.recordBlockIncidentRecoveryOutcome
import skillbill.ports.blockincident.model.BlockIncidentRecoveryTransition
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecovery
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryFailure
import java.time.Clock
import java.time.Duration

internal class BlockIncidentRecoveryResume(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val identifiers: IdentifierGeneratorPort,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun claim(
    observation: DatabaseFileObservation,
    idempotencyKey: String,
  ): BlockIncidentRecoveryResumeClaim {
    val result =
      database.transactionFenced(observation) { unit ->
        val applied =
          unit.blockIncidentRecoveries.byIdempotencyKey(idempotencyKey)
            ?: return@transactionFenced BlockIncidentRecoveryResumeClaim.NotClaimed(null)
        if (applied.status != BlockIncidentRecoveryAttemptStatus.APPLIED) {
          return@transactionFenced BlockIncidentRecoveryResumeClaim.NotClaimed(applied)
        }
        val now = clock.instant()
        val ownerToken = identifiers.randomToken()
        if (!unit.blockIncidentRecoveries.transition(
            BlockIncidentRecoveryTransition(
              applied.attemptId,
              BlockIncidentRecoveryAttemptStatus.APPLIED,
              BlockIncidentRecoveryAttemptStatus.RESUMING,
              null,
              ownerToken,
              applied.ownerGeneration,
              now.plus(RESUME_LEASE),
              now,
            ),
          )
        ) {
          return@transactionFenced BlockIncidentRecoveryResumeClaim.NotClaimed(
            unit.blockIncidentRecoveries.byIdempotencyKey(idempotencyKey),
          )
        }
        val resuming =
          unit.blockIncidentRecoveries.byIdempotencyKey(idempotencyKey)
            ?.takeIf { it.status == BlockIncidentRecoveryAttemptStatus.RESUMING && it.ownerToken == ownerToken }
            ?: error("Claimed recovery attempt '${applied.attemptId}' was not readable after RESUMING.")
        BlockIncidentRecoveryResumeClaim.Claimed(resuming)
      }
    return when (result) {
      FencedTransactionResult.ChangedTarget -> BlockIncidentRecoveryResumeClaim.ChangedDatabaseFile
      is FencedTransactionResult.Committed -> result.value
    }
  }

  fun settle(
    observation: DatabaseFileObservation,
    claim: BlockIncidentRecoveryResumeClaim.Claimed,
    launched: Boolean,
  ): BlockIncidentRecoveryResult {
    val resuming = claim.attempt
    val result =
      database.transactionFenced(observation) { unit ->
        val now = clock.instant()
        val next =
          if (launched) {
            BlockIncidentRecoveryAttemptStatus.RESUMED
          } else {
            BlockIncidentRecoveryAttemptStatus.FAILED
          }
        val incident = unit.blockIncidents.find(resuming.occurrenceToken)
        val moved =
          unit.blockIncidentRecoveries.transition(
            BlockIncidentRecoveryTransition(
              resuming.attemptId,
              BlockIncidentRecoveryAttemptStatus.RESUMING,
              next,
              if (launched) null else BlockIncidentRecoveryFailure.RESUME_FAILED,
              resuming.ownerToken,
              resuming.ownerGeneration,
              null,
              now,
            ),
          )
        val stored =
          unit.blockIncidentRecoveries.byIdempotencyKey(resuming.idempotencyKey)
            ?: error("Resuming recovery attempt '${resuming.attemptId}' disappeared.")
        if (!moved) return@transactionFenced BlockIncidentRecoveryResult.Existing(stored)
        if (incident != null) {
          recordBlockIncidentRecoveryOutcome(
            unit,
            incident,
            if (launched) BlockIncidentRecovery.COMPLETE else BlockIncidentRecovery.FAILED,
          )
        }
        if (launched) {
          BlockIncidentRecoveryResult.Resumed(stored)
        } else {
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "Block incident recovery attempt '${resuming.attemptId}' failed with " +
              "${BlockIncidentRecoveryFailure.RESUME_FAILED.wireValue} for occurrence '${resuming.occurrenceToken}'.",
          )
          BlockIncidentRecoveryResult.Failed(stored)
        }
      }
    return when (result) {
      FencedTransactionResult.ChangedTarget ->
        BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.CHANGED_DATABASE_FILE)
      is FencedTransactionResult.Committed -> result.value
    }
  }

  private companion object {
    val RESUME_LEASE: Duration = Duration.ofMinutes(5)
  }
}

internal sealed interface BlockIncidentRecoveryResumeClaim {
  /** This caller owns RESUMING and is the only one allowed to launch the resumed run. */
  data class Claimed(val attempt: BlockIncidentRecoveryAttempt) : BlockIncidentRecoveryResumeClaim

  /** The attempt was missing, not APPLIED, or claimed by someone else; never launch. */
  data class NotClaimed(val attempt: BlockIncidentRecoveryAttempt?) : BlockIncidentRecoveryResumeClaim

  /** The database file changed since the caller observed it; nothing was written. */
  data object ChangedDatabaseFile : BlockIncidentRecoveryResumeClaim
}
