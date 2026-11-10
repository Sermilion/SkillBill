package skillbill.engine.featuretask.phase.planning

import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.error.core.InvalidFeatureSpecPreparationRequestError
import skillbill.text.sha256HexUtf8
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityDependency
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityDependencyHash
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityIdentity
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchKind
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchOutcome
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityMode
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilitySettledLaunch
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilitySubtaskIdentity
import java.nio.file.Path

public data class PlanBundleFeasibilitySnapshot internal constructor(
  internal val manifest: DecompositionManifest,
  internal val parentText: String,
  internal val manifestText: String,
  internal val specTexts: Map<Int, String>,
  internal val repairDirectory: Path? = null,
) {
  public val fingerprint: String =
    sha256HexUtf8(
      listOf(sha256HexUtf8(parentText), planBundleImmutableManifestHash(manifest))
        .plus(manifest.subtasks.map { "${it.id}:${it.specPath}:${sha256HexUtf8(specTexts.getValue(it.id))}" })
        .joinToString("\n"),
    )

  public val identity: PlanBundleFeasibilityIdentity =
    PlanBundleFeasibilityIdentity(
      parentSha256 = sha256HexUtf8(parentText),
      manifestSha256 = sha256HexUtf8(manifestText),
      immutableManifestSha256 = planBundleImmutableManifestHash(manifest),
      rawParentText = parentText,
      rawManifestText = manifestText,
      subtasks =
        manifest.subtasks.map { subtask ->
          PlanBundleFeasibilitySubtaskIdentity(
            id = subtask.id,
            specPath = subtask.specPath,
            specSha256 = sha256HexUtf8(specTexts.getValue(subtask.id)),
            criterionRefs =
              PlanBundleSpecText.acceptanceCriteriaOf(specTexts.getValue(subtask.id)).indices.map {
                "${subtask.id}.${it + 1}"
              },
            dependencies =
              subtask.dependencies.map {
                PlanBundleFeasibilityDependency(it.subtaskId, it.optional, it.skipped)
              },
            rawSpecText = specTexts.getValue(subtask.id),
          )
        },
    )

  public val settledSubtaskIds: Set<Int> =
    manifest.subtasks.filter { it.status.decompositionStatus() in SETTLED }.mapTo(mutableSetOf()) { it.id }

  public val criterionRefs: List<String> = identity.subtasks.flatMap { it.criterionRefs }

  internal fun prompt(): String =
    buildString {
      appendLine("## Bundle feasibility snapshot")
      appendLine("Fingerprint: $fingerprint")
      repairDirectory?.let { appendLine("Repair directory: $it") }
      appendLine("Required criterion refs: ${criterionRefs.joinToString(", ")}")
      appendLine("Parent spec, ${manifest.parentSpecPath}:")
      appendLine(parentText)
      appendLine("Manifest, $DECOMPOSITION_MANIFEST_FILENAME:")
      appendLine(manifestText)
      manifest.subtasks.forEach { subtask ->
        appendLine("Subtask ${subtask.id}, ${subtask.specPath}:")
        appendLine(specTexts.getValue(subtask.id))
      }
    }

  internal fun repairViolation(after: PlanBundleFeasibilitySnapshot): String? =
    when {
      parentText != after.parentText -> "Bundle feasibility repair changed the parent spec."
      manifestText != after.manifestText ->
        "Bundle feasibility repair changed the manifest bytes."
      manifest != after.manifest ->
        "Bundle feasibility repair changed the manifest or execution state."
      else ->
        manifest.subtasks.firstNotNullOfOrNull { subtask ->
          if (subtask.status.decompositionStatus() != DecompositionStatus.PENDING &&
            specTexts[subtask.id] != after.specTexts[subtask.id]
          ) {
            "Bundle feasibility repair changed non-pending subtask ${subtask.id}."
          } else {
            null
          }
        }
    }

  private companion object {
    val SETTLED = setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
  }
}

public object PlanBundleFeasibility {
  internal const val REPAIR_REQUIRED: String = "Bundle feasibility requires planning repair."
  internal const val REVIEW_REQUIRED: String = "Bundle feasibility requires a fresh read-only review."
  internal const val PREFIX: String = "bundle-feasibility-v2:"
  public const val HISTORICAL_PREFIX: String = "bundle-feasibility-v1:"
  internal const val REPAIRED: String = "$PREFIX repaired"
  private val REVIEW_HEADER = Regex("""bundle-feasibility-v2: (approved|changes_requested) ([0-9a-f]{64})""")
  private val REVIEW_LINE = Regex("""(criterion|conflict) ([1-9][0-9]*\.[1-9][0-9]*): (\S.*)""")
  private val CITATION = Regex("""(?:^|\s)([^\s:]+):([1-9][0-9]*)(?=\s|$|[),.;])""")

  public data class ReviewReceipt(
    val approved: Boolean,
    val fingerprint: String,
    val lines: List<ReviewLine>,
    val originalValue: String,
  ) {
    public val acceptedLines: List<String> get() = lines.filterNot { it.conflict }.map { it.originalLine }
    public val conflictLines: List<String> get() = lines.filter { it.conflict }.map { it.originalLine }
  }

  public data class ReviewLine(val ref: String, val conflict: Boolean, val originalLine: String)

  public sealed interface ReviewParseResult {
    public data class Accepted(val receipt: ReviewReceipt) : ReviewParseResult

    public data class Rejected(val reason: String) : ReviewParseResult
  }

  public fun capture(
    planner: FeatureTaskRuntimeDecompositionPlanner,
    repoRoot: Path,
    issueKey: String,
  ): PlanBundleFeasibilitySnapshot = captureInDirectory(planner, repoRoot, issueKey, null)

  internal fun captureInDirectory(
    planner: FeatureTaskRuntimeDecompositionPlanner,
    repoRoot: Path,
    issueKey: String,
    directory: Path?,
  ): PlanBundleFeasibilitySnapshot {
    val parent =
      directory?.resolve("spec.md")
        ?: planner.existingParentSpec(repoRoot, issueKey)
        ?: throw InvalidFeatureSpecPreparationRequestError("plan_bundle", "The plan bundle is missing.")
    val manifest = planner.authoredManifest(parent, persistRepairs = false)
    return PlanBundleFeasibilitySnapshot(
      manifest,
      planner.specText(parent),
      planner.specText(parent.resolveSibling(DECOMPOSITION_MANIFEST_FILENAME)),
      manifest.subtasks.associate {
        val path = directory?.resolve(Path.of(it.specPath).fileName) ?: planner.bundlePath(repoRoot, it.specPath)
        it.id to planner.specText(path)
      },
    )
  }

  public fun approved(
    state: FeatureTaskRuntimePlanBundleFeasibilityState,
    snapshot: PlanBundleFeasibilitySnapshot,
    record: FeatureTaskRuntimePhaseRecord?,
    phaseId: String,
  ): Boolean {
    if (!validApprovedState(state) || record?.status != WorkflowStepStatus.COMPLETED) return false
    val artifact = record.outputArtifact ?: return false
    val output = NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(artifact, "plan_subtasks feasibility")
    val matchingPhase =
      output.phaseId == phaseId && output.status == WorkflowStepStatus.COMPLETED.wireValue &&
        phaseId == record.phaseId
    return matchingPhase && state.approval?.immutableFingerprint == snapshot.fingerprint &&
      completedApprovalEvidenceMatches(state, snapshot, record, output.output.value)
  }

  private fun validApprovedState(state: FeatureTaskRuntimePlanBundleFeasibilityState): Boolean =
    state.invalidReason() == null && state.mode == PlanBundleFeasibilityMode.APPROVED && state.conflicts.isEmpty()

  private fun completedApprovalEvidenceMatches(
    state: FeatureTaskRuntimePlanBundleFeasibilityState,
    snapshot: PlanBundleFeasibilitySnapshot,
    record: FeatureTaskRuntimePhaseRecord,
    value: String,
  ): Boolean {
    val approval = state.approval ?: return false
    val candidate = state.approvalCandidate ?: return false
    val transition = state.settledLaunches.singleOrNull { it.transitionId == approval.transitionId } ?: return false
    val evidence = state.acceptedLines
    val matchingCoverage =
      state.requiredRefs == snapshot.criterionRefs &&
        evidence.map { it.ref } == snapshot.criterionRefs && approval.criterionLines == evidence.map { it.line }
    val matchingCandidate = candidate.transitionId == approval.transitionId && candidate.matchesRecord(record)
    return matchingCoverage && matchingCandidate && evidenceMatchesBaseline(state, transition.baselineIdentity) &&
      approvingTransitionMatches(state, transition, record, value) && approvalRejection(snapshot, value) == null
  }

  private fun evidenceMatchesBaseline(
    state: FeatureTaskRuntimePlanBundleFeasibilityState,
    judgedIdentity: PlanBundleFeasibilityIdentity,
  ): Boolean {
    val judgedById = judgedIdentity.subtasks.associateBy { it.id }
    return state.acceptedLines.all { line ->
      val subtask = judgedById.values.firstOrNull { line.ref in it.criterionRefs } ?: return@all false
      val matchingIdentity =
        line.judgedParentSha256 == judgedIdentity.parentSha256 &&
          line.judgedManifestSha256 == judgedIdentity.manifestSha256 && line.judgedSpecSha256 == subtask.specSha256
      matchingIdentity && line.dependencyHashes ==
        subtask.dependencies.map { dependency ->
          PlanBundleFeasibilityDependencyHash(
            dependency.subtaskId,
            judgedById[dependency.subtaskId]?.specSha256.orEmpty(),
          )
        }
    }
  }

  private fun approvingTransitionMatches(
    state: FeatureTaskRuntimePlanBundleFeasibilityState,
    transition: PlanBundleFeasibilitySettledLaunch,
    record: FeatureTaskRuntimePhaseRecord,
    value: String,
  ): Boolean {
    val matchingReview =
      transition.kind == PlanBundleFeasibilityLaunchKind.REVIEW &&
        transition.outcome == PlanBundleFeasibilityLaunchOutcome.COMPLETED
    val matchingRecord = transition.phaseId == record.phaseId && transition.attempt == record.attemptCount
    return matchingReview && matchingRecord && transition.value == value && state.lastReviewValue == transition.value
  }

  public fun parseReviewReceipt(
    snapshot: PlanBundleFeasibilitySnapshot,
    value: String,
  ): ReviewParseResult {
    val lines = value.lines()
    val header = lines.firstOrNull()?.let(REVIEW_HEADER::matchEntire)
    return when {
      header == null -> ReviewParseResult.Rejected("Feasibility receipt header is malformed.")
      header.groupValues[FINGERPRINT_GROUP] != snapshot.fingerprint ->
        ReviewParseResult.Rejected("Feasibility receipt identifies a stale bundle fingerprint.")
      else -> parseReviewLines(snapshot, value, header.groupValues[1] == "approved", lines.drop(1))
    }
  }

  private fun parseReviewLines(
    snapshot: PlanBundleFeasibilitySnapshot,
    value: String,
    approved: Boolean,
    lines: List<String>,
  ): ReviewParseResult {
    val resultLines = mutableListOf<ReviewLine>()
    for (line in lines.filter(String::isNotBlank)) {
      val match =
        REVIEW_LINE.matchEntire(line)
          ?: return ReviewParseResult.Rejected("Malformed feasibility receipt line.")
      reviewLineRejection(match)?.let { return ReviewParseResult.Rejected(it) }
      resultLines += ReviewLine(match.groupValues[FINGERPRINT_GROUP], match.groupValues[1] == "conflict", line)
    }
    val refs = resultLines.map { it.ref }
    val hasConflicts = resultLines.any { it.conflict }
    return when {
      refs != snapshot.criterionRefs || refs.isEmpty() ->
        ReviewParseResult.Rejected(
          "Feasibility receipt must cover every criterion exactly once in manifest order.",
        )
      approved == hasConflicts ->
        ReviewParseResult.Rejected("Feasibility receipt verdict does not match its conflict lines.")
      else -> ReviewParseResult.Accepted(ReviewReceipt(approved, snapshot.fingerprint, resultLines, value))
    }
  }

  private fun reviewLineRejection(match: MatchResult): String? {
    val evidence = match.groupValues[EVIDENCE_GROUP]
    return when {
      evidence.length < MIN_EVIDENCE_CHARS -> "Feasibility evidence must contain at least 40 characters."
      match.groupValues[1] == "conflict" && !CITATION.containsMatchIn(evidence) ->
        "Every feasibility conflict must cite a nonempty path and positive line."
      else -> null
    }
  }

  private const val FINGERPRINT_GROUP = 2
  private const val EVIDENCE_GROUP = 3

  internal fun approvalRejection(
    snapshot: PlanBundleFeasibilitySnapshot,
    value: String,
  ): String? =
    when (val parsed = parseReviewReceipt(snapshot, value)) {
      is ReviewParseResult.Accepted -> if (parsed.receipt.approved) null else "Feasibility receipt requests changes."
      is ReviewParseResult.Rejected -> parsed.reason
    }

  private const val MIN_EVIDENCE_CHARS: Int = 40
}
