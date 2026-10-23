package skillbill.ports.projectstore

import skillbill.ports.projectstore.model.ProjectStoreLocation
import java.nio.file.Path

/**
 * Maps a checkout to its project store under the Skill Bill state directory without writing into the project.
 */
interface ProjectStorePort {
  /**
   * Resolves the store for [workingDirectory]. Identity problems degrade to a path-keyed store and never throw.
   */
  fun resolve(workingDirectory: Path): ProjectStoreLocation

  /**
   * Attaches the checkout at [workingDirectory] to the existing store [projectId]. Fails when that store is
   * unknown or its record is malformed.
   */
  fun link(
    workingDirectory: Path,
    projectId: String,
  ): ProjectStoreLocation
}
