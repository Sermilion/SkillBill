package skillbill.engine.work.pagination

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.ports.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.WorkBoardRequest
import java.nio.file.Path
import java.time.Instant

private const val BOARD_TIMESTAMP_KEY_WIDTH = 20

internal data class WorkBoardCandidate(
  val candidate: IdeStatusCandidate,
  val repositoryIdentity: String,
  val repositoryRoot: Path,
) {
  val pagingKey: String
    get() =
      listOf(
        if (candidate.lifecycleState in
          setOf(
            IdeStatusLifecycleState.TERMINAL,
            IdeStatusLifecycleState.FAILED,
          )
        ) {
          "0"
        } else {
          "1"
        },
        (candidate.startedAt?.toEpochMilli() ?: 0L).toString().padStart(BOARD_TIMESTAMP_KEY_WIDTH, '0'),
        repositoryIdentity,
        candidate.workflowFamily.wireValue,
        candidate.execution?.invocationId ?: candidate.workflowId,
      ).joinToString("\n")
}

internal data class WorkBoardPagePosition(val observedAt: Instant, val afterKey: String?)

internal fun workBoardPagePosition(
  request: WorkBoardRequest,
  now: Instant,
): WorkBoardPagePosition {
  if (request.pageSize !in 1..WorkPaginationLimits.MAX_PAGE_SIZE) {
    invalidWorkPaginationRequest("Page size must be from 1 to 100.")
  }
  val cursor =
    request.cursor ?: return WorkBoardPagePosition(
      Instant.ofEpochMilli((request.observedAt ?: now).toEpochMilli()),
      null,
    )
  val decoded = decodeWorkPageCursor(cursor, request.finishedWithin.wireValue)
  return WorkBoardPagePosition(decoded.observedAt, decoded.position)
}

internal fun workBoardNextCursor(
  request: WorkBoardRequest,
  observedAt: Instant,
  afterKey: String,
): String = encodeWorkPageCursor(request.finishedWithin.wireValue, observedAt, afterKey)
