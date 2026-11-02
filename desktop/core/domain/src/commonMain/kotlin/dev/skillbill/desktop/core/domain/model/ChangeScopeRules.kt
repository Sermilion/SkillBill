package dev.skillbill.desktop.core.domain.model

fun goalScopeTarget(workId: String, listed: SubtaskScopesState.Listed): ChangeScopeTarget = ChangeScopeTarget(
  workId = workId,
  worktreeRoot = listed.worktreeRoot,
  allBaseline = ChangeBaseline.TargetMergeBase(listed.worktreeRoot, listed.targetBranch),
  subtasks = listed.subtasks,
  rangesSupported = listed.rangesSupported,
)

fun executionScopeTarget(workId: String, baseline: ChangeBaseline): ChangeScopeTarget = ChangeScopeTarget(
  workId = workId,
  worktreeRoot = baseline.worktreeRoot,
  allBaseline = baseline,
  subtasks = emptyList(),
  rangesSupported = false,
)

fun availableScopes(target: ChangeScopeTarget): Set<ScopeKind> =
  if (target.subtasks.isNotEmpty() && target.rangesSupported) ScopeKind.entries.toSet()
  else setOf(ScopeKind.All, ScopeKind.Uncommitted)

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
    ChangeScope.Outside -> ScopeRead.Outside(outsideRanges(target.subtasks))
    is ChangeScope.Subtasks -> resolveSubtasks(target, scope)
  }
}

private fun resolveSubtasks(target: ChangeScopeTarget, scope: ChangeScope.Subtasks): ScopeRead {
  if (scope.from > scope.to) return ScopeRead.Unavailable("scope not available")
  val subtasks = target.subtasks.associateBy { it.id }
  val start = subtasks[scope.from]?.takeIf { it.selectable } ?: return ScopeRead.Unavailable("no end commit")
  val end = subtasks[scope.to]?.takeIf { it.selectable } ?: return ScopeRead.Unavailable("scope not available")
  val fromSha = start.startSha ?: return ScopeRead.Unavailable("no end commit")
  end.endSha?.let { return ScopeRead.Committed(fromSha, it) }
  return if (end.isLastStarted(target.subtasks)) {
    ScopeRead.Baseline(ChangeBaseline.SubtaskStart(target.worktreeRoot, fromSha, start.baselineUntrackedPaths))
  } else {
    ScopeRead.Unavailable("no end commit")
  }
}

private fun outsideRanges(subtasks: List<ScopeSubtask>): List<CommitRange> {
  val lastStarted = subtasks.indices.lastOrNull { index ->
    val current = subtasks[index]
    current.state in setOf(ScopeSubtaskState.RUNNING, ScopeSubtaskState.INTERRUPTED) &&
      current.startSha != null && subtasks.drop(index + 1).none { it.startSha != null }
  } ?: -1
  return subtasks.mapIndexedNotNull { index, subtask ->
    val start = subtask.startSha ?: return@mapIndexedNotNull null
    val end = subtask.endSha ?: if (index == lastStarted) null else return@mapIndexedNotNull null
    CommitRange(start, end)
  }
}

private fun ScopeSubtask.isLastStarted(subtasks: List<ScopeSubtask>): Boolean {
  val index = subtasks.indexOf(this)
  return index >= 0 && state in setOf(ScopeSubtaskState.RUNNING, ScopeSubtaskState.INTERRUPTED) &&
    startSha != null && subtasks.drop(index + 1).none { it.startSha != null }
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
    ChangeScope.Outside, ChangeScope.Uncommitted -> null
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
