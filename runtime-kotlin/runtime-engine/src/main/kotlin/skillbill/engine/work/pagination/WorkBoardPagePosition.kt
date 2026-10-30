package skillbill.engine.work.pagination

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.work.model.WorkStoreObservation
import java.time.Instant
import java.util.Locale

internal data class WorkBoardPagePosition(
  val observedAt: Instant,
  val afterKey: String?,
  val observation: WorkStoreObservation? = null,
)

internal fun workBoardPagePosition(
  request: WorkBoardRequest,
  now: Instant,
): WorkBoardPagePosition {
  if (request.pageSize !in 1..WorkPaginationLimits.MAX_PAGE_SIZE) {
    invalidWorkPaginationRequest("Page size must be from 1 to 100.")
  }
  val cursor =
    request.cursor ?: return WorkBoardPagePosition(
      request.observedAt ?: now,
      null,
    )
  val decoded = decodeWorkPageCursor(cursor, requestScope(request))
  return WorkBoardPagePosition(decoded.observedAt, decoded.position, decoded.observation)
}

internal fun workBoardNextCursor(
  request: WorkBoardRequest,
  observedAt: Instant,
  afterKey: String,
  observation: WorkStoreObservation? = null,
): String = encodeWorkPageCursor(requestScope(request), observedAt, afterKey, observation)

private fun requestScope(request: WorkBoardRequest): String =
  request.filter?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)?.let { filter ->
    "${request.finishedWithin.wireValue}\n$filter"
  } ?: request.finishedWithin.wireValue
