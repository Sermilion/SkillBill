package skillbill.ports.db.model

sealed interface DatabaseCheckpointResult {
  data class Truncated(val walPagesBefore: Int) : DatabaseCheckpointResult

  data object Busy : DatabaseCheckpointResult

  data object Absent : DatabaseCheckpointResult
}
