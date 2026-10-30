package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.LatestActivity
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.WorkItem
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.di.core.RuntimeComponent
import skillbill.engine.work.control.model.WorkRunOptions
import skillbill.ports.idestatus.model.WorkDetailProblemCode
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.idestatus.model.WorkDetailResult

/** One detail read: the refined header with its loaded sections, an item the runtime no longer knows, or a failure. */
internal sealed interface DetailRead {
  data class Loaded(val header: WorkItem, val sections: DetailSections.Loaded) : DetailRead

  data class Activity(val header: WorkItem, val latestActivity: LatestActivity) : DetailRead

  data object Gone : DetailRead

  data class Failed(val problem: ReadProblem) : DetailRead
}

/** The detail reads a repository needs, behind an interface so its triggering can be tested without a runtime. */
internal interface DetailReads {
  /** The detail and its run options in one read block. */
  suspend fun first(id: String): DetailRead

  /** [first] plus, per list the user paged past its head, that list re-read from its head at its loaded size. */
  suspend fun refresh(id: String, extended: Map<DetailList, Int>): DetailRead

  /** The header and activity projection without run options or extended lists. */
  suspend fun activity(id: String): DetailRead

  /** One page of [list] after [cursor]. */
  suspend fun list(id: String, list: DetailList, cursor: String?): DetailRead
}

internal class RuntimeDetailReads(
  private val reads: RuntimeReads,
  private val runtime: RuntimeComponent,
  private val diagnostics: Diagnostics,
  private val sessionId: Long,
) : DetailReads {
  override suspend fun first(id: String) = refresh(id, emptyMap())

  override suspend fun refresh(id: String, extended: Map<DetailList, Int>): DetailRead = read("detail") { component ->
    val result = component.ideStatusService.detail(WorkDetailRequest(id, observedAt = null))
    val options = result.item?.let(component.workControlService::options)
    val head = result.toRead(component, options, sessionId)
    if (head !is DetailRead.Loaded) return@read head
    extended.entries.fold(head) { loaded, (list, count) ->
      var remaining = count
      var cursor: String? = null
      var sections = loaded.sections
      while (remaining > 0) {
        val pageSize = remaining.coerceAtMost(WorkPaginationLimits.MAX_PAGE_SIZE)
        val page = component.ideStatusService.detail(
          WorkDetailRequest(id, pageSize = pageSize, list = list.toRuntime(), cursor = cursor),
        )
        val pageDetail = page.item?.toDetail(page.observedAt) ?: break
        val next = page.listCursors[list.toRuntime()]
        sections = sections.withList(list, pageDetail, next, append = cursor != null)
        val fetched = pageDetail.size(list)
        if (fetched == 0 || next == null || next == cursor) break
        remaining -= fetched
        cursor = next
      }
      loaded.copy(sections = sections)
    }
  }

  override suspend fun activity(id: String): DetailRead = read("detail_activity") { component ->
    val result = component.ideStatusService.detail(WorkDetailRequest(id, includeExtendedLists = false))
    result.toRead(component, options = null, sessionId = sessionId)
      .let { read ->
        if (read is DetailRead.Loaded) DetailRead.Activity(read.header, read.sections.detail.latestActivity) else read
      }
  }

  override suspend fun list(id: String, list: DetailList, cursor: String?): DetailRead = read("detail_list") { component ->
    component.ideStatusService.detail(WorkDetailRequest(id, list = list.toRuntime(), cursor = cursor))
      .toRead(component, options = null, sessionId = sessionId)
  }

  private suspend fun read(name: String, block: (RuntimeComponent) -> DetailRead): DetailRead =
    when (val outcome = readOrProblem(runtime, diagnostics) { reads.read(name, block) }) {
      is Outcome.Success -> outcome.value
      is Outcome.Failure -> DetailRead.Failed(outcome.error)
    }
}

private fun WorkDetailResult.toRead(component: RuntimeComponent, options: WorkRunOptions?, sessionId: Long): DetailRead {
  problem?.let { problem ->
    return if (problem.code == WorkDetailProblemCode.UNKNOWN_WORK_ITEM) DetailRead.Gone else DetailRead.Failed(ReadProblem.Runtime(problem.message))
  }
  val detailItem = item ?: return DetailRead.Gone
  return DetailRead.Loaded(
    header = detailItem.toHeader(options, component.checkedOutBranchSource, sessionId),
    sections = DetailSections.Loaded(
      detail = detailItem.toDetail(observedAt),
      runOptions = options?.toDomain(),
      listCursors = listCursors.entries.associate { it.key.toDomain() to it.value },
      moreLists = detailItem.moreLists.mapTo(mutableSetOf()) { it.toDomain() },
      stale = false,
    ),
  )
}

/** Replaces or appends one list's rows from [page], with that list's next cursor. */
internal fun DetailSections.Loaded.withList(list: DetailList, page: Detail, cursor: String?, append: Boolean): DetailSections.Loaded {
  fun <T> merge(current: List<T>, next: List<T>) = if (append) current + next else next
  val merged = when (list) {
    DetailList.Subtasks -> detail.copy(subtasks = merge(detail.subtasks, page.subtasks))
    DetailList.Phases -> detail.copy(phases = merge(detail.phases, page.phases))
    DetailList.Validation -> detail.copy(
      validation = merge(detail.validation, page.validation),
      phaseResults = merge(detail.phaseResults, page.phaseResults).distinctBy { it.subtaskId },
    )
    DetailList.Executions -> detail.copy(executionHistory = merge(detail.executionHistory, page.executionHistory))
    DetailList.Dependencies -> detail.copy(dependencies = merge(detail.dependencies, page.dependencies))
  }
  return copy(
    detail = merged,
    listCursors = if (cursor == null) listCursors - list else listCursors + (list to cursor),
    moreLists = if (cursor == null) moreLists - list else moreLists + list,
  )
}

/** How many rows of [list] the detail holds. */
internal fun Detail.size(list: DetailList): Int = when (list) {
  DetailList.Subtasks -> subtasks.size
  DetailList.Phases -> phases.size
  DetailList.Validation -> validation.size
  DetailList.Executions -> executionHistory.size
  DetailList.Dependencies -> dependencies.size
}
