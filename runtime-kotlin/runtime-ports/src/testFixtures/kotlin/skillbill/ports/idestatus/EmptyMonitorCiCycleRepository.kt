package skillbill.ports.idestatus

import skillbill.ports.idestatus.model.MonitorCiCycleRecord

object EmptyMonitorCiCycleRepository : MonitorCiCycleRepository {
  override fun upsert(
    workflowId: String,
    record: MonitorCiCycleRecord,
  ) = Unit

  override fun latest(workflowId: String): MonitorCiCycleRecord? = null

  override fun cycles(
    workflowId: String,
    limit: Int,
  ): List<MonitorCiCycleRecord> = emptyList()
}
