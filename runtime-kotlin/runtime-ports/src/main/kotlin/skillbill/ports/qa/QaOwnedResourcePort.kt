package skillbill.ports.qa

import skillbill.ports.qa.model.QaOwnedResource
import skillbill.ports.qa.model.QaResourceRegistration
import skillbill.ports.qa.model.QaResourceReleaseOutcome
import java.nio.file.Path

/**
 * Environment resources (Compose projects, containers, volumes, networks, processes and directories) that one QA
 * invocation created, recorded per repository. The runtime tears down only recorded resources, so a resource another
 * invocation owns is never attached or removed.
 */
interface QaOwnedResourcePort {
  /**
   * Records [resource] for [invocationId]. A resource recorded for another invocation yields
   * [QaResourceRegistration.Conflict], whether that owner is live or dead, and its record stays untouched.
   */
  fun register(
    repoRoot: Path,
    invocationId: String,
    resource: QaOwnedResource,
  ): QaResourceRegistration

  /**
   * Tears down [resource] when [invocationId] holds it and drops its record. A missing or foreign-owned record
   * yields [QaResourceReleaseOutcome.NotHeld]; a teardown that did not finish keeps the record and yields
   * [QaResourceReleaseOutcome.Failed].
   */
  fun release(
    repoRoot: Path,
    invocationId: String,
    resource: QaOwnedResource,
  ): QaResourceReleaseOutcome

  /** Tears down every resource [invocationId] still holds, reporting each one it could not remove. */
  fun releaseAllFor(
    repoRoot: Path,
    invocationId: String,
  ): List<QaResourceReleaseOutcome>

  /**
   * Tears down the resources of invocations whose owner record is missing or whose owner process is confirmed dead.
   * Resources of a live or unverifiable owner stay untouched.
   */
  fun reclaimAbandoned(repoRoot: Path): List<QaResourceReleaseOutcome>
}
