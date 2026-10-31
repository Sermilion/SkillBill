package skillbill.engine.featuretask.lifecycle.subtask

import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity

internal fun normalizeRepoPath(path: String): String = path.trim().removeSurrounding("\"").removePrefix("./")

internal fun parseGitPorcelainPaths(output: String): List<String> =
  output
    .lineSequence()
    .map(String::trimEnd)
    .filter { line -> line.length >= GIT_PORCELAIN_MIN_LENGTH }
    .map(::pathFromPorcelainLine)
    .filter(String::isNotBlank)
    .toList()

internal fun pathFromPorcelainLine(line: String): String {
  val pathPart =
    when {
      line.length >= 2 &&
        line[0] != ' ' &&
        line[1] == ' ' &&
        (
          line.length < GIT_PORCELAIN_STATUS_PREFIX_LENGTH + 1 ||
            line[GIT_PORCELAIN_STATUS_PREFIX_LENGTH] != ' '
        ) -> line.drop(2)
      else -> line.drop(GIT_PORCELAIN_STATUS_PREFIX_LENGTH)
    }
  return pathPart.substringAfterLast(" -> ").trim().removeSurrounding("\"")
}

internal fun forceWithLeaseRecord(
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  branch: String,
  commitSha: String,
) = "seam=FeatureTaskRuntimeSubtaskFinalisation.push value_used='git push --force-with-lease origin " +
  "$branch' value_expected=a non-forcing push of '$commitSha' cause=subtask " +
  "'${identity.issueKey}/${identity.subtaskId}' was reopened after its commit had already been " +
  "published, so finalisation rewrote a commit the remote already carries"

internal fun leaseRefreshRecord(
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  branch: String,
) = "seam=FeatureTaskRuntimeSubtaskFinalisation.push value_used='git fetch origin +refs/heads/$branch:" +
  "refs/remotes/origin/$branch' value_expected=the remote-tracking tip for 'origin/$branch' for subtask " +
  "'${identity.issueKey}/${identity.subtaskId}' cause=a leased push is only as current as the last fetch"

internal fun leaseAbsentRecord(
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  branch: String,
) = "seam=FeatureTaskRuntimeSubtaskFinalisation.push value_used='git push -u origin $branch' " +
  "value_expected=a leased push of 'origin/$branch' for subtask '${identity.issueKey}/${identity.subtaskId}' " +
  "cause=the remote no longer has '$branch', so finalisation publishes the local tip as a new remote branch"

internal fun leaseRefreshFailedRecord(
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  branch: String,
  error: String,
) = "seam=FeatureTaskRuntimeSubtaskFinalisation.push value_used='the last observed origin/$branch' " +
  "value_expected=a refreshed 'origin/$branch' for subtask '${identity.issueKey}/${identity.subtaskId}' " +
  "cause=git fetch origin $branch failed, so the leased push will use the last observed tip ($error)"

internal fun leaseRetryRecord(
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  branch: String,
  error: String,
) = "seam=FeatureTaskRuntimeSubtaskFinalisation.push value_used='a second leased push after fetch' " +
  "value_expected=the first leased push of subtask '${identity.issueKey}/${identity.subtaskId}' " +
  "cause=the first leased push was rejected ($error), so finalisation refreshes origin/$branch and retries"

internal fun leaseAbortRecord(
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  branch: String,
  error: String,
) = "seam=FeatureTaskRuntimeSubtaskFinalisation.push value_used='an unpushed local tip' " +
  "value_expected='origin/$branch' for subtask '${identity.issueKey}/${identity.subtaskId}' " +
  "cause=the leased push was rejected after refresh and retry ($error)"
