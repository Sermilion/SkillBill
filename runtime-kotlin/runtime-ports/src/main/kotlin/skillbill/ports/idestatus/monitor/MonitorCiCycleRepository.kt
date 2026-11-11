package skillbill.ports.idestatus.monitor

import skillbill.ports.idestatus.model.MonitorCiCycleRecord

/**
 * Durable goal monitor cycle records keyed by `(workflowId, cycle)`.
 *
 * Cycle numbers restart at 1 when a goal monitor run restarts, so they are not unique across runs.
 * [latest] returns the row with the greatest `startedAt`, ties broken by the greatest cycle.
 * [cycles] selects the [limit] rows with the greatest `startedAt` and returns them ascending by
 * `startedAt`, then cycle; within one monitor run that is ascending cycle order.
 */
interface MonitorCiCycleRepository {
  fun upsert(
    workflowId: String,
    record: MonitorCiCycleRecord,
  )

  fun latest(workflowId: String): MonitorCiCycleRecord?

  fun cycles(
    workflowId: String,
    limit: Int,
  ): List<MonitorCiCycleRecord>
}
