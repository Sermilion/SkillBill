package skillbill.engine.work

import me.tatarka.inject.annotations.Inject
import skillbill.engine.migration.RetainedWorkflowProjection
import skillbill.engine.work.failure.IdeStatusProblemSnapshots
import skillbill.engine.work.pagination.invalidWorkPaginationRequest
import skillbill.engine.work.pagination.workBoardNextCursor
import skillbill.engine.work.pagination.workBoardPagePosition
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.migration.AcceptanceCriterionMigrationFailureCode
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.db.RetainedWorkflowReader
import skillbill.ports.db.model.RetainedWorkflowDiagnostic
import skillbill.ports.db.model.RetainedWorkflowRecord
import skillbill.ports.idestatus.model.IdeStatusProblemCode
import skillbill.ports.idestatus.model.WorkBoardDiagnostic
import skillbill.ports.idestatus.model.WorkBoardDiagnosticReason
import skillbill.ports.idestatus.model.WorkBoardItem
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkBoardResult
import skillbill.ports.idestatus.model.WorkLiveSection
import java.time.Instant

@Inject
public class RetainedWorkflowBoardProjection(
  private val retained: RetainedWorkflowReader,
  private val retainedProjection: RetainedWorkflowProjection,
) {
  internal fun board(
    request: WorkBoardRequest,
    observedAt: Instant,
    failure: SkillBillRuntimeException,
  ): WorkBoardResult {
    val position = workBoardPagePosition(request, observedAt)
    val after =
      position.afterKey?.let { key ->
        if (!key.startsWith(RETAINED_CURSOR_PREFIX)) {
          invalidWorkPaginationRequest(
            "Saved data requires recovery. Restart board pagination to view retained records.",
          )
        }
        key.removePrefix(RETAINED_CURSOR_PREFIX)
      }
    val filter = request.filter?.trim().orEmpty()
    val rows =
      retained.read(null).filter { row ->
        (after == null || row.workflowId > after) &&
          listOfNotNull(row.workflowId, row.issueKey, row.workflowName, retainedProjection.repositoryIdentity(row))
            .any { it.contains(filter, ignoreCase = true) }
      }.sortedBy { it.workflowId }.take(request.pageSize + 1)
    val page = rows.take(request.pageSize)
    return WorkBoardResult(
      observedAt = position.observedAt,
      finishedWithin = request.finishedWithin,
      items = page.map { item(it, position.observedAt, failure) },
      diagnostics =
        page.map {
          WorkBoardDiagnostic(
            WorkBoardDiagnosticReason.INCOMPATIBLE_RECORD,
            retainedProjection.repositoryIdentity(it),
            it.workflowId,
          )
        },
      pageSize = request.pageSize,
      nextCursor =
        if (rows.size > page.size) {
          workBoardNextCursor(request, position.observedAt, RETAINED_CURSOR_PREFIX + page.last().workflowId)
        } else {
          null
        },
      candidatesExamined = rows.size,
    )
  }

  internal fun item(
    id: String,
    observedAt: Instant,
    failure: SkillBillRuntimeException,
  ): WorkBoardItem? = retained.read(id).singleOrNull()?.let { item(it, observedAt, failure) }

  internal fun diagnostic(
    id: String,
    failure: SkillBillRuntimeException,
  ): RetainedWorkflowDiagnostic? = retained.read(id).singleOrNull()?.let { retainedProjection.diagnostic(it, failure) }

  internal fun repositoryIdentity(diagnostic: RetainedWorkflowDiagnostic): String? =
    retainedProjection.repositoryIdentity(diagnostic.record)

  internal fun repositoryItem(
    identity: String,
    observedAt: Instant,
    failure: SkillBillRuntimeException,
  ): WorkBoardItem? =
    retained.read(null).firstOrNull { retainedProjection.repositoryIdentity(it) == identity }
      ?.let { item(it, observedAt, failure) }

  internal fun attach(
    items: List<WorkBoardItem>,
    observedAt: Instant,
  ): List<WorkBoardItem> =
    items.map { saved ->
      val snapshot = saved.snapshot
      val problem = snapshot.problem
      if (saved.migrationDiagnostic != null || problem?.code != IdeStatusProblemCode.INCOMPATIBLE_RECORD) {
        saved
      } else {
        val failure =
          SkillBillRuntimeException(
            AcceptanceCriterionMigrationFailureCode.ARTIFACT_MIGRATION_REFUSED,
            problem.message,
          )
        snapshot.workflowId?.let { item(it, observedAt, failure) } ?: saved
      }
    }

  private fun item(
    row: RetainedWorkflowRecord,
    observedAt: Instant,
    failure: SkillBillRuntimeException,
  ): WorkBoardItem {
    return item(retainedProjection.diagnostic(row, failure), observedAt)
  }

  internal fun item(
    diagnostic: RetainedWorkflowDiagnostic,
    observedAt: Instant,
  ): WorkBoardItem {
    val row = diagnostic.record
    val identity =
      retainedProjection.repositoryIdentity(row)?.takeIf { it.isNotBlank() }
        ?: IdeStatusProblemSnapshots.missingRepositoryIdentity(
          observedAt,
          "Retained workflow '${row.workflowId}' has no repository identity. Original source remains available.",
        ).repositoryIdentity
    val snapshot =
      IdeStatusProblemSnapshots.incompatibleRecord(
        identity,
        observedAt,
        diagnostic.guidance,
        row.workflowId,
      )
    return WorkBoardItem(
      snapshot =
        snapshot.copy(
          lifecycleState = IdeStatusLifecycleState.BLOCKED,
          issueKey = row.issueKey,
          title = row.workflowName.ifBlank { row.issueKey ?: row.workflowId },
          problem = requireNotNull(snapshot.problem).copy(details = retainedProjection.details(diagnostic)),
        ),
      repositoryRoot = identity,
      migrationDiagnostic = diagnostic,
      requiresAttention = true,
      liveSection = WorkLiveSection.NEEDS_YOU,
      exactPauseReason = diagnostic.guidance,
    )
  }
}

private const val RETAINED_CURSOR_PREFIX = "retained-source:"
