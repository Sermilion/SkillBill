package skillbill.infrastructure.workflow.goalplanning

import skillbill.goalrunner.planning.GoalPlanningExcludedPaths
import skillbill.review.model.repositoryRelativePathViolation
import java.nio.file.Files
import java.nio.file.Path

internal fun goalPlanningCanonicalRoot(repoRoot: Path): Path =
  repoRoot.toRealPathOrNull() ?: repoRoot.toAbsolutePath().normalize()

internal fun goalPlanningIncluded(
  repoRoot: Path,
  candidate: Path,
): Pair<Path, String>? {
  val canonical = candidate.toRealPathOrNull()?.takeIf { path -> path.startsWith(repoRoot) } ?: return null
  val relative = repoRoot.relativize(canonical).joinToString("/")
  if (relative.isEmpty() || GoalPlanningExcludedPaths.isExcluded(relative)) return null
  return canonical to relative
}

internal fun goalPlanningIncludedRegularFile(
  repoRoot: Path,
  relativePath: String,
): Path? {
  if (relativePath.isBlank() || GoalPlanningExcludedPaths.isExcluded(relativePath)) return null
  val (canonical, canonicalRelative) = goalPlanningIncluded(repoRoot, repoRoot.resolve(relativePath)) ?: return null
  if (canonicalRelative != relativePath) return null
  return canonical.takeIf { path -> Files.isRegularFile(path) }
}

internal fun goalPlanningIsBoundaryMemoryPath(relativePath: String): Boolean {
  val segments = relativePath.split("/")
  return segments.size >= 2 &&
    segments[segments.size - 2] == GoalPlanningRepositoryScope.AGENT_DIRECTORY &&
    segments.last() in GoalPlanningRepositoryScope.BOUNDARY_MEMORY_FILES
}

internal fun goalPlanningAgentDirectories(repoRoot: Path): AgentDirectoryWalk {
  val found = mutableListOf<Path>()
  val seen = mutableSetOf(repoRoot)
  val pending = ArrayDeque(listOf(repoRoot))
  var visited = 0
  var unlistable = false
  while (pending.isNotEmpty() && visited < GoalPlanningRepositoryScope.MAX_VISITED_DIRECTORIES) {
    visited += 1
    val children = goalPlanningSortedChildDirectories(pending.removeFirst())
    if (children == null) {
      unlistable = true
      continue
    }
    for (canonical in children.mapNotNull { child -> goalPlanningIncluded(repoRoot, child)?.first }) {
      if (!seen.add(canonical)) continue
      if (canonical.fileName.toString() == GoalPlanningRepositoryScope.AGENT_DIRECTORY) {
        found.add(canonical)
      } else {
        pending.add(canonical)
      }
    }
  }
  return AgentDirectoryWalk(
    directories = found.sortedBy { agentDir -> repoRoot.relativize(agentDir).joinToString("/") },
    incomplete = pending.isNotEmpty() || unlistable,
  )
}

internal fun goalPlanningOwningAgentDirectory(
  repoRoot: Path,
  findingPath: String,
): Path? {
  val normalized = goalPlanningNormalizeFindingPath(findingPath) ?: return null
  if (GoalPlanningExcludedPaths.isExcluded(normalized)) return null
  val segments = normalized.split("/")
  for (segmentCount in segments.size downTo 0) {
    val prefix = segments.take(segmentCount).joinToString("/")
    val agentRelative =
      if (prefix.isEmpty()) {
        GoalPlanningRepositoryScope.AGENT_DIRECTORY
      } else {
        "$prefix/${GoalPlanningRepositoryScope.AGENT_DIRECTORY}"
      }
    val agentDir = repoRoot.resolve(agentRelative)
    val includedAgent = goalPlanningIncluded(repoRoot, agentDir) ?: continue
    if (GoalPlanningRepositoryScope.BOUNDARY_MEMORY_FILES.any { fileName ->
        goalPlanningIncludedRegularFile(repoRoot, "$agentRelative/$fileName") != null
      }
    ) {
      return includedAgent.first
    }
  }
  return null
}

internal fun goalPlanningOwningAgentDirectories(
  repoRoot: Path,
  findingPaths: List<String>,
): List<Path> =
  findingPaths.mapNotNull { path -> goalPlanningOwningAgentDirectory(repoRoot, path) }
    .distinct()
    .sortedBy { agentDir -> repoRoot.relativize(agentDir).joinToString("/") }

internal fun goalPlanningNormalizeFindingPath(findingPath: String): String? {
  val trimmed = findingPath.trim()
  if (trimmed.isBlank()) return null
  return trimmed.takeIf { repositoryRelativePathViolation(it) == null }
}

private fun goalPlanningSortedChildDirectories(directory: Path): List<Path>? =
  runCatching {
    Files.list(directory).use { entries ->
      entries.filter { path -> Files.isDirectory(path) }
        .sorted()
        .toList()
    }
  }.getOrNull()

private fun Path.toRealPathOrNull(): Path? = runCatching { toRealPath() }.getOrNull()
