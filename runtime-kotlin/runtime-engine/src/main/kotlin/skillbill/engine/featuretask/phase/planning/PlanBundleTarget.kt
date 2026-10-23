package skillbill.engine.featuretask.phase.planning

import java.nio.file.Path

/**
 * The one bundle directory a plan agent may write in, and where its files end up. [Store] and [Legacy] bundles are
 * written in place; a [Staged] bundle is for a provider that cannot write the store, and the runtime promotes it into
 * its `storeDirectory`.
 */
sealed interface PlanBundleTarget {
  /** The absolute directory the agent writes the bundle in. */
  val directory: Path

  data class Store(override val directory: Path) : PlanBundleTarget

  data class Legacy(override val directory: Path) : PlanBundleTarget

  data class Staged(
    val stagingRoot: Path,
    override val directory: Path,
    val storeDirectory: Path,
  ) : PlanBundleTarget
}
