package dev.skillbill.desktop.core.domain.model

fun goalScopeTarget(workId: String, listed: SubtaskScopesState.Listed): ChangeScopeTarget = ChangeScopeTarget(
  workId = workId,
  worktreeRoot = listed.worktreeRoot,
  allBaseline = if (listed.committedHeadBranch != null && listed.targetBranch != null) {
    ChangeBaseline.CommittedBranchRange(listed.worktreeRoot, listed.targetBranch, listed.committedHeadBranch)
  } else {
    ChangeBaseline.TargetMergeBase(listed.worktreeRoot, listed.targetBranch)
  },
  subtasks = listed.subtasks,
  rangesSupported = listed.rangesSupported,
  committedOnly = listed.committedHeadBranch != null,
  timeline = listed.timeline,
  timelineProblem = listed.timelineProblem,
)

fun executionScopeTarget(workId: String, baseline: ChangeBaseline): ChangeScopeTarget = ChangeScopeTarget(
  workId = workId,
  worktreeRoot = baseline.worktreeRoot,
  allBaseline = baseline,
  subtasks = emptyList(),
  rangesSupported = false,
)

fun chatScopeTarget(
  sessionId: String,
  repositoryRoot: String,
  baseline: ChatChangesBaseline,
  timeline: ChangeScopeTimeline,
  paths: Set<String>?,
): ChangeScopeTarget = ChangeScopeTarget(
  workId = "chat:" + sessionId,
  worktreeRoot = repositoryRoot,
  allBaseline = ChangeBaseline.RunStart(repositoryRoot, baseline.stashSha ?: baseline.headSha, baseline.untrackedPaths),
  subtasks = emptyList(),
  rangesSupported = false,
  timeline = timeline,
  chat = true,
  paths = paths,
)

fun availableScopes(target: ChangeScopeTarget): Set<ScopeKind> {
  if (target.chat) return setOf(ScopeKind.All, ScopeKind.Uncommitted, ScopeKind.Commits)
  val scopes = if (target.subtasks.isNotEmpty() && target.rangesSupported && target.timeline != null) {
    ScopeKind.entries.toSet()
  } else {
    setOf(ScopeKind.All, ScopeKind.Uncommitted)
  }
  return if (target.committedOnly) scopes - ScopeKind.Uncommitted else scopes
}

data class SubtaskColumn(val ids: List<Int>)

fun subtaskColumns(subtasks: List<ScopeSubtask>): List<SubtaskColumn> {
  val groups = mutableListOf<MutableSet<Int>>()
  val shasById = subtasks.associate { it.id to it.commitShas.toSet() }
  for (subtask in subtasks) {
    val shas = shasById.getValue(subtask.id)
    val matches = groups.filter { group ->
      group.any { id -> shasById.getValue(id).any { sha -> sha in shas } }
    }
    val merged = mutableSetOf(subtask.id)
    matches.forEach(merged::addAll)
    groups.removeAll(matches)
    groups += merged
  }
  return groups.map { SubtaskColumn(it.sorted()) }.sortedBy { it.ids.first() }
}

fun selectableSubtaskIds(subtasks: List<ScopeSubtask>): Set<Int> {
  val byId = subtasks.associateBy { it.id }
  return subtaskColumns(subtasks)
    .filter { column -> column.ids.any { byId[it]?.selectable == true } }
    .flatMap { it.ids }
    .toSet()
}

fun selectedSubtaskIds(subtasks: List<ScopeSubtask>, scope: ChangeScope.Subtasks): Set<Int> {
  val selected = (scope.from..scope.to).toMutableSet()
  for (column in subtaskColumns(subtasks)) {
    if (column.ids.any { it in selected }) selected.addAll(column.ids)
  }
  return selected
}

fun selectedEndSubtask(subtasks: List<ScopeSubtask>, scope: ChangeScope.Subtasks): ScopeSubtask? {
  val byId = subtasks.associateBy { it.id }
  return selectedSubtaskIds(subtasks, scope)
    .sortedDescending()
    .firstNotNullOfOrNull { id -> byId[id]?.takeIf(ScopeSubtask::selectable) }
}

fun normalizeRange(target: ChangeScopeTarget, a: Int, b: Int): ChangeScope.Subtasks? {
  if (ScopeKind.Subtasks !in availableScopes(target)) return null
  val selectable = selectableSubtaskIds(target.subtasks)
  if (a !in selectable || b !in selectable) return null
  val columns = subtaskColumns(target.subtasks)
  val low = minOf(a, b)
  val high = maxOf(a, b)
  val lowColumn = columns.firstOrNull { low in it.ids } ?: return null
  val highColumn = columns.firstOrNull { high in it.ids } ?: return null
  val newLow = lowColumn.ids.first()
  val newHigh = if (highColumn.ids.last() - highColumn.ids.first() + 1 == highColumn.ids.size) {
    highColumn.ids.last()
  } else {
    highColumn.ids.first()
  }
  return ChangeScope.Subtasks(minOf(newLow, newHigh), maxOf(newLow, newHigh))
}

fun normalizeCommits(target: ChangeScopeTarget, shas: List<String>): ChangeScope.Commits? {
  if (ScopeKind.Commits !in availableScopes(target) || shas.isEmpty()) return null
  val selected = shas.toSet()
  val commits = target.timeline?.commits?.filter { it.sha in selected }.orEmpty()
  if (commits.size != selected.size) return null
  return ChangeScope.Commits(commits.map { it.sha })
}

fun resolveScope(target: ChangeScopeTarget, scope: ChangeScope): ScopeRead {
  if (scope.kind !in availableScopes(target)) return ScopeRead.Unavailable("scope not available")
  return when (scope) {
    ChangeScope.All -> when (val baseline = target.allBaseline) {
      is ChangeBaseline.CommittedRevisionRange -> ScopeRead.Committed(baseline.fromSha, baseline.toSha)
      else -> ScopeRead.Baseline(baseline)
    }
    ChangeScope.Uncommitted -> ScopeRead.Uncommitted
    ChangeScope.Operator -> ScopeRead.PerCommit(target.timeline!!.commits.filter(ScopeCommit::operator))
    is ChangeScope.Subtasks -> resolveSubtasks(target, scope)
    is ChangeScope.Commits -> {
      val commits = normalizeCommits(target, scope.shas) ?: return ScopeRead.Unavailable("scope not available")
      val timeline = target.timeline!!
      val indices = commits.shas.map { sha -> timeline.commits.indexOfFirst { it.sha == sha } }
      if (indices.zipWithNext().all { (a, b) -> b == a + 1 }) {
        ScopeRead.Committed(timeline.commits[indices.first()].parentSha, timeline.commits[indices.last()].sha)
      } else ScopeRead.PerCommit(commits.shas.mapNotNull { sha -> timeline.commits.firstOrNull { it.sha == sha } })
    }
  }
}

fun workingBaseSha(timeline: ChangeScopeTimeline, subtask: ScopeSubtask): String =
  timeline.commits.firstOrNull { subtask.id in it.ownerIds }?.parentSha ?: timeline.headSha

private fun resolveSubtasks(target: ChangeScopeTarget, scope: ChangeScope.Subtasks): ScopeRead {
  if (scope.from > scope.to) return ScopeRead.Unavailable("scope not available")
  val selectable = selectableSubtaskIds(target.subtasks)
  if (scope.from !in selectable || scope.to !in selectable) return ScopeRead.Unavailable("scope not available")
  val subtasks = target.subtasks.associateBy(ScopeSubtask::id)
  val start = subtasks[scope.from] ?: return ScopeRead.Unavailable("no end commit")
  val end = selectedEndSubtask(target.subtasks, scope) ?: return ScopeRead.Unavailable("scope not available")
  val timeline = target.timeline ?: return ScopeRead.Unavailable("scope not available")
  val selectedIds = selectedSubtaskIds(target.subtasks, scope)
  val ownedIndices = timeline.commits.indices.filter { index ->
    timeline.commits[index].ownerIds.any { it in selectedIds }
  }
  val lastStarted = end.isLastStarted(target.subtasks)
  if (ownedIndices.isEmpty()) {
    return if (!target.committedOnly && end.recordedSha == null && lastStarted) {
      ScopeRead.Baseline(ChangeBaseline.SubtaskStart(target.worktreeRoot, timeline.headSha, start.baselineUntrackedPaths))
    } else {
      ScopeRead.Unavailable("no end commit")
    }
  }
  val firstIndex = ownedIndices.first()
  val lastIndex = ownedIndices.last()
  val span = timeline.commits.subList(firstIndex, lastIndex + 1)
  val selected = span.filter { commit ->
    commit.operator || commit.ownerIds.any { it in selectedIds }
  }
  val includesOtherOwner = span.any { commit ->
    commit.ownerIds.isNotEmpty() && commit.ownerIds.none { it in selectedIds }
  }
  if (includesOtherOwner) return ScopeRead.PerCommit(selected)
  if (!target.committedOnly && end.recordedSha == null && lastStarted) {
    val base = span.firstOrNull()?.parentSha ?: timeline.headSha
    return ScopeRead.Baseline(ChangeBaseline.SubtaskStart(target.worktreeRoot, base, start.baselineUntrackedPaths))
  }
  val first = span.first()
  val last = span.last()
  return ScopeRead.Committed(first.parentSha, last.sha)
}

private fun ScopeSubtask.isLastStarted(subtasks: List<ScopeSubtask>): Boolean {
  val index = subtasks.indexOf(this)
  return index >= 0 && state in setOf(ScopeSubtaskState.RUNNING, ScopeSubtaskState.INTERRUPTED) &&
    subtasks.drop(index + 1).all { it.state == ScopeSubtaskState.NOT_STARTED }
}

fun subtasksTouching(path: String, stats: Map<Int, SubtaskStats>): List<Int> = stats.entries
  .filter { (_, value) -> value.files.any { it.path == path || it.oldPath == path } }
  .map { it.key }
  .sorted()

fun touchCount(path: String, scope: ChangeScope, stats: Map<Int, SubtaskStats>, subtasks: List<ScopeSubtask>): Int? {
  val ids = subtasksTouching(path, stats)
  return when (scope) {
    ChangeScope.All -> ids.size
    is ChangeScope.Subtasks -> ids.count { it in selectedSubtaskIds(subtasks, scope) }
    is ChangeScope.Commits, ChangeScope.Operator, ChangeScope.Uncommitted -> null
  }
}

fun <K> attributeHunks(rangeDiff: FileDiff, perOwner: Map<K, FileDiff>): List<K?> {
  val linesByOwner = perOwner.mapValues { (_, diff) ->
    val lines = diff.hunks.flatMap { it.lines }
    lines.filter { it.kind == DiffLineKind.Added }.map { it.text }.toSet() to
      lines.filter { it.kind == DiffLineKind.Removed }.map { it.text }.toSet()
  }
  return rangeDiff.hunks.map { hunk ->
    val added = hunk.lines.filter { it.kind == DiffLineKind.Added }.map { it.text }.toSet()
    val removed = hunk.lines.filter { it.kind == DiffLineKind.Removed }.map { it.text }.toSet()
    if (added.isEmpty() && removed.isEmpty()) return@map null
    val matches = linesByOwner.filter { (owner, lines) ->
      val (theirAdded, theirRemoved) = lines
      added.all { it in theirAdded } && removed.all { it in theirRemoved } &&
        linesByOwner.all { (otherOwner, otherLines) ->
          otherOwner == owner || (added.none { it in otherLines.first } && removed.none { it in otherLines.second })
        }
    }.keys
    matches.singleOrNull()
  }
}

sealed interface ScopeHunkOwner {
  data class Subtasks(val ids: List<Int>) : ScopeHunkOwner
  data class OperatorCommit(val sha: String) : ScopeHunkOwner
}

fun hunkOwnerLabel(owner: ScopeHunkOwner, subtasks: List<ScopeSubtask>): String = when (owner) {
  is ScopeHunkOwner.OperatorCommit -> owner.sha.take(7)
  is ScopeHunkOwner.Subtasks -> {
    val byId = subtasks.associateBy { it.id }
    owner.ids.mapNotNull { id -> byId[id]?.let(::scopeSubtaskLabel) }.joinToString(" + ")
  }
}

fun spannedCommits(target: ChangeScopeTarget, scope: ChangeScope.Subtasks): List<ScopeCommit> {
  val timeline = target.timeline ?: return emptyList()
  val selected = selectedSubtaskIds(target.subtasks, scope)
  val ownedIndices = timeline.commits.indices.filter { index ->
    timeline.commits[index].ownerIds.any { it in selected }
  }
  if (ownedIndices.isEmpty()) return emptyList()
  val firstIndex = ownedIndices.first()
  return when (val read = resolveScope(target, scope)) {
    is ScopeRead.Baseline -> timeline.commits.subList(firstIndex, timeline.commits.size)
    is ScopeRead.Committed -> {
      val lastIndex = ownedIndices.last()
      timeline.commits.subList(firstIndex, lastIndex + 1).filter { commit ->
        commit.operator || commit.ownerIds.any { it in selected }
      }
    }
    is ScopeRead.PerCommit -> read.commits
    else -> emptyList()
  }
}

fun scopeCommits(target: ChangeScopeTarget, scope: ChangeScope): List<ScopeCommit> = when (scope) {
  is ChangeScope.Commits -> normalizeCommits(target, scope.shas)?.shas.orEmpty().mapNotNull { sha -> target.timeline?.commits?.firstOrNull { it.sha == sha } }
  ChangeScope.Operator -> target.timeline?.commits?.filter(ScopeCommit::operator).orEmpty()
  is ChangeScope.Subtasks -> spannedCommits(target, scope)
  else -> emptyList()
}

data class ScopeHunkGroup(val owner: ScopeHunkOwner, val commits: List<ScopeCommit>, val workingTree: Boolean)

fun scopeHunkGroups(target: ChangeScopeTarget, scope: ChangeScope.Subtasks): List<ScopeHunkGroup> {
  val read = resolveScope(target, scope)
  if (read !is ScopeRead.Committed && read !is ScopeRead.Baseline) return emptyList()
  val groups = linkedMapOf<ScopeHunkOwner, ScopeHunkGroup>()
  for (commit in spannedCommits(target, scope)) {
    val owner = if (commit.operator) {
      ScopeHunkOwner.OperatorCommit(commit.sha)
    } else {
      ScopeHunkOwner.Subtasks(commit.ownerIds.sorted())
    }
    val existing = groups[owner]
    groups[owner] = if (existing == null) {
      ScopeHunkGroup(owner, listOf(commit), workingTree = false)
    } else {
      existing.copy(commits = existing.commits + commit)
    }
  }
  if (read is ScopeRead.Baseline) {
    val end = selectedEndSubtask(target.subtasks, scope)
    if (end != null) {
      val owner = ScopeHunkOwner.Subtasks(listOf(end.id))
      val existing = groups[owner]
      groups[owner] = if (existing == null) {
        ScopeHunkGroup(owner, emptyList(), workingTree = true)
      } else {
        existing.copy(workingTree = true)
      }
    }
  }
  return groups.values.toList()
}

fun scopeSubtaskLabel(subtask: ScopeSubtask): String = "subtask ${subtask.id} · ${subtask.name}"
