package skillbill.engine.apprun

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.apprun.AppRunScriptContract
import skillbill.engine.apprun.model.AppRunRefusalReason
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.execution.QaNamedDevice
import skillbill.engine.featuretask.model.execution.QaNamedDeviceKind
import skillbill.engine.goal.GoalManifestProjectionReader
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goal.GoalRunnerStatusPort
import skillbill.engine.goal.GoalRunnerStatusRequest
import skillbill.engine.work.OPEN_CI_MONITOR_STEP
import skillbill.error.core.catchingNonCancellation
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.branchworktree.BranchWorktreeResources
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeCreation
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolder
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolderKind
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeRelease
import skillbill.ports.workflow.goalstate.GoalRuntimeStateFileStore
import skillbill.scaffold.model.QaTargetKind
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

public sealed interface AppRunTarget {
  public data class Ready(
    val recipe: AppRunRecipe,
    val checkout: Path,
    val detachedHolder: BranchWorktreeHolder?,
  ) : AppRunTarget

  public data class Refused(val reason: AppRunRefusalReason, val message: String) : AppRunTarget
}

@Inject
public class AppRunTargetResolver(
  private val manifestStore: GoalManifestProjectionReader,
  private val goalStatus: GoalRunnerStatusPort,
  private val capabilities: AppRunCapabilityResolver,
  private val branchWorktrees: BranchWorktreeResources,
  private val stateFiles: GoalRuntimeStateFileStore,
  private val diagnostics: RuntimeDiagnostics,
) {
  public fun goalRoutingInput(
    repoRoot: Path,
    issueKey: String,
  ): AppRunGoalRoutingInputResult {
    val manifest =
      manifestStore.read(issueKey, repoRoot)
        ?: return routingInputFailure(issueKey, "No goal named $issueKey exists in this repository.")
    val status =
      goalStatus.status(
        GoalRunnerStatusRequest(
          issueKey,
          repoRoot = repoRoot,
          pruneCheckpointRefs = false,
          includeCompletedSubtaskValidation = false,
        ),
      )
    if (status?.admittedParentComplete() == true) {
      val branch =
        manifest.manifest.featureBranch
          ?: return routingInputFailure(issueKey, "The completed goal has no feature branch.")
      return when (val input = capabilities.completedRoutingInput(repoRoot, branch)) {
        is AppRunGoalRoutingInputResult.Failed -> routingInputFailure(issueKey, input.reason)
        is AppRunGoalRoutingInputResult.Ready -> input
      }
    }
    return monitoringRoutingInput(repoRoot, issueKey, manifest, status)
  }

  private fun monitoringRoutingInput(
    repoRoot: Path,
    issueKey: String,
    manifest: GoalRunnerManifestState,
    status: GoalRunnerStatusProjection?,
  ): AppRunGoalRoutingInputResult {
    val monitoring =
      status?.let {
        it.currentStep == OPEN_CI_MONITOR_STEP && !it.paused && it.executionLiveness == ExecutionLiveness.LIVE
      } == true
    if (!monitoring) return routingInputFailure(issueKey, "The goal is not completed or actively monitoring.")
    val checkoutValue = manifest.controlState.executionWorktreePath
    if (checkoutValue == null || manifest.controlState.executionWorktreeRemovedAt != null ||
      !stateFiles.directoryExists(Path.of(checkoutValue))
    ) {
      return routingInputFailure(issueKey, "The live goal checkout is unavailable.")
    }
    val checkout = Path.of(checkoutValue)
    return liveRoutingInput(repoRoot, issueKey, checkout)
  }

  public fun resolve(
    repoRoot: Path,
    issueKey: String,
    device: QaNamedDevice,
    holder: BranchWorktreeHolder,
  ): AppRunTarget {
    val manifest =
      manifestStore.read(issueKey, repoRoot)
        ?: return refused(AppRunRefusalReason.UNKNOWN_GOAL, "No goal named $issueKey exists in this repository.")
    val status =
      goalStatus.status(
        GoalRunnerStatusRequest(
          issueKey,
          repoRoot = repoRoot,
          pruneCheckpointRefs = false,
          includeCompletedSubtaskValidation = false,
        ),
      )
    val completed = status?.admittedParentComplete() == true
    val monitoring =
      status?.let {
        it.currentStep == OPEN_CI_MONITOR_STEP && !it.paused && it.executionLiveness == ExecutionLiveness.LIVE
      } == true
    if (!completed && status?.currentStep == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX) {
      return refused(
        AppRunRefusalReason.CI_REPAIR_RUNNING,
        CI_REPAIR_COPY,
      )
    }
    if (!completed && !monitoring) return refused(AppRunRefusalReason.NOT_RUNNABLE, NOT_RUNNABLE_COPY)
    val selected = if (monitoring) monitoringCheckout(manifest) else detachedCheckout(repoRoot, manifest, holder)
    return when (selected) {
      is CheckoutSelection.Unavailable -> selected.refusal
      is CheckoutSelection.Available ->
        resolveRecipe(repoRoot, issueKey, device, selected.path, selected.holder)
    }
  }

  private sealed interface CheckoutSelection {
    data class Available(val path: Path, val holder: BranchWorktreeHolder?) : CheckoutSelection

    data class Unavailable(val refusal: AppRunTarget.Refused) : CheckoutSelection
  }

  private fun monitoringCheckout(manifest: GoalRunnerManifestState): CheckoutSelection {
    val path = manifest.controlState.executionWorktreePath
    return if (path == null || manifest.controlState.executionWorktreeRemovedAt != null ||
      !stateFiles.directoryExists(Path.of(path))
    ) {
      CheckoutSelection.Unavailable(
        refused(
          AppRunRefusalReason.CHECKOUT_UNAVAILABLE,
          "The goal checkout is no longer available.",
        ),
      )
    } else {
      CheckoutSelection.Available(Path.of(path), null)
    }
  }

  private fun detachedCheckout(
    repoRoot: Path,
    manifest: GoalRunnerManifestState,
    holder: BranchWorktreeHolder,
  ): CheckoutSelection {
    val branch =
      manifest.manifest.featureBranch ?: return CheckoutSelection.Unavailable(
        refused(AppRunRefusalReason.BRANCH_MISSING, "The completed goal has no feature branch."),
      )
    val detachedHolder = holder.copy(kind = BranchWorktreeHolderKind.APP_RUN)
    return when (val creation = branchWorktrees.createDetached(repoRoot, branch, detachedHolder)) {
      BranchWorktreeCreation.Missing ->
        CheckoutSelection.Unavailable(
          refused(AppRunRefusalReason.BRANCH_MISSING, "Branch '$branch' no longer exists."),
        )
      is BranchWorktreeCreation.Failed ->
        CheckoutSelection.Unavailable(
          refused(
            AppRunRefusalReason.WORKTREE_FAILED,
            creation.reason,
          ),
        )
      is BranchWorktreeCreation.Created ->
        CheckoutSelection.Available(creation.path, detachedHolder)
    }
  }

  private fun resolveRecipe(
    repoRoot: Path,
    issueKey: String,
    device: QaNamedDevice,
    checkout: Path,
    detachedHolder: BranchWorktreeHolder?,
  ): AppRunTarget {
    var transferred = false
    try {
      val input = liveRoutingInput(repoRoot, issueKey, checkout)
      val resolution =
        when (input) {
          is AppRunGoalRoutingInputResult.Failed ->
            AppRunCapabilityResolution.Incompatible(input.reason)
          is AppRunGoalRoutingInputResult.Ready -> capabilities.resolve(input.input)
        }
      val result =
        when (resolution) {
          is AppRunCapabilityResolution.Incompatible ->
            refused(AppRunRefusalReason.PACK_INCOMPATIBLE, resolution.reason)
          is AppRunCapabilityResolution.Absent ->
            refused(
              AppRunRefusalReason.NO_RECIPE,
              "No installed platform pack declares app run for " +
                "${resolution.routedPackSlug ?: "this repository"}.",
            )
          is AppRunCapabilityResolution.Declared ->
            selectRecipe(resolution, device, checkout, detachedHolder)
        }
      transferred = result is AppRunTarget.Ready
      return result
    } finally {
      if (!transferred) releaseDetached(checkout, detachedHolder)
    }
  }

  private fun selectRecipe(
    resolution: AppRunCapabilityResolution.Declared,
    device: QaNamedDevice,
    checkout: Path,
    holder: BranchWorktreeHolder?,
  ): AppRunTarget {
    val platform =
      when (device.kind) {
        QaNamedDeviceKind.SERIAL, QaNamedDeviceKind.AVD ->
          QaTargetKind(AppRunScriptContract.PLATFORM_ANDROID)
      }
    val candidates = resolution.recipes.filter { platform in it.platforms }
    if (candidates.isEmpty()) {
      return refused(
        AppRunRefusalReason.NO_RECIPE,
        "No installed app-run recipe supports Android.",
      )
    }
    if (candidates.size > 1) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "seam=app-run.recipe outcome=multiple selected=${candidates.first().packSlug} " +
          "others=${candidates.drop(1).joinToString { it.packSlug }}",
      )
    }
    return AppRunTarget.Ready(candidates.first(), checkout, holder)
  }

  private fun releaseDetached(
    path: Path,
    holder: BranchWorktreeHolder?,
  ) {
    if (holder == null) return
    catchingNonCancellation { branchWorktrees.release(path, holder) }.fold(
      onSuccess = { result ->
        when (result) {
          is BranchWorktreeRelease.Kept ->
            RuntimeDiagnosticsBestEffortWarning.record(
              diagnostics,
              "seam=app-run.worktree outcome=kept detail=${result.detail}",
            )
          BranchWorktreeRelease.StillHeld ->
            RuntimeDiagnosticsBestEffortWarning.record(
              diagnostics,
              "seam=app-run.worktree outcome=still_held path=$path",
            )
          BranchWorktreeRelease.NotHeld ->
            RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "seam=app-run.worktree outcome=not_held path=$path")
          is BranchWorktreeRelease.Removed -> Unit
        }
      },
      onFailure = { failure ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=app-run.worktree outcome=release_failed path=$path",
          failure,
        )
      },
    )
  }

  private fun refused(
    reason: AppRunRefusalReason,
    message: String,
  ) = AppRunTarget.Refused(reason, message)

  private fun routingInputFailure(
    issueKey: String,
    reason: String,
  ): AppRunGoalRoutingInputResult.Failed {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=app-run.routing_input outcome=unavailable issue=$issueKey reason=$reason",
    )
    return AppRunGoalRoutingInputResult.Failed(reason)
  }

  private fun liveRoutingInput(
    repoRoot: Path,
    issueKey: String,
    checkout: Path,
  ): AppRunGoalRoutingInputResult =
    when (val input = capabilities.liveRoutingInput(repoRoot, checkout)) {
      is AppRunGoalRoutingInputResult.Failed -> routingInputFailure(issueKey, input.reason)
      is AppRunGoalRoutingInputResult.Ready -> input
    }

  private companion object {
    const val CI_REPAIR_COPY =
      "Off while a CI repair edits the checkout. " +
        "Run app turns back on when monitoring resumes."
    const val NOT_RUNNABLE_COPY = "Run app turns on when the goal completes or reaches monitoring."
  }
}
