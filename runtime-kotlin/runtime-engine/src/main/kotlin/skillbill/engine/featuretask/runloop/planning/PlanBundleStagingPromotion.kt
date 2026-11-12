package skillbill.engine.featuretask.runloop.planning

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibility
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibilitySnapshot
import skillbill.engine.featuretask.phase.planning.PlanBundleTarget
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.install.model.SupportedAgent
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

internal object PlanBundleStagingPromotion {
  private const val PARENT_SPEC_FILE = "spec.md"

  fun targetFor(
    planner: FeatureTaskRuntimeDecompositionPlanner,
    run: PhaseRun,
  ): PlanBundleTarget? =
    planner.planBundleTarget(
      repoRoot = run.request.repoRoot,
      issueKey = run.request.issueKey,
      workflowId = run.request.workflowId,
      governedSpecPath = run.request.admittedExecution?.identity?.governedSpecPath,
      providerHonorsAdditionalDirectories = honorsAdditionalDirectories(run.resolvedAgent.resolvedAgentId),
    )

  fun seed(
    planner: FeatureTaskRuntimeDecompositionPlanner,
    target: PlanBundleTarget.Staged,
    diagnostics: RuntimeDiagnostics,
    provider: String,
  ) {
    try {
      copyTree(planner, TreeCopy(target.storeDirectory, target.directory, emptySet(), keepExisting = true))
    } catch (error: IOException) {
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, warning(target, "staging_seed_failed", provider), error)
    }
  }

  fun seedFeasibilityRepair(
    planner: FeatureTaskRuntimeDecompositionPlanner,
    target: PlanBundleTarget.Staged,
    repoRoot: Path,
    diagnostics: RuntimeDiagnostics,
    provider: String,
  ): Boolean =
    try {
      removeStagingRoot(planner, repoRoot, target.stagingRoot)
      copyTree(planner, TreeCopy(target.storeDirectory, target.directory, emptySet(), keepExisting = false))
      true
    } catch (error: IOException) {
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, warning(target, "staging_seed_failed", provider), error)
      false
    }

  fun promote(
    planner: FeatureTaskRuntimeDecompositionPlanner,
    diagnostics: RuntimeDiagnostics,
    request: PromotionRequest,
  ): Boolean {
    val target = request.target
    try {
      val skipped = if (request.parentSpecProtected) setOf(PARENT_SPEC_FILE) else emptySet()
      copyTree(planner, TreeCopy(target.directory, target.storeDirectory, skipped, keepExisting = false))
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        warning(target, "provider_without_additional_directories", request.provider),
      )
      finishStaging(planner, diagnostics, request)
      return true
    } catch (error: IOException) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        warning(target, "staging_promotion_failed", request.provider),
        error,
      )
      return false
    }
  }

  fun promoteFeasibilityRepair(
    planner: FeatureTaskRuntimeDecompositionPlanner,
    request: PromotionRequest,
    issueKey: String,
    canonicalBefore: PlanBundleFeasibilitySnapshot,
    after: PlanBundleFeasibilitySnapshot,
  ): Boolean {
    val canonical = PlanBundleFeasibility.capture(planner, request.repoRoot, issueKey)
    if (canonical.identity == after.identity) return true
    if (canonical.identity != canonicalBefore.identity ||
      canonicalBefore.repairViolation(canonical) != null
    ) {
      return false
    }
    val pending =
      canonicalBefore.manifest.subtasks.filter {
        it.status.decompositionStatus() == DecompositionStatus.PENDING
      }
    val replacements =
      pending.map { subtask ->
        repairReplacement(planner, request, subtask, after) ?: return false
      }
    return planner.applyFeasibilityRepair(
      request.repoRoot,
      issueKey,
      canonicalBefore,
      after,
      replacements.toMap(),
    )
  }

  private fun repairReplacement(
    planner: FeatureTaskRuntimeDecompositionPlanner,
    request: PromotionRequest,
    subtask: DecompositionSubtask,
    after: PlanBundleFeasibilitySnapshot,
  ): Pair<Path, String>? {
    val canonical = planner.bundlePath(request.repoRoot, subtask.specPath).toAbsolutePath().normalize()
    val relative = request.target.storeDirectory.toAbsolutePath().normalize().relativize(canonical)
    val staged = request.target.directory.resolve(relative).toAbsolutePath().normalize()
    val stagingRoot = request.target.directory.toAbsolutePath().normalize()
    if (relative.startsWith("..") || !staged.startsWith(stagingRoot) || !planner.isBundleFile(staged)) return null
    val intended = after.identity.subtasks.firstOrNull { it.id == subtask.id }?.rawSpecText ?: return null
    return (canonical to intended).takeIf { planner.specText(staged) == intended }
  }

  fun seedUnit(
    planner: FeatureTaskRuntimeDecompositionPlanner,
    diagnostics: RuntimeDiagnostics,
    unit: StagedUnit,
  ): Path =
    try {
      val staged = unit.stagedFile()
      planner.writeBundleFile(staged, planner.specText(unit.storeSubSpec))
      staged
    } catch (error: IOException) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        warning(unit.target, "staging_seed_failed", unit.provider),
        error,
      )
      unit.target.directory.resolve(unit.storeSubSpec.fileName)
    }

  fun stagedUnitPath(
    diagnostics: RuntimeDiagnostics,
    unit: StagedUnit,
  ): Path? =
    try {
      val staged = unit.stagedFile()
      if (!staged.toRealPath().startsWith(unit.target.directory.toRealPath())) {
        throw IOException("'${unit.storeSubSpec.fileName}' resolves outside the bundle.")
      }
      staged
    } catch (error: IOException) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        warning(unit.target, "staging_promotion_failed", unit.provider),
        error,
      )
      null
    }

  fun promoteUnit(
    planner: FeatureTaskRuntimeDecompositionPlanner,
    diagnostics: RuntimeDiagnostics,
    unit: StagedUnit,
  ): Boolean {
    val staged = stagedUnitPath(diagnostics, unit) ?: return false
    return try {
      planner.writeBundleFile(unit.storeSubSpec, planner.specText(staged))
      true
    } catch (error: IOException) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        warning(unit.target, "staging_promotion_failed", unit.provider),
        error,
      )
      false
    }
  }

  fun finishStaging(
    planner: FeatureTaskRuntimeDecompositionPlanner,
    diagnostics: RuntimeDiagnostics,
    request: PromotionRequest,
  ) {
    val target = request.target
    try {
      removeStagingRoot(planner, request.repoRoot, target.stagingRoot)
    } catch (error: IOException) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        warning(target, "staging_promotion_failed", request.provider),
        error,
      )
    }
  }

  private fun copyTree(
    planner: FeatureTaskRuntimeDecompositionPlanner,
    copy: TreeCopy,
  ) {
    val realFrom = copy.from.toRealPath()
    planner.bundleTree(copy.from).sorted().forEach { file ->
      val relative = copy.from.relativize(file.toAbsolutePath().normalize())
      if (!planner.isBundleFile(file) || relative.toString() in copy.skippedNames) return@forEach
      if (!file.toRealPath().startsWith(realFrom)) throw IOException("'$relative' resolves outside the bundle.")
      val target = copy.to.resolve(relative)
      if (copy.keepExisting && planner.isBundleFile(target)) return@forEach
      planner.writeBundleFile(target, planner.specText(file))
    }
  }

  private fun removeStagingRoot(
    planner: FeatureTaskRuntimeDecompositionPlanner,
    repoRoot: Path,
    stagingRoot: Path,
  ) {
    val allowed = planner.planStagingRoot(repoRoot.toAbsolutePath().normalize())
    val root = stagingRoot.toAbsolutePath().normalize()
    if (root == allowed || !root.startsWith(allowed)) return
    val tree =
      try {
        planner.bundleTree(root)
      } catch (_: NoSuchFileException) {
        return
      }
    tree.sortedDescending().forEach(planner::deleteBundlePath)
    planner.deleteBundlePath(root)
  }

  private fun warning(
    target: PlanBundleTarget.Staged,
    cause: String,
    provider: String,
  ): String =
    "seam=agent_spec_staging value_expected=${target.storeDirectory} value_used=${target.directory} " +
      "cause=$cause provider=$provider"

  private fun honorsAdditionalDirectories(agentId: String): Boolean =
    SupportedAgent.fromWireOrNull(agentId)?.honorsAdditionalWritableDirectories ?: true
}

private data class TreeCopy(
  val from: Path,
  val to: Path,
  val skippedNames: Set<String>,
  val keepExisting: Boolean,
)

internal data class PromotionRequest(
  val target: PlanBundleTarget.Staged,
  val repoRoot: Path,
  val provider: String,
  val parentSpecProtected: Boolean,
)

internal data class StagedUnit(
  val target: PlanBundleTarget.Staged,
  val storeSubSpec: Path,
  val provider: String,
) {
  fun stagedFile(): Path {
    val relative = target.storeDirectory.relativize(storeSubSpec.toAbsolutePath().normalize())
    if (relative.startsWith("..")) throw IOException("'${storeSubSpec.fileName}' is outside the plan bundle.")
    return target.directory.resolve(relative)
  }
}
