package skillbill.engine.work

import skillbill.engine.work.pagination.AggregateSkipDiagnostics
import skillbill.engine.work.pagination.WorkBoardPagePosition
import skillbill.engine.work.pagination.invalidWorkPaginationRequest
import skillbill.engine.work.pagination.workBoardNextCursor
import skillbill.ports.idestatus.model.WorkBoardDiagnostic
import skillbill.ports.idestatus.model.WorkBoardItem
import skillbill.ports.idestatus.model.WorkBoardReadModelKey
import skillbill.ports.idestatus.model.WorkBoardReadModelPage
import skillbill.ports.idestatus.model.WorkBoardReadModelPageQuery
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkBoardResult
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.work.model.WORK_BOARD_CANDIDATE_BATCH_SIZE
import skillbill.ports.work.model.WorkBoardCandidateQuery
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal object WorkBoardReadModelRead {
  fun read(
    projection: WorkBoardItemProjection,
    unitOfWork: UnitOfWork,
    request: WorkBoardRequest,
    position: WorkBoardPagePosition,
  ): WorkBoardResult {
    val observedAt = position.observedAt
    val observation = unitOfWork.workList.observation()
    if (request.cursor != null && position.observation != observation) {
      invalidWorkPaginationRequest("Work changed. Refresh the board.")
    }
    val after = position.afterKey?.let(::decodePosition)
    val finishedFrom = observedAt.minus(request.finishedWithin.duration)
    val filter = normalizeFilter(request.filter)
    val diagnostics = mutableListOf<WorkBoardDiagnostic>()
    val scanRequest = ScanRequest(observedAt, finishedFrom, filter, diagnostics)
    val result =
      if (unitOfWork.workBoardReadModel.isComplete() && unitOfWork.workBoardReadModel.pendingInvalidation() == null) {
        readIndexed(projection, unitOfWork, request.pageSize, after, scanRequest)
      } else {
        val scanned = scanCandidates(projection, unitOfWork, scanRequest)
        val ordered = scanned.items.sortedWith { left, right -> PROJECTED_ORDER.compare(left.position, right.position) }
        val eligible = ordered.filter { after == null || PROJECTED_ORDER.compare(it.position, after) > 0 }
        BoardPage(eligible.take(request.pageSize), eligible.size > request.pageSize, scanned.examined)
      }
    val page = result.items
    val hasNext = result.hasNext
    val examined = result.examined
    val nextCursor =
      page.lastOrNull()?.takeIf { hasNext }?.let {
        workBoardNextCursor(request, observedAt, encodePosition(it.position), observation)
      }
    return WorkBoardResult(
      observedAt = observedAt,
      finishedWithin = request.finishedWithin,
      items =
        page.map {
          it.item.copy(observation = observation, databaseFileObservation = unitOfWork.databaseFileObservation)
        },
      diagnostics = diagnostics,
      pageSize = request.pageSize,
      nextCursor = nextCursor,
      observation = observation,
      candidatesExamined = examined,
    )
  }

  private fun readIndexed(
    projection: WorkBoardItemProjection,
    unitOfWork: UnitOfWork,
    pageSize: Int,
    after: ProjectedBoardPosition?,
    request: ScanRequest,
  ): BoardPage {
    val indexed = mutableListOf<ProjectedBoardItem>()
    var indexedAfter =
      after?.let {
        WorkBoardReadModelKey(it.displayPriority, formatReadModelInstant(it.updatedAt), it.workId)
      }
    var examined = 0
    var moreRows = true
    var hasNext = false
    while (moreRows && indexed.size <= pageSize) {
      val limit = pageSize + 1 - indexed.size
      val entries =
        unitOfWork.workBoardReadModel.page(
          WorkBoardReadModelPageQuery(request.finishedFrom, request.filter, indexedAfter, limit),
        ).entries
      examined += entries.size
      moreRows = entries.size == limit
      entries.mapNotNull { entry -> indexedItem(projection, unitOfWork, entry, request) }
        .filter { after == null || PROJECTED_ORDER.compare(it.position, after) > 0 }
        .let(indexed::addAll)
      indexedAfter = entries.lastOrNull()?.key ?: break
      hasNext = indexed.size > pageSize
    }
    val ordered = indexed.sortedWith { left, right -> PROJECTED_ORDER.compare(left.position, right.position) }
    return BoardPage(ordered.take(pageSize), hasNext, examined)
  }

  private fun indexedItem(
    projection: WorkBoardItemProjection,
    unitOfWork: UnitOfWork,
    entry: WorkBoardReadModelPage.Entry,
    request: ScanRequest,
  ): ProjectedBoardItem? {
    val row = (entry as? WorkBoardReadModelPage.Entry.Decoded)?.row
    val needsProjection =
      row == null || row.reprojectAt?.let { !it.isAfter(request.observedAt) } == true ||
        row.membershipDeadline?.let { !it.isAfter(request.observedAt) } == true
    val item =
      if (needsProjection) {
        projection.projectBoardCandidate(
          unitOfWork,
          entry.workId,
          BoardProjectionWindow(request.observedAt, request.finishedFrom),
          request.diagnostics,
          AggregateSkipDiagnostics(),
        )
      } else {
        row.item
      } ?: return null
    val projectedId = projection.boardId(item)
    if (projectedId == null || projectedId != entry.workId || '\n' in projectedId) {
      invalidWorkPaginationRequest("Work changed. Refresh the board.")
    }
    if (item.finishedAt?.isAfter(
        request.observedAt,
      ) == true || !projection.matchesFilter(item, request.filter)
    ) {
      return null
    }
    return ProjectedBoardItem(
      item,
      projectedId,
      ProjectedBoardPosition(item.displayPriority, item.snapshot.updatedAt, entry.workId),
    )
  }

  private data class BoardPage(val items: List<ProjectedBoardItem>, val hasNext: Boolean, val examined: Int)

  private fun scanCandidates(
    projection: WorkBoardItemProjection,
    unitOfWork: UnitOfWork,
    request: ScanRequest,
  ): BoardScanResult {
    val observedAt = request.observedAt
    val finishedFrom = request.finishedFrom
    val filter = request.filter
    val diagnostics = request.diagnostics
    val skipDiagnostics = AggregateSkipDiagnostics()
    val projected = mutableListOf<ProjectedBoardItem>()
    val seenIds = mutableSetOf<String>()
    var candidateCursor: String? = null
    var examined = 0

    while (true) {
      val rows =
        unitOfWork.workList.boardCandidates(
          WorkBoardCandidateQuery(
            finishedFrom = finishedFrom,
            observedAt = observedAt,
            afterKey = candidateCursor,
            limit = WORK_BOARD_CANDIDATE_BATCH_SIZE,
          ),
        )
      if (rows.isEmpty()) break
      candidateCursor = rows.last().key
      for (row in rows) {
        if (seenIds.add(row.id)) {
          examined++
          val item =
            projection.projectBoardCandidate(
              unitOfWork,
              row.id,
              BoardProjectionWindow(observedAt, finishedFrom),
              diagnostics,
              skipDiagnostics,
            )
          if (item != null) {
            val projectedId = projection.boardId(item)
            if (projectedId == null || projectedId != row.id || '\n' in projectedId) {
              invalidWorkPaginationRequest("Work changed. Refresh the board.")
            }
            if (item.finishedAt?.isAfter(observedAt) != true && projection.matchesFilter(item, filter)) {
              projected += ProjectedBoardItem(item, projectedId)
            }
          }
        }
      }
    }

    return BoardScanResult(projected, examined)
  }

  private data class BoardScanResult(val items: List<ProjectedBoardItem>, val examined: Int)

  private data class ScanRequest(
    val observedAt: Instant,
    val finishedFrom: Instant,
    val filter: String?,
    val diagnostics: MutableList<WorkBoardDiagnostic>,
  )

  private fun normalizeFilter(value: String?): String? =
    value?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)

  private fun encodePosition(position: ProjectedBoardPosition): String =
    listOf(
      PROJECTED_CURSOR_PREFIX.trimEnd('\n'),
      position.displayPriority.toString(),
      formatReadModelInstant(position.updatedAt),
      position.workId,
    )
      .joinToString("\n")

  private fun decodePosition(value: String): ProjectedBoardPosition {
    val parts = value.split('\n', limit = POSITION_FIELD_COUNT)
    if (!validPositionFields(parts)) {
      invalidWorkPaginationRequest("Work changed. Refresh the board.")
    }
    val priority = parts[1].toIntOrNull() ?: invalidWorkPaginationRequest("Work changed. Refresh the board.")
    if (priority.toString() != parts[1]) invalidWorkPaginationRequest("Work changed. Refresh the board.")
    val updatedAt =
      try {
        Instant.parse(parts[2]).takeIf { it.toString() == parts[2] || formatReadModelInstant(it) == parts[2] }
          ?: invalidWorkPaginationRequest("Work changed. Refresh the board.")
      } catch (_: DateTimeParseException) {
        invalidWorkPaginationRequest("Work changed. Refresh the board.")
      }
    return ProjectedBoardPosition(priority, updatedAt, parts[POSITION_WORK_ID_INDEX])
  }

  private fun validPositionFields(parts: List<String>): Boolean =
    parts.size == POSITION_FIELD_COUNT && parts[0] == PROJECTED_CURSOR_PREFIX.trimEnd('\n') &&
      validPositionWorkId(parts[POSITION_WORK_ID_INDEX])

  private fun validPositionWorkId(id: String): Boolean = id.isNotBlank() && '\n' !in id

  private fun formatReadModelInstant(value: Instant): String = READ_MODEL_INSTANT_FORMATTER.format(value)

  private data class ProjectedBoardItem(
    val item: WorkBoardItem,
    val workId: String,
    private val indexedPosition: ProjectedBoardPosition? = null,
  ) {
    val position: ProjectedBoardPosition
      get() = indexedPosition ?: ProjectedBoardPosition(item.displayPriority, item.snapshot.updatedAt, workId)
  }

  private data class ProjectedBoardPosition(val displayPriority: Int, val updatedAt: Instant, val workId: String)

  private val PROJECTED_ORDER =
    compareBy<ProjectedBoardPosition> { it.displayPriority }
      .thenByDescending { it.updatedAt }
      .thenBy { it.workId }

  private const val PROJECTED_CURSOR_PREFIX = "p\n"
  private const val POSITION_FIELD_COUNT = 4
  private const val POSITION_WORK_ID_INDEX = 3
  private val READ_MODEL_INSTANT_FORMATTER =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC)
}
