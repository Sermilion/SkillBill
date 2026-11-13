package skillbill.ports.idestatus.monitor

import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.ports.idestatus.model.MonitorRepairValidationSnapshotKey

interface MonitorRepairValidationSnapshotRepository {
  /**
   * Persists [snapshot] for [key] when none is stored yet.
   *
   * @return `false` when an accepted snapshot is already bound to [key].
   */
  fun accept(
    key: MonitorRepairValidationSnapshotKey,
    snapshot: ProjectCheckCommandSnapshot,
  ): Boolean

  fun load(key: MonitorRepairValidationSnapshotKey): ProjectCheckCommandSnapshot?

  fun latestCycle(
    ownerId: String,
    phaseId: String,
  ): Int?
}
