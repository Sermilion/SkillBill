package skillbill.engine.goalrunner.execution.support

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.decomposition.specSource
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.workflow.specscratch.SpecScratchStore
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.SpecSource
import java.nio.file.Path

@Inject
public class GoalSpecScratchCleanup(
  private val specScratchStore: SpecScratchStore,
  private val projectStore: ProjectStorePort,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun deleteCompletedSubtaskSpec(
    manifest: DecompositionManifest,
    subtaskId: Int,
    repoRoot: Path,
  ) {
    if (manifest.specSource != SpecSource.LINEAR) return
    val specPath =
      manifest.subtasks.firstOrNull { it.id == subtaskId }?.specPath?.takeIf(String::isNotBlank)
        ?: return
    val resolved = resolvedParentSpecPath(repoRoot, Path.of(specPath), projectStore.resolve(repoRoot))
    catchingNonCancellation { specScratchStore.deleteFileIfExists(resolved) }
      .onFailure { error ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Goal linear-mode subtask spec scratch deletion at '$resolved' failed; the completed " +
            "subtask is unaffected and the scratch can be cleaned up manually.",
          error,
        )
      }
  }

  internal fun deleteGoalSpecBundle(
    manifest: DecompositionManifest,
    repoRoot: Path,
  ) {
    if (manifest.specSource != SpecSource.LINEAR) return
    val parentSpec = resolvedParentSpecPath(repoRoot, Path.of(manifest.parentSpecPath), projectStore.resolve(repoRoot))
    val specDir = parentSpec.parent ?: return
    catchingNonCancellation { specScratchStore.deleteDirectoryIfExists(specDir) }
      .onFailure { error ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Goal linear-mode spec scratch deletion at '$specDir' failed; the completed goal is " +
            "unaffected and the scratch can be cleaned up manually.",
          error,
        )
      }
  }
}
