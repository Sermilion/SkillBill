package skillbill.ports.taskruntime

import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import java.nio.file.Path

/**
 * Read seam through which the CLI sources run-invariants from a governed spec,
 * keeping filesystem access out of the CLI module.
 */
interface FeatureTaskRuntimeRunInvariantsSource {
  fun read(specPath: Path): FeatureTaskRuntimeRunInvariantsRead
}
