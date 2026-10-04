package skillbill.infrastructure.workflow.goalplanning

import skillbill.error.shellcontent.goalVerificationBoundaryCapExceededError
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryBody
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import java.nio.file.Path

internal data class BoundaryBodyResolutionState(
  val bodies: MutableList<GoalPlanningBoundaryBody>,
  val unresolved: MutableList<String>,
  val parsedByPath: MutableMap<String, Map<String, BoundaryMemoryEntry>>,
  var truncated: Boolean,
  var totalBytes: Int,
)

internal data class BoundaryBodyResolutionStep(
  val continueLoop: Boolean,
  val truncated: Boolean,
)

internal fun resolveBoundaryHeading(input: BoundaryHeadingResolutionInput): BoundaryBodyResolutionStep {
  if (
    input.state.bodies.size >= input.caps.maxSelectedBodies ||
    input.state.totalBytes >= input.caps.maxTotalBodyBytes
  ) {
    return capExceededStep(input.index, input.requested, input.loudFailOnCapExceeded, input.state)
  }
  val entry = entryForBoundary(input.canonicalRoot, input.headingId, input.catalogHeadingIds, input.state.parsedByPath)
  if (entry == null) {
    input.state.unresolved.add(input.headingId)
    return BoundaryBodyResolutionStep(continueLoop = true, truncated = input.state.truncated)
  }
  val body = goalPlanningTruncateToUtf8Bytes(entry.body, input.caps.maxBodyBytes)
  val bodyBytes = goalPlanningUtf8Size(body)
  if (body.length < entry.body.length) {
    input.state.truncated = true
  }
  if (input.state.totalBytes + bodyBytes > input.caps.maxTotalBodyBytes) {
    return capExceededStep(input.index, input.requested, input.loudFailOnCapExceeded, input.state)
  }
  input.state.totalBytes += bodyBytes
  input.state.bodies.add(
    GoalPlanningBoundaryBody(
      headingId = input.headingId,
      sourcePath = BoundaryMemoryHeadingParser.sourcePathOf(input.headingId).orEmpty(),
      heading = entry.heading,
      body = body,
    ),
  )
  return BoundaryBodyResolutionStep(continueLoop = true, truncated = input.state.truncated)
}

private fun capExceededStep(
  index: Int,
  requested: List<String>,
  loudFailOnCapExceeded: Boolean,
  state: BoundaryBodyResolutionState,
): BoundaryBodyResolutionStep {
  if (loudFailOnCapExceeded) {
    throwBoundaryCapExceeded(
      "finding verification boundary body resolution exceeded max_selected_bodies or max_total_body_bytes",
    )
  }
  state.truncated = true
  state.unresolved.addAll(requested.subList(index, requested.size))
  return BoundaryBodyResolutionStep(continueLoop = false, truncated = true)
}

private fun throwBoundaryCapExceeded(message: String) {
  throw goalVerificationBoundaryCapExceededError(message)
}

private fun entryForBoundary(
  repoRoot: Path,
  headingId: String,
  catalogHeadingIds: Set<String>,
  parsedByPath: MutableMap<String, Map<String, BoundaryMemoryEntry>>,
): BoundaryMemoryEntry? {
  if (headingId !in catalogHeadingIds) return null
  val sourcePath = BoundaryMemoryHeadingParser.sourcePathOf(headingId) ?: return null
  if (!GoalPlanningRepositoryScope.isBoundaryMemoryPath(sourcePath)) return null
  return parsedByPath.getOrPut(sourcePath) { boundaryEntriesOf(repoRoot, sourcePath) }[headingId]
}

private fun boundaryEntriesOf(
  repoRoot: Path,
  sourcePath: String,
): Map<String, BoundaryMemoryEntry> {
  val canonical = GoalPlanningRepositoryScope.includedRegularFile(repoRoot, sourcePath) ?: return emptyMap()
  val read =
    goalPlanningReadFileOrNull(canonical, GoalPlanningContext.MAX_BOUNDARY_FILE_BYTES)
      ?: return emptyMap()
  return BoundaryMemoryHeadingParser.parse(sourcePath, read.text).associateBy(BoundaryMemoryEntry::headingId)
}
