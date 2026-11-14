package skillbill.ports.idestatus

import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.ports.idestatus.model.MonitorRepairValidationSnapshotKey
import skillbill.ports.idestatus.monitor.MonitorRepairValidationSnapshotRepository
import java.util.concurrent.ConcurrentHashMap

class InMemoryMonitorRepairValidationSnapshotRepository : MonitorRepairValidationSnapshotRepository {
  private val snapshots =
    ConcurrentHashMap<MonitorRepairValidationSnapshotKey, ProjectCheckCommandSnapshot>()

  override fun accept(
    key: MonitorRepairValidationSnapshotKey,
    snapshot: ProjectCheckCommandSnapshot,
  ): Boolean {
    val frozen = snapshot.frozen()
    return snapshots.putIfAbsent(key, frozen) == null
  }

  override fun load(key: MonitorRepairValidationSnapshotKey): ProjectCheckCommandSnapshot? = snapshots[key]

  override fun latestCycle(
    ownerId: String,
    phaseId: String,
  ): Int? =
    snapshots.keys
      .filter { it.ownerId == ownerId && it.phaseId == phaseId }
      .maxOfOrNull { it.cycle }
}

object EmptyMonitorRepairValidationSnapshotRepository : MonitorRepairValidationSnapshotRepository {
  override fun accept(
    key: MonitorRepairValidationSnapshotKey,
    snapshot: ProjectCheckCommandSnapshot,
  ): Boolean = true

  override fun load(key: MonitorRepairValidationSnapshotKey): ProjectCheckCommandSnapshot? = null

  override fun latestCycle(
    ownerId: String,
    phaseId: String,
  ): Int? = null
}
