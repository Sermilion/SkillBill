package skillbill.ports.blockincident

import skillbill.error.core.BlockIncidentFailureCode
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import java.time.Instant

/**
 * Bounded store for block observations that could not reach the database. It keeps only facts the producer held,
 * never database evidence, so the next writable entry can create or link the incident from them.
 */
interface BlockIncidentSpool {
  /** Stores one record; a full spool keeps its existing records and refuses the new one. */
  fun append(record: BlockIncidentSpoolRecord): BlockIncidentSpoolAppend

  /** Returns up to [limit] stored records, oldest first, skipping records that cannot be read. */
  fun pending(limit: Int): List<BlockIncidentSpoolEntry>

  /** Deletes one stored record after it has been imported. */
  fun remove(recordId: String)
}

data class BlockIncidentSpoolRecord(
  val occurrenceToken: String,
  val alias: BlockIncidentAlias,
  val workflowId: String?,
  val parentWorkflowId: String?,
  val phaseId: String?,
  val invocationId: String?,
  val attempt: Int?,
  val origin: BlockIncidentOrigin,
  val reason: String,
  val failureCode: String?,
  val failureDisposition: String?,
  val throwableType: String?,
  val exitCode: Int?,
  val observedAt: Instant,
  val captureFailureCode: BlockIncidentFailureCode,
)

data class BlockIncidentSpoolEntry(
  val recordId: String,
  val record: BlockIncidentSpoolRecord,
)

/** Outcome of one spool append. */
sealed interface BlockIncidentSpoolAppend {
  data class Appended(val recordId: String) : BlockIncidentSpoolAppend

  data object DroppedFull : BlockIncidentSpoolAppend

  data class Failed(val message: String) : BlockIncidentSpoolAppend
}
