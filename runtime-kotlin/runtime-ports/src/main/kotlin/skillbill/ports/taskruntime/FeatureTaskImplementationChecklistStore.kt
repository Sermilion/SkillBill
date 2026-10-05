package skillbill.ports.taskruntime

import skillbill.ports.taskruntime.model.ImplementationChecklistPrepareResult
import skillbill.ports.taskruntime.model.ImplementationChecklistSeed
import java.nio.file.Path

interface FeatureTaskImplementationChecklistStore {
  fun prepare(
    repoRoot: Path,
    seed: ImplementationChecklistSeed,
  ): ImplementationChecklistPrepareResult
}
