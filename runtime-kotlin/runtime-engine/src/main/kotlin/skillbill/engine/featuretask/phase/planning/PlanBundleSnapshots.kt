package skillbill.engine.featuretask.phase.planning

import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.featurespec.model.FeatureSpecWriteResult
import skillbill.review.spec.GovernedSpecSectionParser
import skillbill.review.spec.GovernedSpecSectionParser.ACCEPTANCE_CRITERIA_PREFIX
import skillbill.text.sha256HexUtf8
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundleOrigin
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundleSubtask
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.io.IOException
import java.nio.file.Path

internal object PlanBundleSpecText {
  private val IMPLEMENTATION_HEADING = Regex("""^##\s+implementation\b.*$""", RegexOption.IGNORE_CASE)
  private val SECOND_LEVEL_HEADING = Regex("""^##\s+\S.*$""")

  fun scopeOf(specText: String): String {
    val lines = specText.lines()
    val section = detailsRange(lines) ?: return specText.trimEnd()
    return (lines.take(section.first) + lines.drop(section.last + 1)).joinToString("\n").trimEnd()
  }

  fun detailsOf(specText: String): String? {
    val lines = specText.lines()
    val section = detailsRange(lines) ?: return null
    return lines.subList(section.first + 1, section.last + 1).joinToString("\n").trim().takeIf(String::isNotBlank)
  }

  fun governedSubSpecReady(specText: String): Boolean =
    acceptanceCriteriaOf(specText).isNotEmpty() && detailsOf(specText) != null

  fun acceptanceCriteriaOf(specText: String): List<String> =
    GovernedSpecSectionParser.parseListSection(specText) { it.startsWith(ACCEPTANCE_CRITERIA_PREFIX) }

  private fun detailsRange(lines: List<String>): IntRange? {
    val start = lines.indexOfFirst { IMPLEMENTATION_HEADING.matches(it.trim()) }
    if (start < 0) return null
    val next = lines.drop(start + 1).indexOfFirst { SECOND_LEVEL_HEADING.matches(it) }
    return start..(if (next < 0) lines.lastIndex else start + next)
  }
}

internal sealed interface PlanUnitSettlement {
  data class Planned(val bundle: FeatureTaskRuntimePlanBundle) : PlanUnitSettlement

  data class Rejected(val reason: String) : PlanUnitSettlement
}

internal class PlanBundleSnapshots(private val planner: FeatureTaskRuntimeDecompositionPlanner) {
  fun capture(
    repoRoot: Path,
    written: FeatureSpecWriteResult,
    origin: FeatureTaskRuntimePlanBundleOrigin,
  ): FeatureTaskRuntimePlanBundle {
    val parentPath = planner.bundlePath(repoRoot, written.parentSpecPath)
    val manifestPath = planner.bundlePath(repoRoot, written.decompositionManifestPath)
    val ids = planner.authoredSubtasks(parentPath).map { it.id }
    return FeatureTaskRuntimePlanBundle(
      origin = origin,
      parentSpecSha256 = sha256HexUtf8(planner.specText(parentPath)),
      manifestSha256 = sha256HexUtf8(planner.specText(manifestPath)),
      subtasks =
        written.subtaskSpecPaths.zip(ids).map { (specPath, id) ->
          snapshotSubtask(repoRoot, id, specPath, origin)
        },
    )
  }

  fun unitComplete(
    repoRoot: Path,
    subtask: FeatureTaskRuntimePlanBundleSubtask,
    unitRecord: FeatureTaskRuntimePhaseRecord?,
  ): Boolean {
    if (unitRecord == null ||
      unitRecord.status != WorkflowStepStatus.COMPLETED ||
      unitRecord.outputArtifact.isNullOrBlank()
    ) {
      return false
    }
    val recorded = subtask.detailsSha256 ?: return false
    val text = readOrNull(planner.bundlePath(repoRoot, subtask.specPath)) ?: return false
    return sha256HexUtf8(PlanBundleSpecText.scopeOf(text)) == subtask.scopeSha256 &&
      PlanBundleSpecText.detailsOf(text)?.let(::sha256HexUtf8) == recorded
  }

  fun drift(
    repoRoot: Path,
    issueKey: String,
    bundle: FeatureTaskRuntimePlanBundle,
  ): String? {
    val parent = planner.existingParentSpec(repoRoot, issueKey) ?: return "The parent spec is missing."
    if (readOrNull(parent)?.let(::sha256HexUtf8) != bundle.parentSpecSha256) {
      return "The parent spec changed while the subtasks were planned."
    }
    val manifest = parent.resolveSibling(DECOMPOSITION_MANIFEST_FILENAME)
    if (readOrNull(manifest)?.let(::sha256HexUtf8) != bundle.manifestSha256) {
      return "The decomposition manifest changed while the subtasks were planned."
    }
    return bundle.subtasks.firstNotNullOfOrNull { subtask ->
      val text = readOrNull(planner.bundlePath(repoRoot, subtask.specPath))
      if (text != null && sha256HexUtf8(PlanBundleSpecText.scopeOf(text)) == subtask.scopeSha256) {
        null
      } else {
        "The scope of subtask ${subtask.id} changed while the subtasks were planned."
      }
    }
  }

  fun rebase(
    repoRoot: Path,
    issueKey: String,
    bundle: FeatureTaskRuntimePlanBundle,
    settledSubtaskIds: Set<Int>,
  ): FeatureTaskRuntimePlanBundle? {
    val parent = planner.existingParentSpec(repoRoot, issueKey) ?: return null
    val parentText = readOrNull(parent) ?: return null
    val manifestText = readOrNull(parent.resolveSibling(DECOMPOSITION_MANIFEST_FILENAME)) ?: return null
    return bundle.copy(
      parentSpecSha256 = sha256HexUtf8(parentText),
      manifestSha256 = sha256HexUtf8(manifestText),
      subtasks = bundle.subtasks.map { rebaseSubtask(repoRoot, it, it.id in settledSubtaskIds) },
    )
  }

  fun settleUnit(
    repoRoot: Path,
    issueKey: String,
    bundle: FeatureTaskRuntimePlanBundle,
    unitId: Int,
  ): PlanUnitSettlement {
    drift(repoRoot, issueKey, bundle)?.let { return PlanUnitSettlement.Rejected(it) }
    val subtask =
      bundle.subtask(unitId) ?: return PlanUnitSettlement.Rejected("Subtask $unitId is not in the plan bundle.")
    return settleUnitDetails(repoRoot, bundle, subtask)
  }

  private fun settleUnitDetails(
    repoRoot: Path,
    bundle: FeatureTaskRuntimePlanBundle,
    subtask: FeatureTaskRuntimePlanBundleSubtask,
  ): PlanUnitSettlement {
    val text =
      readOrNull(planner.bundlePath(repoRoot, subtask.specPath))
        ?: return PlanUnitSettlement.Rejected("The spec of subtask ${subtask.id} is no longer readable.")
    val details =
      PlanBundleSpecText.detailsOf(text)
        ?.takeIf { PlanBundleSpecText.governedSubSpecReady(text) }
        ?: return PlanUnitSettlement.Rejected(
          "Subtask ${subtask.id} has no acceptance criteria or a blank Implementation Details section.",
        )
    return PlanUnitSettlement.Planned(
      bundle.withSubtask(subtask.copy(detailsSha256 = sha256HexUtf8(details), planText = details)),
    )
  }

  private fun rebaseSubtask(
    repoRoot: Path,
    subtask: FeatureTaskRuntimePlanBundleSubtask,
    settled: Boolean,
  ): FeatureTaskRuntimePlanBundleSubtask {
    val text = readOrNull(planner.bundlePath(repoRoot, subtask.specPath)) ?: return subtask
    val scopeSha256 = sha256HexUtf8(PlanBundleSpecText.scopeOf(text))
    val details = PlanBundleSpecText.detailsOf(text)
    val editedDetails =
      details != null && sha256HexUtf8(
        details,
      ) != subtask.detailsSha256 && PlanBundleSpecText.governedSubSpecReady(text)
    return when {
      (settled || editedDetails) && details != null ->
        subtask.copy(scopeSha256 = scopeSha256, detailsSha256 = sha256HexUtf8(details), planText = details)
      scopeSha256 != subtask.scopeSha256 ->
        subtask.copy(scopeSha256 = scopeSha256, detailsSha256 = null, planText = null)
      else -> subtask
    }
  }

  private fun snapshotSubtask(
    repoRoot: Path,
    id: Int,
    specPath: String,
    origin: FeatureTaskRuntimePlanBundleOrigin,
  ): FeatureTaskRuntimePlanBundleSubtask {
    val text = planner.specText(planner.bundlePath(repoRoot, specPath))
    val keptDetails =
      PlanBundleSpecText.detailsOf(text)?.takeIf { origin == FeatureTaskRuntimePlanBundleOrigin.EXISTING }
    return FeatureTaskRuntimePlanBundleSubtask(
      id = id,
      specPath = specPath,
      scopeSha256 = sha256HexUtf8(PlanBundleSpecText.scopeOf(text)),
      detailsSha256 = keptDetails?.let(::sha256HexUtf8),
      planText = keptDetails,
    )
  }

  private fun readOrNull(path: Path): String? =
    try {
      planner.specText(path)
    } catch (_: IOException) {
      null
    }
}
