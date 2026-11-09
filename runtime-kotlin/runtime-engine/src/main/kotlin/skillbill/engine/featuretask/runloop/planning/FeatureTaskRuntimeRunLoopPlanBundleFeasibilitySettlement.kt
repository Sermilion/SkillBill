package skillbill.engine.featuretask.runloop.planning

import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibility
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibilitySnapshot
import skillbill.engine.featuretask.phase.planning.PlanBundleTarget
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.slot.attempt.PhaseOutputSettlementContext
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import java.nio.file.Path

internal object FeatureTaskRuntimeRunLoopPlanBundleFeasibilitySettlement {
  fun settle(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    before: PlanBundleFeasibilitySnapshot,
    repairing: Boolean,
  ): AttemptResult? {
    val planner = context.decompositionPlanner
    val request = context.request
    val target = PlanBundleStagingPromotion.targetFor(planner, capture.run)
    val directory = (target as? PlanBundleTarget.Staged)?.directory.takeIf { repairing }
    val after = PlanBundleFeasibility.captureInDirectory(planner, request.repoRoot, request.issueKey, directory)
    val allowed =
      if (repairing) {
        before.manifest.subtasks.map { subtask ->
          directory?.resolve(
            Path.of(subtask.specPath).fileName,
          ) ?: planner.bundlePath(request.repoRoot, subtask.specPath)
        }.toSet() +
          checkNotNull(directory ?: planner.existingParentSpec(request.repoRoot, request.issueKey)?.parent)
            .resolve(DECOMPOSITION_MANIFEST_FILENAME)
      } else {
        emptySet()
      }
    val changed =
      capture.fileManifest.introduced +
        (capture.fileManifest.before.toSet() - capture.fileManifest.after.toSet())
    val unauthorized = changed.filter { request.repoRoot.resolve(it).normalize() !in allowed }
    if (unauthorized.isNotEmpty()) {
      return block(context, capture, "Bundle feasibility changed unauthorized files: ${unauthorized.joinToString()}.")
    }
    if (!repairing) return review(context, capture, attested, before, after)
    before.repairViolation(after)?.let { return block(context, capture, it) }
    return repair(context, capture, attested, before, target)
  }

  private fun review(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    before: PlanBundleFeasibilitySnapshot,
    after: PlanBundleFeasibilitySnapshot,
  ): AttemptResult? {
    if (before != after) return block(context, capture, "The read-only feasibility review changed the bundle.")
    val value = attested.output.value
    val conflicts = value.lines().drop(1).filter { it.startsWith("conflict ") }
    if (value.lineSequence().firstOrNull() ==
      "${PlanBundleFeasibility.PREFIX} changes_requested ${before.fingerprint}" && conflicts.isNotEmpty()
    ) {
      return retry(capture, PlanBundleFeasibility.REPAIR_REQUIRED + "\n" + conflicts.joinToString("\n"))
    }
    return PlanBundleFeasibility.approvalRejection(before, value)?.let {
      retry(capture, PlanBundleFeasibility.REVIEW_REQUIRED + "\n" + it)
    }
  }

  private fun repair(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    before: PlanBundleFeasibilitySnapshot,
    target: PlanBundleTarget?,
  ): AttemptResult {
    val planner = context.decompositionPlanner
    val request = context.request
    if (attested.output.value.lineSequence().firstOrNull() != PlanBundleFeasibility.REPAIRED) {
      return retry(
        capture,
        PlanBundleFeasibility.REPAIR_REQUIRED + "\nThe planner must report repaired, then await review.",
      )
    }
    if (target is PlanBundleTarget.Staged) {
      val current = PlanBundleFeasibility.capture(planner, request.repoRoot, request.issueKey)
      if (before.copy(repairDirectory = null) != current) {
        return block(context, capture, "The stored bundle changed during staged feasibility repair; promotion refused.")
      }
      PlanBundleStagingPromotion.promote(
        planner,
        context.diagnostics,
        PromotionRequest(
          target,
          request.repoRoot,
          capture.run.resolvedAgent.resolvedAgentId,
          parentSpecProtected = true,
        ),
      )
    }
    val rejection =
      PlanDecompositionStop.recordExistingBundleOrRejection(
        context,
        capture.run.phaseId,
      )
    return retry(
      capture,
      if (rejection == null) {
        PlanBundleFeasibility.REVIEW_REQUIRED
      } else {
        PlanBundleFeasibility.REPAIR_REQUIRED + "\n" + rejection
      },
    )
  }

  private fun retry(
    capture: ValidatedOutputCapture,
    reason: String,
  ): AttemptResult = AttemptResult.PlanBundleRejected(reason, capture.fileManifest)

  private fun block(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    reason: String,
  ): AttemptResult {
    val coupling = context.settlementCoupling()
    return AttemptResult.settled(
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
        coupling.progress,
        coupling.transitions,
        context.recorder,
        PhaseBlockRequest(capture.run, capture.iteration, reason, context.observability),
      ),
    )
  }
}
