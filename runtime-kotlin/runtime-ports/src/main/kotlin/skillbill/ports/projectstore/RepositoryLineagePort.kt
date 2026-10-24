package skillbill.ports.projectstore

import skillbill.ports.projectstore.model.RepositoryLineage
import java.nio.file.Path

/**
 * Reads the history-derived identity of a repository.
 */
fun interface RepositoryLineagePort {
  /**
   * Returns the lineage of the repository at [repoRoot], or null when it has no commits or git fails.
   */
  fun lineage(repoRoot: Path): RepositoryLineage?
}
