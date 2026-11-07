package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ScopeCommit
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.ScopeKind
import dev.skillbill.desktop.core.domain.model.ScopeRead
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.normalizeRange
import dev.skillbill.desktop.core.domain.model.normalizeCommits
import dev.skillbill.desktop.core.domain.model.resolveScope
import dev.skillbill.desktop.core.domain.model.availableScopes
import dev.skillbill.desktop.core.domain.model.selectableSubtaskIds
import dev.skillbill.desktop.core.domain.model.selectedSubtaskIds
import dev.skillbill.desktop.core.domain.model.spannedCommits
import dev.skillbill.desktop.core.domain.model.subtaskColumns
import dev.skillbill.desktop.core.domain.model.scopeCommits
import kotlin.math.abs

/**
 * The range a drag from [fromId] to [toId] selects: the end is the selectable subtask nearest [toId] between the two, so a drag into
 * not-started subtasks stops at the last one with a start commit. Null when [fromId] is not selectable.
 */
internal fun dragRange(target: ChangeScopeTarget, fromId: Int, toId: Int): ChangeScope.Subtasks? {
  val selectable = selectableSubtaskIds(target.subtasks)
  if (fromId !in selectable) return null
  val span = minOf(fromId, toId)..maxOf(fromId, toId)
  val end = target.subtasks.filter { it.id in selectable && it.id in span }.minByOrNull { abs(it.id - toId) }?.id ?: fromId
  return normalizeRange(target, fromId, end)
}

internal fun commitSpan(commits: List<ScopeCommit>, a: String, b: String): List<String> {
  val first = commits.indexOfFirst { it.sha == a }
  val last = commits.indexOfFirst { it.sha == b }
  if (first < 0 || last < 0) return emptyList()
  return commits.subList(minOf(first, last), maxOf(first, last) + 1).map { it.sha }
}

internal fun commitStepTarget(commits: List<ScopeCommit>, scope: ChangeScope, forward: Boolean): String? {
  if (commits.isEmpty()) return null
  val selected = (scope as? ChangeScope.Commits)?.shas?.mapNotNull { sha -> commits.indexOfFirst { it.sha == sha }.takeIf { it >= 0 } }
  val index = when {
    selected.isNullOrEmpty() -> if (forward) 0 else commits.lastIndex
    forward -> (selected.max() + 1).takeIf { it <= commits.lastIndex } ?: return null
    else -> (selected.min() - 1).takeIf { it >= 0 } ?: return null
  }
  return commits[index].sha
}

internal fun filterCommits(commits: List<ScopeCommit>, subtasks: List<ScopeSubtask>, query: String): List<ScopeCommit> {
  val needle = query.trim()
  if (needle.isEmpty()) return commits
  val byId = subtasks.associateBy { it.id }
  return commits.filter { commit ->
    commit.sha.startsWith(needle, ignoreCase = true) || commit.subject.contains(needle, ignoreCase = true) ||
      commit.ownerIds.any { id -> id.toString() == needle || byId[id]?.name?.contains(needle, ignoreCase = true) == true }
  }
}

internal fun commitSelectionOf(target: ChangeScopeTarget, scope: ChangeScope): ChangeScope = when (scope) {
  is ChangeScope.Subtasks, ChangeScope.Operator -> scopeCommits(target, scope).map { it.sha }
    .takeIf { it.isNotEmpty() }?.let { ChangeScope.Commits(it) } ?: ChangeScope.All
  else -> scope
}

internal fun subtaskSelectionOf(target: ChangeScopeTarget, scope: ChangeScope): ChangeScope {
  val selected = scope as? ChangeScope.Commits ?: return scope
  val selectedShas = selected.shas.toSet()
  val covered = target.subtasks.filter { it.commitShas.isNotEmpty() && it.commitShas.all { sha -> sha in selectedShas } }
  if (covered.isEmpty()) return ChangeScope.All
  val from = covered.minOf { it.id }
  val to = covered.maxOf { it.id }
  val range = normalizeRange(target, from, to) ?: return ChangeScope.All
  val normalized = selectedSubtaskIds(target.subtasks, range).filter { id -> target.subtasks.any { it.id == id && it.selectable } }.toSet()
  return if (normalized.isNotEmpty() && normalized.all { id -> target.subtasks.first { it.id == id }.commitShas.isNotEmpty() && target.subtasks.first { it.id == id }.commitShas.all { it in selectedShas } }) range else ChangeScope.All
}

/**
 * The single subtask a step selects: after or before the selected range, or the first or last selectable one from any other
 * scope. Null when nothing selectable lies that way.
 */
internal fun stepTarget(subtasks: List<ScopeSubtask>, scope: ChangeScope, forward: Boolean): Int? {
  val selectable = selectableSubtaskIds(subtasks)
  val columns = subtaskColumns(subtasks).filter { column -> column.ids.any { it in selectable } }
  val selected = (scope as? ChangeScope.Subtasks)?.let { selectedSubtaskIds(subtasks, it) }.orEmpty()
  return when {
    scope is ChangeScope.Subtasks && forward ->
      columns.firstOrNull { column -> column.ids.first() > scope.to && column.ids.none { it in selected } }?.ids?.first()
    scope is ChangeScope.Subtasks ->
      columns.lastOrNull { column -> column.ids.first() < scope.from && column.ids.none { it in selected } }?.ids?.first()
    forward -> columns.firstOrNull()?.ids?.first()
    else -> columns.lastOrNull()?.ids?.first()
  }
}

/** The scope bar's commit range for [scope]. */
internal fun commitRangeText(target: ChangeScopeTarget, scope: ChangeScope): String = when (scope) {
  ChangeScope.All -> when (val baseline = target.allBaseline) {
    is ChangeBaseline.TargetMergeBase -> "${baseline.targetBranch ?: "default branch"}...HEAD"
    is ChangeBaseline.RunStart -> "${baseline.baseSha.take(7)}..HEAD"
    is ChangeBaseline.SubtaskStart -> "${baseline.baseSha.take(7)}..HEAD"
    is ChangeBaseline.CommittedBranchRange -> "${baseline.targetBranch}...${baseline.headBranch}"
    is ChangeBaseline.CommittedRevisionRange -> "${baseline.fromSha.take(7)}..${baseline.toSha.take(7)}"
  }
  ChangeScope.Uncommitted -> ""
  ChangeScope.Operator -> target.timeline?.commits?.filter { it.operator }?.joinToString(", ") { it.sha.take(7) }.orEmpty()
  is ChangeScope.Commits -> when (val read = resolveScope(target, scope)) {
    is ScopeRead.Committed -> "${read.fromSha.take(7)}..${read.toSha.take(7)}"
    is ScopeRead.PerCommit -> read.commits.joinToString(", ") { it.sha.take(7) }
    is ScopeRead.Unavailable -> read.reason
    else -> ""
  }
  is ChangeScope.Subtasks -> {
    val read = resolveScope(target, scope)
    val range = when (read) {
      is ScopeRead.Committed -> "${read.fromSha.take(7)}..${read.toSha.take(7)}"
      is ScopeRead.Baseline -> when (val baseline = read.baseline) {
        is ChangeBaseline.CommittedRevisionRange -> "${baseline.fromSha.take(7)}..${baseline.toSha.take(7)}"
        else -> "${(baseline as? ChangeBaseline.SubtaskStart)?.baseSha?.take(7).orEmpty()}..HEAD"
      }
      is ScopeRead.Unavailable -> read.reason
      is ScopeRead.PerCommit -> read.commits.joinToString(", ") { it.sha.take(7) }
      else -> ""
    }
    val knownSelected = selectedSubtaskIds(target.subtasks, scope).filter { id -> target.subtasks.any { it.id == id } }
    val suffix = buildString {
      if (knownSelected.size > 1) append(" · ${knownSelected.size} of ${target.subtasks.size} subtasks")
      if (read is ScopeRead.Committed || read is ScopeRead.Baseline) {
        val operators = spannedCommits(target, scope).count { it.operator }
        if (operators == 1) append(" · includes 1 operator commit")
        else if (operators > 1) append(" · includes $operators operator commits")
      }
    }
    range + suffix
  }
}

/** Picker search: a blank query keeps every row; else rows whose number equals the query or whose name contains it, any case. */
internal fun filterSubtasks(subtasks: List<ScopeSubtask>, query: String): List<ScopeSubtask> {
  val needle = query.trim()
  if (needle.isEmpty()) return subtasks
  return subtasks.filter { it.id.toString() == needle || it.name.contains(needle, ignoreCase = true) }
}

/**
 * The toolbar's quick buttons. All stays available for the current target. Settled errors remain selectable for Operator and
 * Uncommitted so their actual read state can be shown. Running counts its subtask's stats and is disabled when no subtask runs.
 * Without Subtasks only All and Uncommitted are available.
 */
internal fun quickButtons(state: ChangesWindowUiState): List<QuickButtonUi> {
  val scope = state.scope
  val target = state.target
  val allCount = (state.allRead as? ScopeListUi.Listed)?.changes?.files?.size
  val buttons = mutableListOf(
    QuickButtonUi(QuickScope.All, "All", allCount, enabled = target != null, pressed = scope == ChangeScope.All),
  )
  val scopes = target?.let(::availableScopes).orEmpty()
  if (state.subtasksAvailable && state.view == ChangesView.Subtasks) {
    val running = state.subtasks.firstOrNull { it.state == ScopeSubtaskState.RUNNING }
    val runningCount = running?.let { state.stats[it.id]?.files?.size }
    buttons += QuickButtonUi(
      QuickScope.Running,
      running?.let { "Running · ${it.id}" } ?: "Running",
      runningCount,
      enabled = running != null && running.selectable && (runningCount ?: 0) > 0,
      pressed = running != null && target != null &&
        scope == normalizeRange(target, running.id, running.id),
      running = running != null,
    )
    if (ScopeKind.Operator in scopes) {
      buttons += readButton(QuickScope.Operator, "Operator", state.operatorRead, scope == ChangeScope.Operator)
    }
  }
  if (ScopeKind.Uncommitted in scopes) {
    buttons += readButton(QuickScope.Uncommitted, "Uncommitted", state.uncommittedRead, scope == ChangeScope.Uncommitted)
  }
  return buttons
}

private fun readButton(kind: QuickScope, label: String, read: ScopeChangesState?, pressed: Boolean): QuickButtonUi {
  val count = (read as? ScopeChangesState.Listed)?.changes?.files?.size
  val enabled = when (read) {
    null, ScopeChangesState.Loading -> false
    is ScopeChangesState.Listed -> read.changes.files.isNotEmpty()
    else -> true
  }
  return QuickButtonUi(kind, label, count, enabled, pressed)
}
