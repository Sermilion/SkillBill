package skillbill.infrastructure.workflow.git.branches

import skillbill.ports.workflow.gitops.model.RepositoryBranchRef

private const val LOCAL_PREFIX = "refs/heads/"
private const val REMOTE_PREFIX = "refs/remotes/"
private const val ORIGIN_PREFIX = "origin/"

internal data class RawBranchRef(
  val refName: String,
  val symref: String,
  val committedAtEpochSeconds: Long,
)

internal data class RepositoryBranchGroups(
  val local: List<RepositoryBranchRef>,
  val remote: List<RepositoryBranchRef>,
)

internal fun groupRepositoryBranches(
  refs: List<RawBranchRef>,
  defaultBranch: String?,
): RepositoryBranchGroups {
  val branches = refs.filter { it.symref.isEmpty() }
  val local = branches.named(LOCAL_PREFIX)
  val localNames = local.mapTo(mutableSetOf(), RepositoryBranchRef::name)
  val remote =
    branches.named(REMOTE_PREFIX).filterNot { ref ->
      ref.name.startsWith(ORIGIN_PREFIX) &&
        ref.name.removePrefix(ORIGIN_PREFIX).let { it in localNames || it == defaultBranch }
    }
  return RepositoryBranchGroups(local.filterNot { it.name == defaultBranch }, remote)
}

private fun List<RawBranchRef>.named(prefix: String): List<RepositoryBranchRef> =
  filter { it.refName.startsWith(prefix) }
    .map { RepositoryBranchRef(it.refName.removePrefix(prefix), it.committedAtEpochSeconds) }
