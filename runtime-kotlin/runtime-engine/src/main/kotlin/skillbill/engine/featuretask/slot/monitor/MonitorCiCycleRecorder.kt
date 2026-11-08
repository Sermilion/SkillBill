package skillbill.engine.featuretask.slot.monitor

import me.tatarka.inject.annotations.Inject
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.model.MonitorCiCycleRecord

@Inject
public class MonitorCiCycleRecorder(
  private val database: DatabaseSessionFactory,
) {
  internal fun record(
    workflowId: String,
    record: MonitorCiCycleRecord,
  ) {
    database.transaction { unitOfWork -> unitOfWork.monitorCiCycles.upsert(workflowId, record) }
  }

  internal fun latest(workflowId: String): MonitorCiCycleRecord? =
    database.read { unitOfWork -> unitOfWork.monitorCiCycles.latest(workflowId) }
}
