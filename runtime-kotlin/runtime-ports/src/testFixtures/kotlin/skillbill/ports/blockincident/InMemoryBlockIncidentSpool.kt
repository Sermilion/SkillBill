package skillbill.ports.blockincident

import skillbill.ports.blockincident.model.BlockIncidentSpoolAppend
import skillbill.ports.blockincident.model.BlockIncidentSpoolEntry
import skillbill.ports.blockincident.model.BlockIncidentSpoolRecord

class InMemoryBlockIncidentSpool : BlockIncidentSpool {
  private val records = linkedMapOf<String, BlockIncidentSpoolRecord>()
  var failAppend: Boolean = false

  override fun append(record: BlockIncidentSpoolRecord): BlockIncidentSpoolAppend =
    synchronized(this) {
      if (failAppend) return BlockIncidentSpoolAppend.Failed("Configured append failure.")
      val recordId = "%020d_%s".format(record.observedAt.toEpochMilli(), record.occurrenceToken)
      if (recordId !in records && records.size >= MAX_RECORDS) return BlockIncidentSpoolAppend.DroppedFull
      val retained = record.copy(reason = record.reason.take(MAX_REASON_CHARS))
      records[recordId] = retained
      BlockIncidentSpoolAppend.Appended(recordId)
    }

  override fun pending(limit: Int): List<BlockIncidentSpoolEntry> =
    synchronized(this) {
      require(limit > 0)
      records.entries.sortedBy { it.key }.take(limit).map { BlockIncidentSpoolEntry(it.key, it.value) }
    }

  override fun remove(recordId: String) {
    synchronized(this) { records.remove(recordId) }
  }

  private companion object {
    const val MAX_RECORDS = 64
    const val MAX_REASON_CHARS = 8192
  }
}
