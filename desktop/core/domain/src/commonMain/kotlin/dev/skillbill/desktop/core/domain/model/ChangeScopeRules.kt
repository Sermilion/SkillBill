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

fun availableScopes(target: ChangeScopeTarget): Set<ScopeKind> {
  val scopes = if (target.subtasks.isNotEmpty() && target.rangesSupported && target.timeline != null) {
    ScopeKind.entries.toSet()
  } else {
    setOf(ScopeKind.All, ScopeKind.Uncommitted)
  }
  return if (target.committedOnly) scopes - ScopeKind.Uncommitted else scopes
}

fun normalizeRange(target: ChangeScopeTarget, a: Int, b: Int): ChangeScope.Subtasks? {
  if (ScopeKind.Subtasks !in availableScopes(target)) return null
  val first = target.subtasks.firstOrNull { it.id == a && it.selectable } ?: return null
  val second = target.subtasks.firstOrNull { it.id == b && it.selectable } ?: return null
  return ChangeScope.Subtasks(minOf(first.id, second.id), maxOf(first.id, second.id))
}

fun resolveScope(target: ChangeScopeTarget, scope: ChangeScope): ScopeRead {
  if (scope.kind !in availableScopes(target)) return ScopeRead.Unavailable("scope not available")
  return when (scope) {
    ChangeScope.All -> ScopeRead.Baseline(target.allBaseline)
    ChangeScope.Uncommitted -> ScopeRead.Uncommitted
    ChangeScope.Operator -> ScopeRead.PerCommit(target.timeline!!.commits.filter(ScopeCommit::operator))
    is ChangeScope.Subtasks -> resolveSubtasks(target, scope)
  }
}

fun workingBaseSha(timeline: ChangeScopeTimeline, subtask: ScopeSubtask): String =
  timeline.commits.firstOrNull { subtask.id in it.ownerIds }?.parentSha ?: timeline.headSha

private fun resolveSubtasks(target: ChangeScopeTarget, scope: ChangeScope.Subtasks): ScopeRead {
  if (scope.from > scope.to) return ScopeRead.Unavailable("scope not available")
  val subtasks = target.subtasks.associateBy(ScopeSubtask::id)
  val start = subtasks[scope.from]?.takeIf(ScopeSubtask::selectable) ?: return ScopeRead.Unavailable("no end commit")
  val end = subtasks[scope.to]?.takeIf(ScopeSubtask::selectable) ?: return ScopeRead.Unavailable("scope not available")
  val timeline = target.timeline ?: return ScopeRead.Unavailable("scope not available")
  val ownedIndices = timeline.commits.indices.filter { index ->
    timeline.commits[index].ownerIds.any { it in scope.from..scope.to }
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
    commit.operator || commit.ownerIds.any { it in scope.from..scope.to }
  }
  val includesOtherOwner = span.any { commit ->
    commit.ownerIds.isNotEmpty() && commit.ownerIds.none { it in scope.from..scope.to }
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

fun touchCount(path: String, scope: ChangeScope, stats: Map<Int, SubtaskStats>): Int? {
  val ids = subtasksTouching(path, stats)
  return when (scope) {
    ChangeScope.All -> ids.size
    is ChangeScope.Subtasks -> ids.count { it in scope.from..scope.to }
    ChangeScope.Operator, ChangeScope.Uncommitted -> null
  }
}

fun attributeHunks(rangeDiff: FileDiff, perSubtask: Map<Int, FileDiff>): List<Int?> {
  val linesBySubtask = perSubtask.mapValues { (_, diff) ->
    val lines = diff.hunks.flatMap { it.lines }
    lines.filter { it.kind == DiffLineKind.Added }.map { it.text }.toSet() to
      lines.filter { it.kind == DiffLineKind.Removed }.map { it.text }.toSet()
  }
  return rangeDiff.hunks.map { hunk ->
    val added = hunk.lines.filter { it.kind == DiffLineKind.Added }.map { it.text }.toSet()
    val removed = hunk.lines.filter { it.kind == DiffLineKind.Removed }.map { it.text }.toSet()
    if (added.isEmpty() && removed.isEmpty()) return@map null
    val matches = linesBySubtask.filter { (id, lines) ->
      val (theirAdded, theirRemoved) = lines
      added.all { it in theirAdded } && removed.all { it in theirRemoved } &&
        linesBySubtask.all { (otherId, otherLines) ->
          otherId == id || (added.none { it in otherLines.first } && removed.none { it in otherLines.second })
        }
    }.keys
    matches.singleOrNull()
  }
}

fun scopeSubtaskLabel(subtask: ScopeSubtask): String = "subtask ${subtask.id} · ${subtask.name}"
