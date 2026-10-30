package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.WorkDependency
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.status.statusKind
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

private const val BLOCKED = "blocked"
private const val SUBTASK_SPEC_PREFIX = "spec_subtask_"

/**
 * The window's subtask selections, one per work item, so a choice survives paging between items (J, K, the pager), which
 * replaces the detail and its ViewModel. The choices belong to one database session: item ids repeat across databases, so a
 * new session starts empty. UI state only; nothing here reaches the runtime.
 */
@Inject
@SingleIn(WindowScope::class)
class SubtaskSelections(private val session: DatabaseSessionController) {
  private val choices = mutableMapOf<String, SubtaskChoice>()
  private var sessionId: Long? = null
  private var carryListFocus = false

  fun get(itemId: String): SubtaskChoice = current()[itemId] ?: SubtaskChoice()

  fun set(itemId: String, choice: SubtaskChoice) {
    current()[itemId] = choice
  }

  /**
   * main.js keeps keyboard focus in the Subtasks list across a J/K page (render() refocuses the `data-zone`). The detail being
   * left records it here; the next detail takes it once, and drops it when it has no index.
   */
  fun carryListFocus() {
    carryListFocus = true
  }

  fun takeListFocus(): Boolean = carryListFocus.also { carryListFocus = false }

  private fun current(): MutableMap<String, SubtaskChoice> {
    val open = (session.state.value as? DatabaseSessionState.Open)?.sessionId
    if (open != sessionId) {
      choices.clear()
      sessionId = open
    }
    return choices
  }
}

/** A goal past planning with at least one subtask: the index is selectable and the pane holds the inspector. */
internal fun hasSubtaskIndex(item: WorkItem, detail: Detail): Boolean =
  item.kind == WorkKind.Goal && item.statusKind() != StatusKind.Planning && detail.subtasks.isNotEmpty()

/** The index's rows in runtime order, then Goal finish; empty when the item has no index. */
internal fun subtaskKeys(item: WorkItem, detail: Detail): List<SubtaskKey> =
  if (!hasSubtaskIndex(item, detail)) emptyList() else detail.subtasks.map { SubtaskKey.Subtask(it.id) } + SubtaskKey.GoalFinish

/** The running subtask, or null once the goal finished. */
internal fun currentSubtask(item: WorkItem, detail: Detail): WorkSubtask? =
  if (finishedAt(item) != null) null else detail.subtasks.firstOrNull { it.id.toString() == item.currentSubtaskId }

/** The selection a goal opens with: the blocked subtask, else the current one, else the first. */
internal fun defaultSubtask(item: WorkItem, detail: Detail): SubtaskKey? {
  if (!hasSubtaskIndex(item, detail)) return null
  val subtask = detail.subtasks.firstOrNull { it.status == BLOCKED } ?: currentSubtask(item, detail) ?: detail.subtasks.first()
  return SubtaskKey.Subtask(subtask.id)
}

/** The reader's choice while it still names a row of the index, else the default. */
internal fun selectedSubtask(item: WorkItem, detail: Detail, chosen: SubtaskKey?): SubtaskKey? =
  chosen?.takeIf { it in subtaskKeys(item, detail) } ?: defaultSubtask(item, detail)

/** A subtask's own edges, or the paged dependency list's edges for it when the subtask row carries none. */
internal fun dependenciesOf(detail: Detail, subtaskId: Int): List<WorkDependency> {
  val own = detail.subtasks.firstOrNull { it.id == subtaskId }?.dependencies.orEmpty()
  val edges = own.ifEmpty { detail.dependencies.filter { it.subtaskId == subtaskId }.map { it.dependency } }
  return edges.distinctBy { it.subtaskId }
}

/** The reverse edges: every subtask that depends on [subtaskId], in index order, marked optional as that edge is. */
internal fun neededBy(detail: Detail, subtaskId: Int): List<WorkDependency> = detail.subtasks.mapNotNull { other ->
  dependenciesOf(detail, other.id).firstOrNull { it.subtaskId == subtaskId }?.let { WorkDependency(other.id, it.optional, it.skipped) }
}

/** The subtask's spec file: the plan report's path for it, else a bundle file named `spec_subtask_<id>.md`. */
internal fun subtaskSpecPath(detail: Detail, subtaskId: Int): String? {
  val report = detail.goalSpecBundle ?: return null
  return report.subtasks.firstOrNull { it.id == subtaskId }?.specPath?.takeIf { it.isNotBlank() }
    ?: report.bundleFiles.firstOrNull { fileName(it) == "$SUBTASK_SPEC_PREFIX$subtaskId.md" }
}

/** The goal-level spec files for the pane: the bundle in its display order without any subtask spec, which opens from the inspector. */
internal fun goalSpecFiles(detail: Detail): List<String> {
  val report = detail.goalSpecBundle ?: return emptyList()
  val subtaskSpecs = report.subtasks.map { it.specPath }.toSet()
  return specBundlePaths(report).filter { it !in subtaskSpecs && !fileName(it).startsWith(SUBTASK_SPEC_PREFIX) }
}

/** The two-digit number the index and chips show. */
internal fun subtaskNumber(id: Int): String = id.toString().padStart(2, '0')
