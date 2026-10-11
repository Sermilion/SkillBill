package skillbill.engine.work.pagination

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.ports.idestatus.model.WorkDetailRequest
import java.time.Instant

internal data class WorkDetailPagePosition(val observedAt: Instant, val offset: Int)

internal fun workDetailPagePosition(
  request: WorkDetailRequest,
  now: Instant,
): WorkDetailPagePosition {
  if (request.pageSize !in 1..WorkPaginationLimits.MAX_PAGE_SIZE) {
    invalidWorkPaginationRequest("Page size must be from 1 to 100.")
  }
  val cursor =
    request.cursor ?: return WorkDetailPagePosition(
      Instant.ofEpochMilli((request.observedAt ?: now).toEpochMilli()),
      0,
    )
  val decoded = decodeWorkPageCursor(cursor, request.id)
  val offset = decoded.position.toIntOrNull() ?: invalidWorkPaginationRequest("Invalid detail page position.")
  if (offset !in 0..(Int.MAX_VALUE - request.pageSize)) invalidWorkPaginationRequest("Invalid detail page position.")
  return WorkDetailPagePosition(decoded.observedAt, offset)
}
