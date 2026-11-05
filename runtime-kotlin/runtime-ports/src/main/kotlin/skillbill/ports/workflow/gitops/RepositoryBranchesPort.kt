package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.RepositoryBranchesResult
import java.nio.file.Path

/**
 * Read-only git view of the branches a repository already knows about.
 *
 * The read blocks on git and must run off the UI thread and outside any database read.
 * It never contacts a remote, and every failure is returned as a typed result, never thrown.
 */
interface RepositoryBranchesPort {
  /** Lists local and remote-tracking branches of the repository containing [path], newest commit first. */
  fun branches(path: Path): RepositoryBranchesResult
}
