package skillbill.ports.blockincident

import skillbill.contracts.JsonCodec

class InMemoryBlockIncidentSpool : BlockIncidentSpool {
  private val records = linkedMapOf<String, BlockIncidentSpoolRecord>()
  var failAppend: Boolean = false

  override fun append(record: BlockIncidentSpoolRecord): BlockIncidentSpoolAppend = synchronized(this) {
    if (failAppend) return BlockIncidentSpoolAppend.Failed("Configured append failure.")
    val recordId = "${record.occurrenceToken}_${record.observedAt.toEpochMilli()}"
    if (recordId !in records && records.size >= MAX_RECORDS) return BlockIncidentSpoolAppend.DroppedFull
    val retained = record.copy(reason = record.reason.take(MAX_REASON_CHARS))
    if (JsonCodec.valueToJsonString(retained).toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES) {
      return BlockIncidentSpoolAppend.Failed("Encoded spool record exceeds the size limit.")
    }
    records[recordId] = retained
    BlockIncidentSpoolAppend.Appended(recordId)
  }

  override fun pending(limit: Int): List<BlockIncidentSpoolEntry> = synchronized(this) {
    require(limit > 0)
    records.entries.take(limit).map { BlockIncidentSpoolEntry(it.key, it.value) }
  }

  override fun remove(recordId: String) {
    synchronized(this) { records.remove(recordId) }
  }

  private companion object {
    const val MAX_RECORDS = 64
    const val MAX_RECORD_BYTES = 16 * 1024
    const val MAX_REASON_CHARS = 8192
  }
}
