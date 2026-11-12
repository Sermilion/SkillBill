package skillbill.engine.apprun

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

public typealias AppRunPlatformsMemo = MutableMap<Pair<AppRunGoalRoutingInput, List<PlatformManifest>>, List<String>>

@Inject
public class GoalAppRunPlatforms(
  private val resolver: AppRunCapabilityResolver,
  private val diagnostics: RuntimeDiagnostics,
) {
  public fun newPassMemo(): AppRunPlatformsMemo = mutableMapOf()

  public fun forGoal(
    isGoal: Boolean,
    repoRoot: Path,
    branch: String?,
    worktreePath: String?,
    memo: AppRunPlatformsMemo,
  ): List<String> {
    if (!isGoal) return emptyList()
    return resolveGoal(repoRoot, branch, worktreePath, memo)
  }

  private fun resolveGoal(
    repoRoot: Path,
    branch: String?,
    worktreePath: String?,
    memo: AppRunPlatformsMemo,
  ): List<String> {
    val routingResult =
      catchingNonCancellation { routingInput(repoRoot, branch, worktreePath) }.getOrElse { error ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=work_projection.app_run_platforms repo=$repoRoot value_used=empty reason=${error.message}",
          error,
        )
        return emptyList()
      }
    val input =
      when (routingResult) {
        is AppRunGoalRoutingInputResult.Ready -> routingResult.input
        is AppRunGoalRoutingInputResult.Failed -> {
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "seam=work_projection.app_run_platforms repo=$repoRoot value_used=empty reason=${routingResult.reason}",
          )
          return emptyList()
        }
      }
    val catalogResult = catchingNonCancellation { resolver.catalogManifests().toList() }
    val catalog =
      catalogResult.getOrElse { error ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=work_projection.app_run_platforms repo=$repoRoot value_used=empty reason=${error.message}",
          error,
        )
        return emptyList()
      }
    val key = input to catalog
    return memo.getOrPut(key) { resolvePlatforms(input, catalog, repoRoot) }
  }

  private fun resolvePlatforms(
    input: AppRunGoalRoutingInput,
    catalog: List<PlatformManifest>,
    repoRoot: Path,
  ): List<String> =

    catchingNonCancellation { resolver.resolve(input, catalog) }.fold(
      onSuccess = { resolution ->
        when (resolution) {
          is AppRunCapabilityResolution.Incompatible -> {
            RuntimeDiagnosticsBestEffortWarning.record(
              diagnostics,
              "seam=work_projection.app_run_platforms repo=$repoRoot value_used=empty reason=${resolution.reason}",
            )
            emptyList()
          }
          else -> resolution.platforms()
        }
      },
      onFailure = { error ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=work_projection.app_run_platforms repo=$repoRoot value_used=empty reason=${error.message}",
          error,
        )
        emptyList()
      },
    )

  private fun routingInput(
    repoRoot: Path,
    branch: String?,
    worktreePath: String?,
  ): AppRunGoalRoutingInputResult {
    if (!worktreePath.isNullOrBlank()) {
      val liveResult =
        catchingNonCancellation { resolver.liveRoutingInput(repoRoot, Path.of(worktreePath)) }
      val live =
        liveResult.getOrElse { error ->
          AppRunGoalRoutingInputResult.Failed("Could not inspect the live goal checkout: ${error.message}")
        }
      when (live) {
        is AppRunGoalRoutingInputResult.Ready -> return live
        is AppRunGoalRoutingInputResult.Failed -> {
          return fallbackToBranch(repoRoot, branch, live)
        }
      }
    }
    if (branch.isNullOrBlank() || branch == "HEAD") {
      return AppRunGoalRoutingInputResult.Failed("Goal routing input is unavailable.")
    }
    return resolver.completedRoutingInput(repoRoot, branch)
  }

  private fun fallbackToBranch(
    repoRoot: Path,
    branch: String?,
    live: AppRunGoalRoutingInputResult.Failed,
  ): AppRunGoalRoutingInputResult {
    if (branch.isNullOrBlank() || branch == "HEAD") return live
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=work_projection.app_run_platforms repo=$repoRoot value_used=goal_branch_tip reason=${live.reason}",
    )
    return when (val completed = resolver.completedRoutingInput(repoRoot, branch)) {
      is AppRunGoalRoutingInputResult.Ready -> completed
      is AppRunGoalRoutingInputResult.Failed ->
        AppRunGoalRoutingInputResult.Failed("${live.reason} ${completed.reason}")
    }
  }
}
