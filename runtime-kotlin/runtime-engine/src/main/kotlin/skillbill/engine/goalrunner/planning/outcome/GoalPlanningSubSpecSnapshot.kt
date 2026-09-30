package skillbill.engine.goalrunner.planning.outcome

import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.sweep.DefaultGoalPlanningSweep
import skillbill.review.spec.GovernedSpecSectionParser
import skillbill.review.spec.GovernedSpecSectionParser.ACCEPTANCE_CRITERIA_PREFIX
import skillbill.text.sha256HexUtf8
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path

internal data class GoalPlanningSubSpecSnapshot(
  val title: String,
  val acceptanceCriteria: List<String>,
  val siblingHashes: Map<Int, String>,
)

internal fun governedSubSpecReady(specText: String): Boolean =
  acceptanceCriteriaOf(specText).isNotEmpty() && hasImplementationDetails(specText)

internal fun DefaultGoalPlanningSweep.snapshotSubSpecs(
  shared: GoalPlanningSharedContext,
  subtask: DecompositionSubtask,
  specPath: Path,
): GoalPlanningSubSpecSnapshot {
  val specText = manifestFileStore.readText(specPath)
  val siblings =
    shared.manifest.subtasks
      .filter { it.id != subtask.id }
      .mapNotNull { sibling -> siblingHash(shared, sibling)?.let { sibling.id to it } }
      .toMap()
  return GoalPlanningSubSpecSnapshot(
    title = specTitle(specText),
    acceptanceCriteria = acceptanceCriteriaOf(specText),
    siblingHashes = siblings,
  )
}

internal fun DefaultGoalPlanningSweep.admitPersistedSubSpec(
  shared: GoalPlanningSharedContext,
  subtask: DecompositionSubtask,
  launchedSpecPath: Path,
  snapshot: GoalPlanningSubSpecSnapshot,
  startedSiblingIds: Set<Int>,
): Result<String> =
  runCatching {
    val path = resolvedSubSpecPath(shared.repoRoot, subtask.specPath, repositoryEnclosingRootPort)
    require(path != null && path == launchedSpecPath) {
      "the governed sub-spec no longer resolves to its assigned path inside the repository"
    }
    require(manifestFileStore.isRegularFile(path)) { "the governed sub-spec is no longer a regular file" }
    val text = manifestFileStore.readText(path)
    require(specTitle(text) == snapshot.title) { "the governed sub-spec subtask identity changed" }
    require(acceptanceCriteriaOf(text) == snapshot.acceptanceCriteria) {
      "the governed sub-spec acceptance criteria are missing or changed"
    }
    require(hasImplementationDetails(text)) {
      "the governed sub-spec has no non-blank implementation details section"
    }
    require(sha256HexUtf8(manifestFileStore.readText(shared.parentSpecPath)) == shared.parentSpecHash) {
      "the parent spec was modified during the plan session"
    }
    val modifiedSibling =
      snapshot.siblingHashes.entries.firstOrNull { (id, hash) ->
        id !in startedSiblingIds &&
          shared.manifest.subtasks.firstOrNull { it.id == id }?.let { siblingHash(shared, it) } != hash
      }
    require(modifiedSibling == null) {
      "sibling sub-spec ${modifiedSibling?.key} was modified during the plan session"
    }
    text
  }.recoverCatching { error ->
    throw IllegalStateException(
      "Goal planning plan session for subtask ${subtask.id} did not leave a valid governed sub-spec: " +
        error.message.orEmpty(),
      error,
    )
  }

private fun DefaultGoalPlanningSweep.siblingHash(
  shared: GoalPlanningSharedContext,
  sibling: DecompositionSubtask,
): String? {
  val path = resolvedSubSpecPath(shared.repoRoot, sibling.specPath, repositoryEnclosingRootPort) ?: return null
  if (!manifestFileStore.isRegularFile(path)) return null
  return sha256HexUtf8(manifestFileStore.readText(path))
}

private fun specTitle(specText: String): String =
  specText.lineSequence().firstOrNull(String::isNotBlank).orEmpty().trim()

private fun acceptanceCriteriaOf(specText: String): List<String> =
  GovernedSpecSectionParser.parseListSection(specText) { it.startsWith(ACCEPTANCE_CRITERIA_PREFIX) }

private fun hasImplementationDetails(specText: String): Boolean {
  val lines = specText.lines()
  val start = lines.indexOfFirst { IMPLEMENTATION_HEADING.matches(it.trim()) }
  if (start < 0) return false
  return lines.drop(start + 1).takeWhile { !SECOND_LEVEL_HEADING.matches(it) }.any(String::isNotBlank)
}

private val IMPLEMENTATION_HEADING = Regex("""^##\s+implementation\b.*$""", RegexOption.IGNORE_CASE)
private val SECOND_LEVEL_HEADING = Regex("""^##\s+\S.*$""")
