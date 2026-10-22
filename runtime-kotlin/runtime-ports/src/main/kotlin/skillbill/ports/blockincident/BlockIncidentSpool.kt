package skillbill.ports.blockincident

import skillbill.ports.blockincident.model.BlockIncidentSpoolAppend
import skillbill.ports.blockincident.model.BlockIncidentSpoolEntry
import skillbill.ports.blockincident.model.BlockIncidentSpoolRecord

/**
 * Bounded store for block observations that could not reach the database. It keeps only facts the producer held,
 * never database evidence, so the next writable entry can create or link the incident from them.
 */
interface BlockIncidentSpool {
  /** Stores one record; a full spool keeps its existing records and refuses the new one. */
  fun append(record: BlockIncidentSpoolRecord): BlockIncidentSpoolAppend

  /** Returns up to [limit] stored records, oldest first; records that cannot be read are discarded with a warning. */
  fun pending(limit: Int): List<BlockIncidentSpoolEntry>

  /** Deletes one stored record after it has been imported. */
  fun remove(recordId: String)
}
