package skillbill.engine.work.pagination

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.ports.idestatus.model.StandalonePhaseExecutionAnchor
import skillbill.ports.idestatus.model.WorkDetailList
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.idestatus.model.WorkDetailResult
import skillbill.ports.work.model.WorkGoalDependencyAnchor
import skillbill.ports.work.model.WorkStoreObservation
import java.time.Instant

/**
 * The identity of the last item a list page read, so the next page continues strictly after it whatever was inserted or
 * removed since. Each list has its own anchor kind; one list's anchor is never read as another's.
 */
internal sealed interface WorkDetailAnchor {
  val list: WorkDetailList

  data class Execution(val anchor: StandalonePhaseExecutionAnchor) : WorkDetailAnchor {
    override val list = WorkDetailList.EXECUTIONS
  }

  data class Subtask(override val list: WorkDetailList, val afterSubtaskId: Int) : WorkDetailAnchor

  data class Dependency(val anchor: WorkGoalDependencyAnchor) : WorkDetailAnchor {
    override val list = WorkDetailList.DEPENDENCIES
  }

  data class Phase(val afterPhaseId: String) : WorkDetailAnchor {
    override val list = WorkDetailList.PHASES
  }
}

/**
 * [anchor] is the item the page continues after, null for the head of a list. [offset] is only the page's position hint,
 * and the head page's start when the caller gives a plain [WorkDetailRequest.startOffset].
 */
internal data class WorkDetailPagePosition(
  val observedAt: Instant,
  val offset: Int,
  val observation: WorkStoreObservation? = null,
  val anchor: WorkDetailAnchor? = null,
)

private const val POSITION_SEPARATOR = "|"
private const val ANCHOR_PARTS = 3
private const val OFFSET_AND_LIST_PARTS = 2

/** The identity string of an anchor: `<list>|<field>|<field>`; it carries no observation fence. */
internal fun WorkDetailAnchor.encode(): String =
  when (this) {
    is WorkDetailAnchor.Execution -> listOf(list.name, anchor.runSequence, anchor.invocationId)
    is WorkDetailAnchor.Subtask -> listOf(list.name, afterSubtaskId.toString())
    is WorkDetailAnchor.Dependency -> listOf(list.name, anchor.subtaskId.toString(), anchor.dependencyId.toString())
    is WorkDetailAnchor.Phase -> listOf(list.name, afterPhaseId)
  }.joinToString(POSITION_SEPARATOR)

/** Decodes [value] as an anchor of exactly [expected]; a malformed value or another list's anchor is rejected. */
internal fun decodeWorkDetailAnchor(
  value: String,
  expected: WorkDetailList?,
): WorkDetailAnchor {
  val parts = value.split(POSITION_SEPARATOR, limit = ANCHOR_PARTS)
  val list = WorkDetailList.entries.firstOrNull { it.name == parts.first() }
  if (list == null || list != expected) invalidWorkPaginationRequest("Invalid detail page position.")
  val fields = parts.drop(1)
  if (fields.any(String::isEmpty)) invalidWorkPaginationRequest("Invalid detail page position.")
  return when (list) {
    WorkDetailList.EXECUTIONS ->
      fields.takeIf { it.size == 2 }?.let { WorkDetailAnchor.Execution(StandalonePhaseExecutionAnchor(it[0], it[1])) }
    WorkDetailList.SUBTASKS, WorkDetailList.VALIDATION ->
      fields.singleOrNull()?.toIntOrNull()?.let { WorkDetailAnchor.Subtask(list, it) }
    WorkDetailList.DEPENDENCIES ->
      fields.takeIf { it.size == 2 }?.let { pair ->
        val subtask = pair[0].toIntOrNull()
        val dependency = pair[1].toIntOrNull()
        if (subtask == null || dependency == null) {
          null
        } else {
          WorkDetailAnchor.Dependency(WorkGoalDependencyAnchor(subtask, dependency))
        }
      }
    WorkDetailList.PHASES -> fields.singleOrNull()?.let { WorkDetailAnchor.Phase(it) }
  } ?: invalidWorkPaginationRequest("Invalid detail page position.")
}

/** A cursor position is `<offset hint>` for a head page, else `<offset hint>|<anchor>`. */
private fun encodePosition(
  offset: Int,
  anchor: WorkDetailAnchor?,
): String = if (anchor == null) offset.toString() else "$offset$POSITION_SEPARATOR${anchor.encode()}"

private fun decodePosition(
  position: String,
  list: WorkDetailList?,
): Pair<Int, WorkDetailAnchor?> {
  val parts = position.split(POSITION_SEPARATOR, limit = OFFSET_AND_LIST_PARTS)
  val offset = parts.first().toIntOrNull() ?: invalidWorkPaginationRequest("Invalid detail page position.")
  return offset to parts.getOrNull(1)?.let { decodeWorkDetailAnchor(it, list) }
}

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
      anchor = request.anchor?.let { decodeWorkDetailAnchor(it, request.list) },
    )
  if (request.anchor != null) invalidWorkPaginationRequest("A cursor and an anchor cannot both be given.")
  val decoded = decodeWorkPageCursor(cursor, workDetailCursorSelector(request.id, request.list))
  val (offset, anchor) = decodePosition(decoded.position, request.list)
  if (offset !in 0..(Int.MAX_VALUE - request.pageSize)) invalidWorkPaginationRequest("Invalid detail page position.")
  return WorkDetailPagePosition(decoded.observedAt, offset, decoded.observation, anchor)
}

internal fun workDetailCursorSelector(
  id: String,
  list: WorkDetailList?,
): String = list?.let { "$id|${it.name}" } ?: id

internal fun WorkDetailRequest.includesDetailList(selected: WorkDetailList): Boolean = list == null || list == selected

/**
 * Builds each list's cursor from its own last-read item ([nextAnchors]); an aggregate request has no single list to
 * anchor, so its next cursor advances every list by offset. The item's list anchors record the identity each list page
 * started after, so a refresh can re-read that page without a store-fenced cursor.
 */
internal fun workDetailPagedResult(
  result: WorkDetailResult,
  request: WorkDetailRequest,
  position: WorkDetailPagePosition,
  nextAnchors: Map<WorkDetailList, WorkDetailAnchor>,
): WorkDetailResult {
  fun cursor(list: WorkDetailList?): String =
    encodeWorkPageCursor(
      workDetailCursorSelector(request.id, list),
      position.observedAt,
      encodePosition(position.offset + request.pageSize, list?.let(nextAnchors::get)),
      result.observation,
    )
  val item = result.item
  val startAnchors = position.anchor?.let { mapOf(it.list to it.encode()) }.orEmpty()
  return result.copy(
    pageSize = request.pageSize,
    nextCursor = item?.takeIf { it.hasMore }?.let { cursor(request.list) },
    listCursors = item?.moreLists.orEmpty().associateWith(::cursor),
    item = item?.copy(listAnchors = startAnchors),
  )
}
