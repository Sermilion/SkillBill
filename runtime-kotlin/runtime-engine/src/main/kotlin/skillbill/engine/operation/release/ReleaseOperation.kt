package skillbill.engine.operation.release

import skillbill.engine.directive.directiveResource
import skillbill.engine.operation.core.ConfirmableOperation
import skillbill.engine.operation.core.ConfirmedOperationProposal
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationRunResult
import skillbill.engine.operation.core.OperationStepResult
import skillbill.engine.operation.core.requireGitValue
import skillbill.error.operation.ReleaseBranchBehindRemoteError
import skillbill.error.operation.ReleaseWorktreeDirtyError
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult

class ReleaseOperation(
  private val gitOperations: WorkflowGitOperations,
) : ConfirmableOperation {
  override val id: String = "release"

  override fun pre(context: OperationContext) {
    if (!context.confirming) ReleaseBump.parse(context.arguments.bump)
    val repoRoot = context.repoRoot
    if (gitOperations.worktreeStatus(repoRoot).requireGitValue("worktree status").isNotBlank()) {
      throw ReleaseWorktreeDirtyError(repoRoot.toString())
    }
    val branch = branch(context)
    gitOperations.refreshRemoteBranch(repoRoot, branch)
    if (gitOperations.localBranchBehindRemote(repoRoot, branch).requireGitValue("branch freshness") == "true") {
      throw ReleaseBranchBehindRemoteError(branch)
    }
  }

  override fun run(context: OperationContext): OperationRunResult {
    val bump = ReleaseBump.parse(context.arguments.bump)
    val lastTag = lastReleaseTag(context)
    val version = nextReleaseVersion(lastTag, bump)
    val commits = gitOperations.commitLogSince(context.repoRoot, lastTag).requireGitValue("commit log")
    val directive =
      releaseDirective()
        .replace("{{version}}", version)
        .replace("{{previous_tag}}", lastTag ?: "none (first release; commits since the root commit)")
        .replace("{{commit_log}}", commits.ifBlank { "(no commits)" })
    return when (val step = context.steps.runReadOnly(context, CHANGELOG_STEP, directive)) {
      is OperationStepResult.Failed -> OperationRunResult.Finished(OperationOutcome.Failed(step.reason))
      is OperationStepResult.Settled -> {
        val changelog = step.value.trim() + "\n"
        OperationRunResult.Proposed(
          value = changelog,
          summary = "Release $version (bump: ${bump.wireValue}, previous: ${lastTag ?: "none"})\n\n$changelog",
          operationValues = currentAnchors(context) + (RELEASE_VERSION to version),
        )
      }
    }
  }

  override fun currentAnchors(context: OperationContext): Map<String, String> =
    mapOf(
      LAST_RELEASE_TAG to lastReleaseTag(context).orEmpty(),
      REMOTE_HEAD to gitOperations.remoteBranchHead(context.repoRoot, branch(context)).requireGitValue(REMOTE_HEAD),
    )

  override fun execute(
    context: OperationContext,
    proposal: ConfirmedOperationProposal,
  ): OperationOutcome {
    val version =
      proposal.operationValues[RELEASE_VERSION]
        ?: return OperationOutcome.Failed("Release proposal '${proposal.token}' carries no version.")
    val tagged = gitOperations.createAnnotatedTag(context.repoRoot, version, proposal.value)
    if (tagged !is WorkflowGitOperationResult.Ok) {
      return OperationOutcome.Failed("Could not create tag $version: ${tagged.error}")
    }
    val pushed = gitOperations.pushTag(context.repoRoot, version)
    if (pushed !is WorkflowGitOperationResult.Ok) {
      val removed = gitOperations.deleteLocalTag(context.repoRoot, version)
      val localState =
        if (removed is WorkflowGitOperationResult.Ok) {
          "Removed the local tag; re-run the release for a new proposal."
        } else {
          "The local tag $version still exists (${removed.error}); delete it or push it by hand."
        }
      return OperationOutcome.Failed("Could not push tag $version: ${pushed.error}. $localState")
    }
    return OperationOutcome.Completed(
      "Created and pushed annotated tag $version. Watch the release workflow wired to the tag for build and " +
        "publish status.\n",
    )
  }

  private fun lastReleaseTag(context: OperationContext): String? =
    gitOperations.lastReleaseTag(context.repoRoot).requireGitValue(LAST_RELEASE_TAG).ifBlank { null }

  private fun branch(context: OperationContext): String =
    gitOperations.currentBranch(context.repoRoot).requireGitValue("branch")

  private fun releaseDirective(): String = directiveResource(RELEASE_DIRECTIVE_RESOURCE)
}

private const val CHANGELOG_STEP = "operation.release.changelog"
private const val RELEASE_DIRECTIVE_RESOURCE = "/skillbill/engine/operation/release/release-directive.md"
private const val LAST_RELEASE_TAG = "last_release_tag"
private const val REMOTE_HEAD = "remote_head"
private const val RELEASE_VERSION = "release_version"
