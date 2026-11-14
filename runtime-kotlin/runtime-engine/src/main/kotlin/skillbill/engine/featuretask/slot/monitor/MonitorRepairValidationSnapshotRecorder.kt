package skillbill.engine.featuretask.slot.monitor

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.model.MonitorRepairValidationSnapshotKey

@Inject
public class MonitorRepairValidationSnapshotRecorder(
  private val database: DatabaseSessionFactory,
) {
  internal fun accept(
    key: MonitorRepairValidationSnapshotKey,
    snapshot: ProjectCheckCommandSnapshot,
  ): Boolean = database.transaction { unitOfWork -> unitOfWork.monitorRepairValidationSnapshots.accept(key, snapshot) }

  internal fun load(key: MonitorRepairValidationSnapshotKey): ProjectCheckCommandSnapshot? =
    database.read { unitOfWork -> unitOfWork.monitorRepairValidationSnapshots.load(key) }

  internal fun latestCycle(
    ownerId: String,
    phaseId: String,
  ): Int? = database.read { unitOfWork -> unitOfWork.monitorRepairValidationSnapshots.latestCycle(ownerId, phaseId) }
}
