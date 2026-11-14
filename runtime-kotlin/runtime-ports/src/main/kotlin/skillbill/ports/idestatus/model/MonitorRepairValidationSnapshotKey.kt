package skillbill.ports.idestatus.model

data class MonitorRepairValidationSnapshotKey(
  val ownerId: String,
  val cycle: Int,
  val phaseId: String,
)
