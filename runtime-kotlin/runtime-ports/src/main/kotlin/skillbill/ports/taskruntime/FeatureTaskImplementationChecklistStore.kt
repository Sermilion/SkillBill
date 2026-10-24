package skillbill.ports.taskruntime

import skillbill.ports.taskruntime.model.ImplementationChecklistPrepareResult
import skillbill.ports.taskruntime.model.ImplementationChecklistSeed
import java.nio.file.Path

interface FeatureTaskImplementationChecklistStore {
  /** Absolute path of the checklist for [workflowId] inside the project store that serves [repoRoot]. */
  fun checklistPath(
    repoRoot: Path,
    workflowId: String,
  ): Path

  fun prepare(
    repoRoot: Path,
    seed: ImplementationChecklistSeed,
  ): ImplementationChecklistPrepareResult
}
