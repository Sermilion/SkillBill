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
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
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

  public val settledSubtaskIds: Set<Int> =
    manifest.subtasks.filter { it.status.decompositionStatus() in SETTLED }.mapTo(mutableSetOf()) { it.id }

  internal val criterionRefs: List<String> =
    manifest.subtasks.flatMap { subtask ->
      PlanBundleSpecText.acceptanceCriteriaOf(specTexts.getValue(subtask.id)).indices.map { "${subtask.id}.${it + 1}" }
    }

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
      manifest != after.manifest ->
        "Bundle feasibility repair changed the manifest or execution state."
      else ->
        manifest.subtasks.firstNotNullOfOrNull { subtask ->
          if (subtask.id in settledSubtaskIds && specTexts[subtask.id] != after.specTexts[subtask.id]) {
            "Bundle feasibility repair changed settled subtask ${subtask.id}."
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
  internal const val PREFIX: String = "bundle-feasibility-v1:"
  internal const val REPAIRED: String = "$PREFIX repaired"
  private val CRITERION = Regex("""criterion ([1-9][0-9]*\.[1-9][0-9]*): (\S.*)""")

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
    val manifest = planner.authoredManifest(parent)
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
    snapshot: PlanBundleFeasibilitySnapshot,
    record: FeatureTaskRuntimePhaseRecord?,
  ): Boolean {
    if (record?.status != WorkflowStepStatus.COMPLETED) return false
    val artifact = record.outputArtifact ?: return false
    val output = NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(artifact, "plan_subtasks feasibility")
    if (output.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS ||
      output.status != WorkflowStepStatus.COMPLETED.wireValue
    ) {
      return false
    }
    return approvalRejection(snapshot, output.output.value) == null
  }

  internal fun approvalRejection(
    snapshot: PlanBundleFeasibilitySnapshot,
    value: String,
  ): String? {
    val lines = value.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    if (lines.firstOrNull() != "$PREFIX approved ${snapshot.fingerprint}") {
      return "Feasibility approval is missing or does not identify the current bundle fingerprint."
    }
    val evidence = lines.drop(1).map { CRITERION.matchEntire(it) ?: return "Malformed criterion feasibility evidence." }
    val refs = evidence.map { it.groupValues[1] }
    if (refs != snapshot.criterionRefs || refs.isEmpty()) {
      return "Feasibility evidence must cover every criterion exactly once in manifest order."
    }
    if (evidence.any { it.groupValues[2].length < MIN_EVIDENCE_CHARS }) {
      return "Feasibility evidence must explain the available dependencies and the passing intermediate state."
    }
    return null
  }

  internal fun repairRequested(reason: String?): Boolean = reason?.startsWith(REPAIR_REQUIRED) == true

  private const val MIN_EVIDENCE_CHARS: Int = 40
}
