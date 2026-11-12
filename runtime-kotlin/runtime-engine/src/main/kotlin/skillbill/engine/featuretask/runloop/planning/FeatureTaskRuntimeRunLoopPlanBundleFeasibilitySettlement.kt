package skillbill.engine.featuretask.runloop.planning

import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityRepairAppliedWrite
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWrite
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWriteResult
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibility
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibilitySnapshot
import skillbill.engine.featuretask.phase.planning.PlanBundleTarget
import skillbill.engine.featuretask.phase.planning.feasibilityAcceptedLines
import skillbill.engine.featuretask.runloop.attempt.matchesProtectedBundleBytes
import skillbill.engine.featuretask.runloop.attempt.repairBaselineViolation
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.slot.attempt.PhaseOutputSettlementContext
import skillbill.engine.featuretask.slot.attempt.PlanBundleFeasibilityLaunchContext
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.PlanBundleFeasibilityFailureCode
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityApprovalCandidate
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityConflict
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityIdentity
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunch
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchKind
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchOutcome
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityRepositoryBaseline
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilitySettledLaunch
import skillbill.workflow.taskruntime.model.planning.blockPlanBundleFeasibilityLaunch
import skillbill.workflow.taskruntime.model.planning.refusePlanBundleFeasibilityRepair
import skillbill.workflow.taskruntime.model.planning.refusePlanBundleFeasibilityReview
import skillbill.workflow.taskruntime.model.planning.settlePlanBundleFeasibilityRepair
import skillbill.workflow.taskruntime.model.planning.settlePlanBundleFeasibilityReview
import java.nio.file.Path

internal object FeatureTaskRuntimeRunLoopPlanBundleFeasibilitySettlement {
  fun refuseUnusableReview(
    context: PhaseOutputSettlementContext,
    run: PhaseRun,
    attempt: Int,
    reason: String,
  ): Boolean {
    val current = context.recorder.loadPlanBundleFeasibility(run.request.workflowId) ?: return false
    val launch =
      current.activeLaunch?.takeIf {
        it.phaseId == run.phaseId
      } ?: return false
    val authorityFailure = unusableOutputAuthorityViolation(context, run, launch)
    if (authorityFailure != null) return blockUnusableLaunch(context, run, attempt, current, authorityFailure)

    return refuseUnusableLaunch(context, run, attempt, current, reason)
  }

  private fun blockUnusableLaunch(
    context: PhaseOutputSettlementContext,
    run: PhaseRun,
    attempt: Int,
    current: FeatureTaskRuntimePlanBundleFeasibilityState,
    reason: String,
  ): Boolean {
    val blocked =
      blockPlanBundleFeasibilityLaunch(
        current,
        checkNotNull(current.activeLaunch).transitionId,
        reason,
      ) ?: return false
    return when (
      context.recorder.writePlanBundleFeasibility(
        PlanBundleFeasibilityStateWrite(
          run.request.workflowId,
          run.phaseId,
          attempt,
          current.revision,
          checkNotNull(current.activeLaunch).transitionId,
          blocked,
        ),
      )
    ) {
      is PlanBundleFeasibilityStateWriteResult.Applied,
      is PlanBundleFeasibilityStateWriteResult.Replayed,
      -> true
      is PlanBundleFeasibilityStateWriteResult.Existing -> false
      is PlanBundleFeasibilityStateWriteResult.Refused -> false
    }
  }

  private fun refuseUnusableLaunch(
    context: PhaseOutputSettlementContext,
    run: PhaseRun,
    attempt: Int,
    current: FeatureTaskRuntimePlanBundleFeasibilityState,
    reason: String,
  ): Boolean {
    val refused =
      when (checkNotNull(current.activeLaunch).kind) {
        PlanBundleFeasibilityLaunchKind.REVIEW ->
          refusePlanBundleFeasibilityReview(
            current,
            checkNotNull(current.activeLaunch).transitionId,
            reason,
          )
        PlanBundleFeasibilityLaunchKind.REPAIR ->
          refusePlanBundleFeasibilityRepair(
            current,
            checkNotNull(current.activeLaunch).transitionId,
            reason,
          )
      } ?: return false
    return when (
      val result =
        context.recorder.writePlanBundleFeasibility(
          PlanBundleFeasibilityStateWrite(
            run.request.workflowId,
            run.phaseId,
            attempt,
            current.revision,
            checkNotNull(current.activeLaunch).transitionId,
            refused,
          ),
        )
    ) {
      is PlanBundleFeasibilityStateWriteResult.Applied -> true
      is PlanBundleFeasibilityStateWriteResult.Replayed -> true
      is PlanBundleFeasibilityStateWriteResult.Existing ->
        refusedReviewWriteWarning(context, run, attempt, "unexpected existing state")
      is PlanBundleFeasibilityStateWriteResult.Refused ->
        refusedReviewWriteWarning(context, run, attempt, result.reason)
    }
  }

  private fun unusableOutputAuthorityViolation(
    context: PhaseOutputSettlementContext,
    run: PhaseRun,
    launch: PlanBundleFeasibilityLaunch,
  ): String? {
    val request = run.request
    val planner = context.decompositionPlanner
    val canonical = PlanBundleFeasibility.capture(planner, request.repoRoot, request.issueKey)
    val canonicalPath =
      (PlanBundleStagingPromotion.targetFor(planner, run) as? PlanBundleTarget.Staged)
        ?.storeDirectory?.toAbsolutePath()?.normalize()
    val repairDirectory = launch.repairDirectory?.let(Path::of)?.toAbsolutePath()?.normalize()
    val stagingRoot =
      planner.planStagingRoot(request.repoRoot.toAbsolutePath().normalize())
        .resolve(request.workflowId).toAbsolutePath().normalize()
    val staged = repairDirectory?.takeIf { it != canonicalPath && it.startsWith(stagingRoot) }
    val stagedSnapshot =
      staged?.takeIf { planner.bundleTree(it).isNotEmpty() }?.let {
        PlanBundleFeasibility.captureInDirectory(planner, request.repoRoot, request.issueKey, it)
      }
    val observed = stagedSnapshot ?: canonical
    val baseline = launch.baselineIdentity
    val bundleViolation =
      if (launch.kind == PlanBundleFeasibilityLaunchKind.REVIEW) {
        if (canonical.identity != baseline || observed.identity != baseline) {
          "Read-only feasibility review changed its admitted bundle baseline or repair staging."
        } else {
          reviewStagingViolation(planner, staged, stagedSnapshot)
        }
      } else {
        repairAuthorityViolation(baseline, canonical, observed, staged != null)
      }
    if (bundleViolation != null) return bundleViolation
    val pending = observed.manifest.subtasks.filter { it.status.decompositionStatus() == DecompositionStatus.PENDING }
    val allowed =
      if (launch.kind == PlanBundleFeasibilityLaunchKind.REPAIR && staged == null) {
        val root = request.repoRoot.toAbsolutePath().normalize()
        pending.map {
          root.relativize(planner.bundlePath(root, it.specPath).toAbsolutePath().normalize()).toString()
        }.toSet()
      } else {
        emptySet()
      }
    return repositoryAuthorityViolation(context, request.repoRoot, launch, allowed)
  }

  private fun reviewStagingViolation(
    planner: FeatureTaskRuntimeDecompositionPlanner,
    staged: Path?,
    snapshot: PlanBundleFeasibilitySnapshot?,
  ): String? {
    if (snapshot == null) return null
    val expected =
      setOf(
        Path.of(snapshot.manifest.parentSpecPath).fileName.toString(),
        DECOMPOSITION_MANIFEST_FILENAME,
      ) +
        snapshot.manifest.subtasks.map { Path.of(it.specPath).fileName.toString() }
    val directory = checkNotNull(staged)
    val actual = planner.bundleTree(directory).map { directory.relativize(it).toString().replace('\\', '/') }.toSet()
    return if (actual != expected.toSet()) {
      "Read-only feasibility review changed repair staging file authority."
    } else {
      null
    }
  }

  private fun repairAuthorityViolation(
    baseline: PlanBundleFeasibilityIdentity,
    canonical: PlanBundleFeasibilitySnapshot,
    observed: PlanBundleFeasibilitySnapshot,
    staged: Boolean,
  ): String? {
    if (!matchesProtectedBundleBytes(canonical.identity, baseline)) {
      return "Feasibility repair changed protected parent or manifest bytes."
    }
    val protectedViolation = repairBaselineViolation(baseline, observed)
    return when {
      protectedViolation != null -> "Feasibility repair changed its protected baseline: $protectedViolation."
      staged && canonical.identity != baseline -> "Staged feasibility repair changed canonical bundle files."
      else -> null
    }
  }

  fun settle(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    launchContext: PlanBundleFeasibilityLaunchContext,
  ): AttemptResult? {
    val before = launchContext.snapshot
    val repairing = launchContext.repairing
    val planner = context.decompositionPlanner
    val request = context.request
    val selectedTarget = PlanBundleStagingPromotion.targetFor(planner, capture.run)
    val target = if (repairing) admittedRepairTarget(planner, capture, before, selectedTarget) else selectedTarget
    if (repairing && target == null) {
      return block(context, capture, launchContext, "The admitted repair directory cannot be restored safely.")
    }
    val launch =
      context.recorder.loadPlanBundleFeasibility(request.workflowId)?.activeLaunch
        ?.takeIf { it.transitionId == launchContext.transitionId }
        ?: return continueFeasibility(capture, "Feasibility artifact or its active launch is no longer present.")
    val directory = (target as? PlanBundleTarget.Staged)?.directory.takeIf { repairing }
    val after = PlanBundleFeasibility.captureInDirectory(planner, request.repoRoot, request.issueKey, directory)
    val allowedRepositoryPaths =
      if (repairing && target !is PlanBundleTarget.Staged) {
        pendingRepositoryPaths(context, before)
      } else {
        emptySet()
      }
    val violation =
      repositoryAuthorityViolation(context, request.repoRoot, launch, allowedRepositoryPaths)
        ?: fileManifestAuthorityViolation(context, capture, launchContext, target)
        ?: before.repairViolation(after).takeIf { repairing }
    if (violation != null) return block(context, capture, launchContext, violation)
    return if (repairing) {
      repair(context, capture, launchContext, RepairCandidate(after, target, attested.output.value))
    } else {
      review(context, capture, attested, launchContext, after)
    }
  }

  private fun pendingRepositoryPaths(
    context: PhaseOutputSettlementContext,
    snapshot: PlanBundleFeasibilitySnapshot,
  ): Set<String> {
    val root = context.request.repoRoot.toAbsolutePath().normalize()
    return snapshot.manifest.subtasks.filter { it.status.decompositionStatus() == DecompositionStatus.PENDING }
      .map {
        root.relativize(
          context.decompositionPlanner.bundlePath(root, it.specPath).toAbsolutePath().normalize(),
        ).toString()
      }.toSet()
  }

  private fun fileManifestAuthorityViolation(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    launch: PlanBundleFeasibilityLaunchContext,
    target: PlanBundleTarget?,
  ): String? {
    val directory = (target as? PlanBundleTarget.Staged)?.directory
    val allowed =
      if (launch.repairing) {
        launch.snapshot.manifest.subtasks.filter { it.status.decompositionStatus() == DecompositionStatus.PENDING }
          .map { subtask ->
            val canonical = context.decompositionPlanner.bundlePath(context.request.repoRoot, subtask.specPath)
            if (directory == null) {
              canonical
            } else {
              val storeRoot = target.storeDirectory.toAbsolutePath().normalize()
              directory.resolve(storeRoot.relativize(canonical.toAbsolutePath().normalize())).normalize()
            }
          }.toSet()
      } else {
        emptySet()
      }
    val changed =
      capture.fileManifest.introduced +
        (capture.fileManifest.before.toSet() - capture.fileManifest.after.toSet())
    val unauthorized = changed.filter { context.request.repoRoot.resolve(it).normalize() !in allowed }
    return unauthorized.takeIf { it.isNotEmpty() }?.let {
      "Bundle feasibility changed unauthorized files: ${it.joinToString()}."
    }
  }

  private fun review(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    launchContext: PlanBundleFeasibilityLaunchContext,
    after: PlanBundleFeasibilitySnapshot,
  ): AttemptResult? {
    val before = launchContext.snapshot
    if (before.copy(repairDirectory = null) != after.copy(repairDirectory = null)) {
      return block(context, capture, launchContext, "The read-only feasibility review changed the bundle.")
    }
    val current =
      context.recorder.loadPlanBundleFeasibility(context.request.workflowId)
        ?: return continueFeasibility(capture, "Feasibility receipt was not recorded because its artifact is missing.")
    val retained = current.settledLaunches.firstOrNull { it.transitionId == launchContext.transitionId }
    val originalValue = retained?.takeIf { it.outcome == PlanBundleFeasibilityLaunchOutcome.COMPLETED }?.value
    return when (
      val parsed =
        PlanBundleFeasibility.parseReviewReceipt(
          before,
          originalValue ?: attested.output.value,
          launchContext.projection?.requiredRefs ?: current.requiredRefs,
        )
    ) {
      is PlanBundleFeasibility.ReviewParseResult.Rejected ->
        if (retained != null) {
          continueFeasibility(capture, "Feasibility replay retained a refused review: ${retained.value}")
        } else {
          refuseReview(context, capture, launchContext, current, parsed.reason)
        }
      is PlanBundleFeasibility.ReviewParseResult.Accepted ->
        settleAcceptedReview(
          context,
          capture,
          attested,
          launchContext,
          ReviewedReceipt(current, retained, parsed.receipt),
        )
    }
  }

  private data class ReviewedReceipt(
    val current: FeatureTaskRuntimePlanBundleFeasibilityState,
    val retained: PlanBundleFeasibilitySettledLaunch?,
    val receipt: PlanBundleFeasibility.ReviewReceipt,
  )

  private fun refuseReview(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    launch: PlanBundleFeasibilityLaunchContext,
    current: FeatureTaskRuntimePlanBundleFeasibilityState,
    reason: String,
  ): AttemptResult {
    val refused =
      refusePlanBundleFeasibilityReview(current, launch.transitionId, reason)
        ?: return continueFeasibility(capture, "Feasibility review refusal did not match its admitted transition.")
    return when (val result = writeSettlementState(context, capture, launch, refused)) {
      is PlanBundleFeasibilityStateWriteResult.Applied ->
        continueFeasibility(capture, PlanBundleFeasibility.REVIEW_REQUIRED + "\n" + reason)
      is PlanBundleFeasibilityStateWriteResult.Replayed ->
        continueFeasibility(capture, "Feasibility review replay retained ${result.outcome} evidence.")
      is PlanBundleFeasibilityStateWriteResult.Existing ->
        continueFeasibility(capture, "Feasibility review refusal was not written.")
      is PlanBundleFeasibilityStateWriteResult.Refused ->
        continueFeasibility(capture, "Feasibility review refusal was rejected: ${result.reason}")
    }
  }

  private fun writeSettlementState(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    launch: PlanBundleFeasibilityLaunchContext,
    state: FeatureTaskRuntimePlanBundleFeasibilityState,
  ): PlanBundleFeasibilityStateWriteResult =
    context.recorder.writePlanBundleFeasibility(
      PlanBundleFeasibilityStateWrite(
        context.request.workflowId,
        capture.run.phaseId,
        launch.attempt,
        launch.expectedRevision,
        launch.transitionId,
        state,
      ),
    )

  private fun settleAcceptedReview(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    launch: PlanBundleFeasibilityLaunchContext,
    reviewed: ReviewedReceipt,
  ): AttemptResult? {
    val retained = reviewed.retained
    if (retained?.outcome == PlanBundleFeasibilityLaunchOutcome.REFUSED) {
      return continueFeasibility(capture, "Feasibility replay retained a refused review: ${retained.value}")
    }
    val baseline =
      reviewed.current.activeLaunch?.takeIf { it.transitionId == launch.transitionId }?.baselineIdentity
        ?: retained?.baselineIdentity
        ?: return continueFeasibility(capture, "Feasibility receipt has no retained admitted baseline.")
    if (baseline != launch.snapshot.identity) {
      return continueFeasibility(capture, "Feasibility receipt baseline differs from its admitted snapshot.")
    }
    val proposed =
      if (retained == null) {
        acceptedReviewState(capture, attested, launch, reviewed)
      } else {
        reviewed.current
      }
    return if (proposed == null) {
      continueFeasibility(capture, "Feasibility receipt did not match an active admitted review.")
    } else {
      val result = writeSettlementState(context, capture, launch, proposed)
      val authoritative = authoritativeReviewReceipt(result, launch.snapshot, launch.transitionId, reviewed.receipt)
      when (authoritative) {
        is PlanBundleFeasibility.ReviewParseResult.Rejected -> continueFeasibility(capture, authoritative.reason)
        is PlanBundleFeasibility.ReviewParseResult.Accepted ->
          authoritative.receipt.takeUnless { it.approved }?.let {
            continueFeasibility(
              capture,
              PlanBundleFeasibility.REPAIR_REQUIRED + "\n" + it.conflictLines.joinToString("\n"),
            )
          }
      }
    }
  }

  private fun acceptedReviewState(
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    launch: PlanBundleFeasibilityLaunchContext,
    reviewed: ReviewedReceipt,
  ): FeatureTaskRuntimePlanBundleFeasibilityState? {
    val projection = launch.projection
    val currentRefs = launch.snapshot.criterionRefs
    val successful =
      (projection?.carriedLines ?: emptyList()) +
        feasibilityAcceptedLines(launch.snapshot.identity, reviewed.receipt)
    val conflicts =
      reviewed.receipt.lines.filter { it.conflict }
        .map { PlanBundleFeasibilityConflict(it.ref, it.originalLine) }
    val orderedSuccessful = successful.sortedBy { currentRefs.indexOf(it.ref) }
    val complete = conflicts.isEmpty() && orderedSuccessful.map { it.ref } == currentRefs
    val candidate =
      if (reviewed.receipt.approved && complete) {
        PlanBundleFeasibilityApprovalCandidate(
          sourceRevision = reviewed.current.revision + 1,
          immutableFingerprint = launch.snapshot.fingerprint,
          transitionId = launch.transitionId,
          phaseId = capture.run.phaseId,
          attempt = launch.attempt,
          reviewerAgentId = capture.run.resolvedAgent.resolvedAgentId,
          normalizedEnvelope = attested.canonicalJson,
          fileManifestBefore = capture.fileManifest.before,
          fileManifestAfter = capture.fileManifest.after,
          fileManifestIntroduced = capture.fileManifest.introduced,
        )
      } else {
        null
      }
    return settlePlanBundleFeasibilityReview(
      reviewed.current,
      launch.transitionId,
      orderedSuccessful,
      conflicts,
      reviewed.receipt.originalValue,
    )?.copy(
      approvalCandidate = candidate,
      requiredRefs = if (complete) currentRefs else projection?.requiredRefs ?: currentRefs,
      carriedRefs = projection?.carriedRefs ?: emptyList(),
      changedSubtaskIds = projection?.changedSubtaskIds ?: emptyList(),
    )
  }

  private fun authoritativeReviewReceipt(
    result: PlanBundleFeasibilityStateWriteResult,
    snapshot: PlanBundleFeasibilitySnapshot,
    transitionId: String,
    receipt: PlanBundleFeasibility.ReviewReceipt,
  ): PlanBundleFeasibility.ReviewParseResult =
    when (result) {
      is PlanBundleFeasibilityStateWriteResult.Applied -> PlanBundleFeasibility.ReviewParseResult.Accepted(receipt)
      is PlanBundleFeasibilityStateWriteResult.Replayed -> {
        val archived = result.state.settledLaunches.firstOrNull { it.transitionId == transitionId }
        if (archived == null) {
          PlanBundleFeasibility.ReviewParseResult.Rejected("Feasibility replay has no retained receipt.")
        } else {
          PlanBundleFeasibility.parseReviewReceipt(snapshot, archived.value, receipt.lines.map { it.ref })
        }
      }
      is PlanBundleFeasibilityStateWriteResult.Existing ->
        PlanBundleFeasibility.ReviewParseResult.Rejected("Feasibility receipt write unexpectedly found existing state.")
      is PlanBundleFeasibilityStateWriteResult.Refused ->
        PlanBundleFeasibility.ReviewParseResult.Rejected("Feasibility receipt was refused: ${result.reason}")
    }

  private data class RepairCandidate(
    val snapshot: PlanBundleFeasibilitySnapshot,
    val target: PlanBundleTarget?,
    val value: String,
  )

  private sealed interface RepairIntentPreparation {
    data class Ready(val state: FeatureTaskRuntimePlanBundleFeasibilityState) : RepairIntentPreparation

    data class Unavailable(val reason: String) : RepairIntentPreparation

    data class Unsafe(val reason: String) : RepairIntentPreparation

    data class InvalidOutput(val reason: String) : RepairIntentPreparation
  }

  private fun repair(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    launch: PlanBundleFeasibilityLaunchContext,
    candidate: RepairCandidate,
  ): AttemptResult {
    val intent = prepareRepairIntent(context, capture, launch, candidate)
    return when (intent) {
      is RepairIntentPreparation.Unavailable -> continueFeasibility(capture, intent.reason)
      is RepairIntentPreparation.Unsafe -> block(context, capture, launch, intent.reason)
      is RepairIntentPreparation.InvalidOutput -> refuseRepair(context, capture, launch, intent.reason)
      is RepairIntentPreparation.Ready -> {
        val violation = projectRepair(context, capture, launch, candidate)
        if (violation != null) {
          block(context, capture, launch, violation)
        } else {
          recordProjectedRepair(context, capture, launch, candidate, intent.state.revision)
        }
      }
    }
  }

  private fun prepareRepairIntent(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    launch: PlanBundleFeasibilityLaunchContext,
    candidate: RepairCandidate,
  ): RepairIntentPreparation {
    val current =
      context.recorder.loadPlanBundleFeasibility(context.request.workflowId)
        ?: return RepairIntentPreparation.Unavailable("Feasibility repair state disappeared before intent recording.")
    val active =
      current.activeLaunch?.takeIf { it.transitionId == launch.transitionId }
        ?: return RepairIntentPreparation.Unavailable("Feasibility repair intent has no active admitted launch.")
    val violation = stagedIntentViolation(context, candidate, active)
    if (violation != null) return RepairIntentPreparation.Unsafe(violation)
    return if (!PlanBundleFeasibility.reportsRepaired(candidate.value)) {
      RepairIntentPreparation.InvalidOutput(PlanBundleFeasibility.REPAIR_HEADER_REFUSAL)
    } else if (active.intendedAfterIdentity != null) {
      RepairIntentPreparation.Ready(current)
    } else {
      val prepared =
        current.copy(
          revision = current.revision + 1,
          activeLaunch =
            active.copy(
              intendedAfterIdentity = candidate.snapshot.identity,
              intendedRepairValue = candidate.value,
              repairBeforeIdentity =
                if (candidate.target is PlanBundleTarget.Staged) {
                  active.baselineIdentity
                } else {
                  launch.snapshot.identity
                },
            ),
        )
      when (
        val result =
          writeSettlementState(
            context,
            capture,
            launch.copy(expectedRevision = current.revision),
            prepared,
          )
      ) {
        is PlanBundleFeasibilityStateWriteResult.Applied -> RepairIntentPreparation.Ready(result.state)
        is PlanBundleFeasibilityStateWriteResult.Replayed -> RepairIntentPreparation.Ready(result.state)
        is PlanBundleFeasibilityStateWriteResult.Existing ->
          RepairIntentPreparation.Unsafe("The repair intent was not recorded before projection.")
        is PlanBundleFeasibilityStateWriteResult.Refused ->
          RepairIntentPreparation.Unsafe("The repair intent was refused before projection: ${result.reason}")
      }
    }
  }

  private fun stagedIntentViolation(
    context: PhaseOutputSettlementContext,
    candidate: RepairCandidate,
    active: PlanBundleFeasibilityLaunch,
  ): String? {
    if (active.intendedAfterIdentity != null && active.intendedAfterIdentity != candidate.snapshot.identity) {
      return "The repair output disagrees with its retained intended identity."
    }
    if (candidate.target !is PlanBundleTarget.Staged) return null
    val canonical =
      PlanBundleFeasibility.capture(
        context.decompositionPlanner,
        context.request.repoRoot,
        context.request.issueKey,
      )
    val expectedBefore = active.repairBeforeIdentity ?: active.baselineIdentity
    return when {
      active.intendedAfterIdentity == null && canonical.identity != active.baselineIdentity ->
        "Canonical bundle files no longer match the staged-repair admission baseline."
      active.intendedAfterIdentity != null && expectedBefore != active.baselineIdentity ->
        "The staged repair intent replaced its original canonical baseline."
      active.intendedAfterIdentity != null && canonical.identity != active.baselineIdentity &&
        canonical.identity != active.intendedAfterIdentity ->
        "Canonical bundle files match neither the staged-repair baseline nor its intended result."
      else -> null
    }
  }

  private fun projectRepair(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    launch: PlanBundleFeasibilityLaunchContext,
    candidate: RepairCandidate,
  ): String? {
    val planner = context.decompositionPlanner
    val request = context.request
    val target = candidate.target
    val canonicalBefore =
      if (target is PlanBundleTarget.Staged) {
        PlanBundleFeasibility.capture(planner, request.repoRoot, request.issueKey)
      } else {
        launch.snapshot
      }
    if (target is PlanBundleTarget.Staged && canonicalBefore.identity != candidate.snapshot.identity) {
      val promotion =
        PromotionRequest(
          target,
          request.repoRoot,
          capture.run.resolvedAgent.resolvedAgentId,
          parentSpecProtected = true,
        )
      if (!PlanBundleStagingPromotion.promoteFeasibilityRepair(
          planner,
          promotion,
          request.issueKey,
          canonicalBefore,
          candidate.snapshot,
        )
      ) {
        return "Staged feasibility repair promotion failed; the admitted baseline remains authoritative."
      }
    }
    val postRepair = PlanBundleFeasibility.capture(planner, request.repoRoot, request.issueKey)
    val activeIntent =
      context.recorder.loadPlanBundleFeasibility(request.workflowId)?.activeLaunch
        ?.takeIf { it.transitionId == launch.transitionId }
        ?: return "Feasibility repair intent disappeared after projection."
    if (activeIntent.intendedAfterIdentity != postRepair.identity) {
      return "The projected bundle differs from its retained intended identity."
    }
    val projectedPaths =
      postRepair.manifest.subtasks
        .filter { it.status.decompositionStatus() == DecompositionStatus.PENDING }
        .map { subtask ->
          request.repoRoot.toAbsolutePath().normalize()
            .relativize(planner.bundlePath(request.repoRoot, subtask.specPath).toAbsolutePath().normalize()).toString()
        }.toSet()
    return repositoryAuthorityViolation(context, request.repoRoot, activeIntent, projectedPaths)
  }

  private fun recordProjectedRepair(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    launch: PlanBundleFeasibilityLaunchContext,
    candidate: RepairCandidate,
    appliedRevision: Int,
  ): AttemptResult {
    val request = context.request
    val postRepair = PlanBundleFeasibility.capture(context.decompositionPlanner, request.repoRoot, request.issueKey)
    val current =
      context.recorder.loadPlanBundleFeasibility(request.workflowId)
        ?: return continueFeasibility(capture, "Feasibility repair state disappeared after projection.")
    val proposed =
      settlePlanBundleFeasibilityRepair(current, launch.transitionId, postRepair.identity, candidate.value)
        ?: return continueFeasibility(capture, "Feasibility repair did not match its admitted transition.")
    val bundle =
      PlanDecompositionStop.prepareFeasibilityRepairBundle(context).getOrElse { error ->
        return block(
          context,
          capture,
          launch,
          "The repaired bundle could not be prepared for recording: ${error.message.orEmpty()}",
        )
      }
    return when (
      val result =
        context.recorder.recordFeasibilityRepairApplied(
          PlanBundleFeasibilityRepairAppliedWrite(
            workflowId = request.workflowId,
            phaseId = capture.run.phaseId,
            attempt = launch.attempt,
            expectedRevision = appliedRevision,
            transitionId = launch.transitionId,
            intendedIdentity = postRepair.identity,
            bundle = bundle,
            state = proposed,
          ),
        )
    ) {
      is PlanBundleFeasibilityStateWriteResult.Applied,
      is PlanBundleFeasibilityStateWriteResult.Replayed,
      -> {
        val target = candidate.target
        if (target is PlanBundleTarget.Staged) {
          PlanBundleStagingPromotion.finishStaging(
            context.decompositionPlanner,
            context.diagnostics,
            PromotionRequest(
              target,
              request.repoRoot,
              capture.run.resolvedAgent.resolvedAgentId,
              parentSpecProtected = true,
            ),
          )
        }
        continueFeasibility(capture, PlanBundleFeasibility.REVIEW_REQUIRED)
      }
      is PlanBundleFeasibilityStateWriteResult.Existing ->
        continueFeasibility(capture, "Feasibility repair state was not recorded.")
      is PlanBundleFeasibilityStateWriteResult.Refused ->
        continueFeasibility(capture, "Feasibility repair state was refused: ${result.reason}")
    }
  }

  private fun refuseRepair(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    launchContext: PlanBundleFeasibilityLaunchContext,
    reason: String,
  ): AttemptResult {
    val expectedRevision = launchContext.expectedRevision
    val transitionId = launchContext.transitionId
    val attempt = launchContext.attempt

    val current =
      context.recorder.loadPlanBundleFeasibility(context.request.workflowId)
        ?: return continueFeasibility(capture, reason)
    val refused =
      refusePlanBundleFeasibilityRepair(current, transitionId, reason)
        ?: return continueFeasibility(capture, reason)
    return when (
      val result =
        context.recorder.writePlanBundleFeasibility(
          PlanBundleFeasibilityStateWrite(
            context.request.workflowId,
            capture.run.phaseId,
            attempt,
            expectedRevision,
            transitionId,
            refused,
          ),
        )
    ) {
      is PlanBundleFeasibilityStateWriteResult.Applied,
      is PlanBundleFeasibilityStateWriteResult.Replayed,
      is PlanBundleFeasibilityStateWriteResult.Existing,
      -> continueFeasibility(capture, reason)
      is PlanBundleFeasibilityStateWriteResult.Refused ->
        continueFeasibility(capture, "Feasibility repair refusal was rejected: ${result.reason}")
    }
  }

  private fun admittedRepairTarget(
    planner: FeatureTaskRuntimeDecompositionPlanner,
    capture: ValidatedOutputCapture,
    before: PlanBundleFeasibilitySnapshot,
    selected: PlanBundleTarget?,
  ): PlanBundleTarget? {
    val admittedDirectory = before.repairDirectory ?: return selected
    val storeDirectory =
      when (selected) {
        is PlanBundleTarget.Staged -> selected.storeDirectory
        is PlanBundleTarget.Store -> selected.directory
        is PlanBundleTarget.Legacy -> selected.directory
        null -> return null
      }.toAbsolutePath().normalize()
    val admitted = admittedDirectory.toAbsolutePath().normalize()
    if (admitted == storeDirectory) {
      return if (selected is PlanBundleTarget.Legacy) {
        PlanBundleTarget.Legacy(admitted)
      } else {
        PlanBundleTarget.Store(admitted)
      }
    }
    val stagingRoot =
      planner.planStagingRoot(capture.run.request.repoRoot.toAbsolutePath().normalize())
        .resolve(capture.run.request.workflowId).toAbsolutePath().normalize()
    return if (admitted.startsWith(
        stagingRoot,
      )
    ) {
      PlanBundleTarget.Staged(stagingRoot, admitted, storeDirectory)
    } else {
      null
    }
  }

  private fun continueFeasibility(
    capture: ValidatedOutputCapture,
    reason: String,
  ): AttemptResult = AttemptResult.BundleFeasibilityContinuation(reason, capture.fileManifest)

  private fun repositoryAuthorityViolation(
    context: PhaseOutputSettlementContext,
    repoRoot: Path,
    launch: PlanBundleFeasibilityLaunch,
    allowedPaths: Set<String>,
  ): String? {
    val baseline =
      launch.repositoryBaseline
        ?: return "The admitted feasibility launch has no original repository evidence; restore its source backup."
    val status = context.gitOperations.worktreeStatus(repoRoot)
    val head = context.gitOperations.runtimePhaseHeadCommit(repoRoot)
    return when {
      status !is WorkflowGitOperationResult.Ok -> "Could not observe feasibility worktree status: ${status.error}"
      head !is WorkflowGitOperationResult.Ok -> "Could not observe feasibility repository HEAD: ${head.error}"
      head.value != baseline.headCommit ->
        "The repository HEAD changed during feasibility work; original commits and history are protected."
      else -> repositoryChangedPathViolation(context, repoRoot, baseline, allowedPaths, status.value.orEmpty())
    }
  }

  private fun repositoryChangedPathViolation(
    context: PhaseOutputSettlementContext,
    repoRoot: Path,
    baseline: PlanBundleFeasibilityRepositoryBaseline,
    allowedPaths: Set<String>,
    statusText: String,
  ): String? {
    val currentPaths =
      (
        FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(statusText) +
          FeatureTaskRuntimePhaseSafetyPolicy.deletedPaths(statusText)
      ).distinct().sorted()
    val currentIdentities =
      when (val result = context.gitOperations.pathContentIdentities(repoRoot, currentPaths)) {
        is WorkflowPathContentIdentitiesResult.Resolved -> result.identities
        is WorkflowPathContentIdentitiesResult.Failed ->
          return "Could not observe feasibility file identities: ${result.error}"
      }
    val changed =
      buildSet {
        addAll(baseline.dirtyPaths.toSet() - currentPaths.toSet())
        addAll(currentPaths.toSet() - baseline.dirtyPaths.toSet())
        addAll(baseline.contentIdentities.keys - currentIdentities.keys)
        addAll(currentIdentities.keys - baseline.contentIdentities.keys)
        (baseline.contentIdentities.keys intersect currentIdentities.keys).forEach { path ->
          if (baseline.contentIdentities[path] != currentIdentities[path]) add(path)
        }
      }
    val unauthorized = changed.filterNot(allowedPaths::contains)
    return unauthorized.takeIf { it.isNotEmpty() }?.let {
      "Feasibility changed repository paths outside its admitted authority: ${it.sorted().joinToString()}."
    }
  }

  private fun refusedReviewWriteWarning(
    context: PhaseOutputSettlementContext,
    run: PhaseRun,
    attempt: Int,
    reason: String,
  ): Nothing {
    RuntimeDiagnosticsBestEffortWarning.record(
      context.diagnostics,
      "Could not close unusable feasibility review for workflow '${run.request.workflowId}', " +
        "phase '${run.phaseId}' attempt $attempt: $reason; its active launch remains preserved.",
    )
    throw SkillBillRuntimeException(
      PlanBundleFeasibilityFailureCode.INVALID_STATE,
      "Could not persist feasibility settlement for workflow '${run.request.workflowId}', " +
        "phase '${run.phaseId}' attempt $attempt: $reason. Preserve the admitted launch and recover with " +
        "current ownership.",
    )
  }

  private fun block(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
    launchContext: PlanBundleFeasibilityLaunchContext,
    reason: String,
  ): AttemptResult {
    val expectedRevision = launchContext.expectedRevision
    val transitionId = launchContext.transitionId
    val attempt = launchContext.attempt

    val current = context.recorder.loadPlanBundleFeasibility(context.request.workflowId)
    val blocked = current?.let { blockPlanBundleFeasibilityLaunch(it, transitionId, reason) }
    if (blocked != null) {
      when (
        val result =
          context.recorder.writePlanBundleFeasibility(
            PlanBundleFeasibilityStateWrite(
              context.request.workflowId,
              capture.run.phaseId,
              attempt,
              expectedRevision,
              transitionId,
              blocked,
            ),
          )
      ) {
        is PlanBundleFeasibilityStateWriteResult.Applied,
        is PlanBundleFeasibilityStateWriteResult.Replayed,
        -> Unit
        is PlanBundleFeasibilityStateWriteResult.Existing ->
          RuntimeDiagnosticsBestEffortWarning.record(
            context.diagnostics,
            "Feasibility block write found existing state for '${context.request.workflowId}'.",
          )
        is PlanBundleFeasibilityStateWriteResult.Refused ->
          RuntimeDiagnosticsBestEffortWarning.record(
            context.diagnostics,
            "Feasibility block write failed for '${context.request.workflowId}': ${result.reason}.",
          )
      }
    }
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
