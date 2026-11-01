package skillbill.engine.featuretask.phase.planning

import java.nio.file.Path

/**
 * The one bundle directory a plan agent may write in, and where its files end up. [Store] and [Legacy] bundles are
 * written in place; a [Staged] bundle is for a provider that cannot write the store, and the runtime promotes it into
 * its `storeDirectory`.
 */
public sealed interface PlanBundleTarget {
  /** The absolute directory the agent writes the bundle in. */
  public val directory: Path

  public data class Store(override val directory: Path) : PlanBundleTarget

  public data class Legacy(override val directory: Path) : PlanBundleTarget

  public data class Staged(
    public val stagingRoot: Path,
    public override val directory: Path,
    public val storeDirectory: Path,
  ) : PlanBundleTarget
}
