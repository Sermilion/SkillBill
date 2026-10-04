package skillbill.workflow.model.persistence.artifact

import skillbill.contracts.JsonCodec

fun appendBoundedHistoryBySequence(
  existing: List<Any>,
  entry: Any,
  retentionLimit: Int,
): List<Any> {
  val existingMaps =
    existing.map { item ->
      JsonCodec.anyToStringAnyMap(item)
        ?: throw IllegalArgumentException("Bounded history entry must decode to an object.")
    }
  val entryMap =
    JsonCodec.anyToStringAnyMap(entry)
      ?: throw IllegalArgumentException("Bounded history append entry must decode to an object.")
  return (existingMaps + entryMap)
    .sortedBy { item -> item.historySequenceNumber() }
    .takeLast(retentionLimit)
}

private fun Map<String, Any?>.historySequenceNumber(): Int =
  when (val raw = this["sequence_number"]) {
    is Int -> raw
    is Number -> raw.toInt()
    is String -> raw.toIntOrNull() ?: 0
    else -> 0
  }
