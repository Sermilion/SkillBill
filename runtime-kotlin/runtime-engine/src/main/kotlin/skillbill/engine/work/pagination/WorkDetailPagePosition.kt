package skillbill.engine.work.pagination

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.ports.idestatus.model.WorkDetailList
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.idestatus.model.WorkDetailResult
import skillbill.ports.work.model.WorkStoreObservation
import java.time.Instant

internal data class WorkDetailPagePosition(
  val observedAt: Instant,
  val offset: Int,
  val observation: WorkStoreObservation? = null,
)

internal fun workDetailPagePosition(
  request: WorkDetailRequest,
  now: Instant,
): WorkDetailPagePosition {
  if (request.pageSize !in 1..WorkPaginationLimits.MAX_PAGE_SIZE) {
    invalidWorkPaginationRequest("Page size must be from 1 to 100.")
  }
  if (request.startOffset !in 0..(Int.MAX_VALUE - request.pageSize)) {
    invalidWorkPaginationRequest("Invalid detail page position.")
  }
  val cursor =
    request.cursor ?: return WorkDetailPagePosition(
      request.observedAt ?: now,
      request.startOffset,
    )
  val decoded = decodeWorkPageCursor(cursor, workDetailCursorSelector(request.id, request.list))
  val offset = decoded.position.toIntOrNull() ?: invalidWorkPaginationRequest("Invalid detail page position.")
  if (offset !in 0..(Int.MAX_VALUE - request.pageSize)) invalidWorkPaginationRequest("Invalid detail page position.")
  return WorkDetailPagePosition(decoded.observedAt, offset, decoded.observation)
}

internal fun workDetailCursorSelector(
  id: String,
  list: WorkDetailList?,
): String = list?.let { "$id|${it.name}" } ?: id

internal fun WorkDetailRequest.includesDetailList(selected: WorkDetailList): Boolean = list == null || list == selected

internal fun workDetailPagedResult(
  result: WorkDetailResult,
  request: WorkDetailRequest,
  position: WorkDetailPagePosition,
): WorkDetailResult {
  fun cursor(list: WorkDetailList?): String =
    encodeWorkPageCursor(
      workDetailCursorSelector(request.id, list),
      position.observedAt,
      (position.offset + request.pageSize).toString(),
      result.observation,
    )
  return result.copy(
    pageSize = request.pageSize,
    nextCursor = result.item?.takeIf { it.hasMore }?.let { cursor(request.list) },
    listCursors = result.item?.moreLists.orEmpty().associateWith(::cursor),
  )
}
