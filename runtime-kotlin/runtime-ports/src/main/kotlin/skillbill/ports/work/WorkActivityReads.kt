package skillbill.ports.work

import skillbill.ports.work.model.WorkActivityAttempt
import skillbill.ports.work.model.WorkActivityStop

interface WorkActivityReads {
  fun goalActivityAttempts(
    parentWorkflowId: String,
    offset: Int,
    limit: Int,
  ): List<WorkActivityAttempt>

  fun goalActivityStops(
    parentWorkflowId: String,
    offset: Int,
    limit: Int,
  ): List<WorkActivityStop>
}
