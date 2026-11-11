package skillbill.workflow.taskruntime.model.planning

import skillbill.text.sha256HexUtf8

private val SCOPE_SHA256 = Regex("[0-9a-f]{64}")

public data class PlanBundleFeasibilityReviewProjection(
  val identity: PlanBundleFeasibilityIdentity,
  val requiredRefs: List<String>,
  val carriedRefs: List<String>,
  val carriedLines: List<PlanBundleFeasibilityAcceptedLine>,
  val previousConflictLines: List<PlanBundleFeasibilityConflict>,
  val changedSubtaskIds: List<Int>,
)

public sealed interface PlanBundleFeasibilityReviewScopeResult {
  public data class Valid(
    val projection: PlanBundleFeasibilityReviewProjection,
  ) : PlanBundleFeasibilityReviewScopeResult

  public data class Invalid(val reason: String) : PlanBundleFeasibilityReviewScopeResult
}

public object PlanBundleFeasibilityReviewScope {
  public fun derive(
    current: PlanBundleFeasibilityIdentity,
    previous: FeatureTaskRuntimePlanBundleFeasibilityState?,
  ): PlanBundleFeasibilityReviewScopeResult {
    validationReason(current)?.let { return PlanBundleFeasibilityReviewScopeResult.Invalid(it) }
    if (previous == null) return PlanBundleFeasibilityReviewScopeResult.Valid(full(current))
    if (previous.invalidReason() != null) {
      return PlanBundleFeasibilityReviewScopeResult.Invalid("previous feasibility evidence is incoherent")
    }
    return deriveFromEvidence(current, previous)
  }

  private fun deriveFromEvidence(
    current: PlanBundleFeasibilityIdentity,
    previous: FeatureTaskRuntimePlanBundleFeasibilityState,
  ): PlanBundleFeasibilityReviewScopeResult {
    val historical =
      previous.cycleHistory.lastOrNull { it.acceptedLines.isNotEmpty() || it.conflicts.isNotEmpty() }
        ?.takeIf { previous.acceptedLines.isEmpty() && previous.conflicts.isEmpty() }
    val priorAcceptedLines = historical?.acceptedLines ?: previous.acceptedLines
    val priorConflicts = historical?.conflicts ?: previous.conflicts
    if (priorAcceptedLines.isEmpty() && priorConflicts.isEmpty()) {
      return PlanBundleFeasibilityReviewScopeResult.Valid(full(current))
    }
    val oldIdentity =
      previous.settledLaunches.lastOrNull {
        it.kind == PlanBundleFeasibilityLaunchKind.REVIEW &&
          it.outcome == PlanBundleFeasibilityLaunchOutcome.COMPLETED
      }?.baselineIdentity ?: historical?.identity ?: previous.identity
    if (validationReason(oldIdentity) != null) {
      return PlanBundleFeasibilityReviewScopeResult.Invalid("previous feasibility identity is incoherent")
    }
    if (priorAcceptedLines.any { !coherentLine(it, oldIdentity) } ||
      priorConflicts.any { conflict -> oldIdentity.subtasks.none { conflict.ref in it.criterionRefs } }
    ) {
      return PlanBundleFeasibilityReviewScopeResult.Invalid(
        "previous feasibility evidence does not match its judged identity",
      )
    }
    return PlanBundleFeasibilityReviewScopeResult.Valid(
      select(current, oldIdentity, priorAcceptedLines, priorConflicts),
    )
  }

  private fun select(
    current: PlanBundleFeasibilityIdentity,
    oldIdentity: PlanBundleFeasibilityIdentity,
    priorAcceptedLines: List<PlanBundleFeasibilityAcceptedLine>,
    priorConflicts: List<PlanBundleFeasibilityConflict>,
  ): PlanBundleFeasibilityReviewProjection {
    val currentRefs = current.subtasks.flatMap { it.criterionRefs }
    val currentRefSet = currentRefs.toSet()
    val oldById = oldIdentity.subtasks.associateBy { it.id }
    val currentById = current.subtasks.associateBy { it.id }
    val changedIds = current.subtasks.filter { oldById[it.id]?.specSha256 != it.specSha256 }.map { it.id }
    val manifestDrift = current.manifestSha256 != oldIdentity.manifestSha256
    val identityDrift = manifestDrift || current.parentSha256 != oldIdentity.parentSha256
    val invalidatedIds = dependencyClosure(current, changedIds.toSet())
    val conflictedRefs = priorConflicts.map { it.ref }.filter(currentRefSet::contains).toSet()
    val carriedByRef = mutableMapOf<String, PlanBundleFeasibilityAcceptedLine>()
    priorAcceptedLines.forEach { line ->
      if (line.ref !in currentRefSet || identityDrift) return@forEach
      val subtask = current.subtasks.first { line.ref in it.criterionRefs }
      val dependenciesUnchanged =
        current.transitiveDependencyIds(subtask.id).all { id ->
          oldById[id]?.specSha256 == currentById[id]?.specSha256
        }
      val judgedIdentityMatches =
        line.judgedParentSha256 == current.parentSha256 &&
          line.judgedManifestSha256 == current.manifestSha256 && line.judgedSpecSha256 == subtask.specSha256
      val settledScope = subtask.id !in invalidatedIds && line.ref !in conflictedRefs
      if (judgedIdentityMatches && dependenciesUnchanged && settledScope) {
        carriedByRef[line.ref] = line
      }
    }
    val previousConflicts = priorConflicts.filter { it.ref in currentRefSet }
    val carriedRefs = currentRefs.filter { it in carriedByRef }
    val requiredRefs = currentRefs.filterNot { it in carriedByRef }
    return PlanBundleFeasibilityReviewProjection(
      current,
      requiredRefs,
      carriedRefs,
      carriedRefs.map(carriedByRef::getValue),
      previousConflicts,
      changedIds,
    )
  }

  private fun full(identity: PlanBundleFeasibilityIdentity) =
    PlanBundleFeasibilityReviewProjection(
      identity,
      identity.subtasks.flatMap { it.criterionRefs },
      emptyList(),
      emptyList(),
      emptyList(),
      emptyList(),
    )

  private fun validationReason(identity: PlanBundleFeasibilityIdentity): String? {
    val validDigests =
      listOf(identity.parentSha256, identity.manifestSha256, identity.immutableManifestSha256)
        .all(SCOPE_SHA256::matches)
    val rawIdentityMatches =
      identity.rawParentText?.let { sha256HexUtf8(it) == identity.parentSha256 } != false &&
        identity.rawManifestText?.let { sha256HexUtf8(it) == identity.manifestSha256 } != false
    val ids = identity.subtasks.map { it.id }
    return when {
      !validDigests -> "bundle identity digest is malformed"
      !rawIdentityMatches -> "bundle raw identity does not match its digest"
      ids.any { it < 1 } || ids.distinct().size != ids.size -> "bundle subtask identities are incoherent"
      !validSubtasks(identity) -> "bundle dependency or criterion identity is incoherent"
      else -> null
    }
  }

  private fun validSubtasks(identity: PlanBundleFeasibilityIdentity): Boolean {
    val seen = mutableSetOf<Int>()
    return identity.subtasks.all { subtask ->
      val validSpec =
        SCOPE_SHA256.matches(subtask.specSha256) &&
          subtask.rawSpecText?.let { sha256HexUtf8(it) == subtask.specSha256 } != false
      val validRefs = subtask.criterionRefs == subtask.criterionRefs.indices.map { "${subtask.id}.${it + 1}" }
      val validDependencies =
        subtask.dependencies.map { it.subtaskId }.distinct().size == subtask.dependencies.size &&
          subtask.dependencies.all { it.subtaskId in seen }
      seen += subtask.id
      validSpec && validRefs && validDependencies
    }
  }

  private fun dependencyClosure(
    identity: PlanBundleFeasibilityIdentity,
    changed: Set<Int>,
  ): Set<Int> {
    val invalid = changed.toMutableSet()
    identity.subtasks.forEach { child ->
      if (child.dependencies.any { it.subtaskId in invalid }) invalid += child.id
    }
    return invalid
  }

  private fun coherentLine(
    line: PlanBundleFeasibilityAcceptedLine,
    identity: PlanBundleFeasibilityIdentity,
  ): Boolean {
    val subtask = identity.subtasks.firstOrNull { line.ref in it.criterionRefs } ?: return false
    val byId = identity.subtasks.associateBy { it.id }
    val expectedDirect =
      subtask.dependencies.map { dependency ->
        PlanBundleFeasibilityDependencyHash(dependency.subtaskId, byId.getValue(dependency.subtaskId).specSha256)
      }
    val expectedTransitive =
      identity.transitiveDependencyIds(subtask.id).map { id ->
        PlanBundleFeasibilityDependencyHash(id, byId.getValue(id).specSha256)
      }
    return line.judgedParentSha256 == identity.parentSha256 &&
      line.judgedManifestSha256 == identity.manifestSha256 &&
      line.judgedSpecSha256 == subtask.specSha256 &&
      (line.dependencyHashes == expectedDirect || line.dependencyHashes == expectedTransitive)
  }
}

public fun PlanBundleFeasibilityIdentity.transitiveDependencyIds(subtaskId: Int): List<Int> {
  val byId = subtasks.associateBy { it.id }
  val found = mutableSetOf<Int>()

  fun visit(childId: Int) {
    byId.getValue(childId).dependencies.forEach { dependency ->
      if (found.add(dependency.subtaskId)) visit(dependency.subtaskId)
    }
  }
  visit(subtaskId)
  return subtasks.map { it.id }.filter(found::contains)
}
