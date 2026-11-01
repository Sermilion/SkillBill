package skillbill.engine.operation.release

import skillbill.engine.directive.directiveResource
import skillbill.engine.operation.core.ConfirmableOperation
import skillbill.engine.operation.core.ConfirmedOperationProposal
import skillbill.engine.operation.core.CurrentOperationAnchors
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationPrecheck
import skillbill.engine.operation.core.OperationRefusal
import skillbill.engine.operation.core.OperationRunResult
import skillbill.engine.operation.core.OperationStepResult
import skillbill.engine.operation.core.gitValueOr
import skillbill.engine.operation.core.releaseWorktreeDirty
import skillbill.ports.operation.ReleaseBuildGate
import skillbill.ports.operation.model.ReleaseBuildReadiness
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult

public class ReleaseOperation(
  private val gitOperations: WorkflowGitOperations,
  private val buildGate: ReleaseBuildGate,
) : ConfirmableOperation,
  OperationPrecheck {
  override val id: String = "release"

  override fun pre(context: OperationContext): OperationRefusal? {
    if (!context.confirming && ReleaseBump.parse(context.arguments.bump) == null) {
      return missingReleaseBump(context.arguments.bump)
    }
    val repoRoot = context.repoRoot
    if (gitOperations.worktreeStatus(repoRoot).gitValueOr("worktree status") { return it }.isNotBlank()) {
      return releaseWorktreeDirty(repoRoot.toString())
    }
    return buildReadinessRefusal(context)
  }

  private fun buildReadinessRefusal(context: OperationContext): OperationRefusal? {
    val repoRoot = context.repoRoot
    val head = gitOperations.headCommitSha(repoRoot).gitValueOr("release commit") { return it }
    return when (val readiness = buildGate.inspect(repoRoot, head)) {
      is ReleaseBuildReadiness.Passed -> releaseHeadRefusal(context, readiness.branch, head)
      is ReleaseBuildReadiness.NotReady -> OperationOutcome.Blocked(readiness.reason)
      is ReleaseBuildReadiness.Unavailable -> OperationOutcome.Blocked(readiness.reason)
    }
  }

  private fun releaseHeadRefusal(
    context: OperationContext,
    branch: String,
    head: String,
  ): OperationRefusal? {
    val repoRoot = context.repoRoot
    gitOperations.refreshRemoteBranch(repoRoot, branch).gitValueOr("release branch refresh") { return it }
    val remoteHead = gitOperations.remoteBranchHead(repoRoot, branch).gitValueOr("release branch head") { return it }
    return if (head == remoteHead) {
      null
    } else {
      OperationOutcome.Blocked(
        "Release commit $head must match origin/$branch at $remoteHead. " +
          "Update the checkout and propose the release again.",
      )
    }
  }

  override fun run(context: OperationContext): OperationRunResult {
    val bump = checkNotNull(ReleaseBump.parse(context.arguments.bump)) { "pre validates the release bump." }
    val lastTag = lastReleaseTag(context) { return OperationRunResult.Finished(it) }
    val version = nextReleaseVersion(lastTag, bump)
    val commits =
      gitOperations.commitLogSince(context.repoRoot, lastTag).gitValueOr("commit log") {
        return OperationRunResult.Finished(it)
      }
    val directive =
      releaseDirective()
        .replace("{{version}}", version)
        .replace("{{previous_tag}}", lastTag ?: "none (first release; commits since the root commit)")
        .replace("{{commit_log}}", commits.ifBlank { "(no commits)" })
    return when (val step = context.steps.runReadOnly(context, CHANGELOG_STEP, directive)) {
      is OperationStepResult.Failed -> OperationRunResult.Finished(OperationOutcome.Failed(step.reason))
      is OperationStepResult.Refused -> OperationRunResult.Finished(step.refusal)
      is OperationStepResult.Settled -> proposal(context, step.value.trim() + "\n", version, bump, lastTag)
    }
  }

  private fun proposal(
    context: OperationContext,
    changelog: String,
    version: String,
    bump: ReleaseBump,
    lastTag: String?,
  ): OperationRunResult =
    when (val anchors = currentAnchors(context)) {
      is CurrentOperationAnchors.Unreadable -> OperationRunResult.Finished(anchors.refusal)
      is CurrentOperationAnchors.Read ->
        OperationRunResult.Proposed(
          value = changelog,
          summary = "Release $version (bump: ${bump.wireValue}, previous: ${lastTag ?: "none"})\n\n$changelog",
          operationValues = anchors.values + (RELEASE_VERSION to version),
        )
    }

  override fun currentAnchors(context: OperationContext): CurrentOperationAnchors {
    val lastTag = lastReleaseTag(context) { return CurrentOperationAnchors.Unreadable(it) }
    val branch = branch(context) { return CurrentOperationAnchors.Unreadable(it) }
    val remoteHead =
      gitOperations.remoteBranchHead(context.repoRoot, branch).gitValueOr(REMOTE_HEAD) {
        return CurrentOperationAnchors.Unreadable(it)
      }
    return CurrentOperationAnchors.Read(mapOf(LAST_RELEASE_TAG to lastTag.orEmpty(), REMOTE_HEAD to remoteHead))
  }

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

  private inline fun lastReleaseTag(
    context: OperationContext,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ): String? = gitOperations.lastReleaseTag(context.repoRoot).gitValueOr(LAST_RELEASE_TAG, refuse).ifBlank { null }

  private inline fun branch(
    context: OperationContext,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ): String = gitOperations.currentBranch(context.repoRoot).gitValueOr("branch", refuse)

  private fun releaseDirective(): String = directiveResource(RELEASE_DIRECTIVE_RESOURCE)
}

private fun missingReleaseBump(bump: String?): OperationOutcome.Usage =
  OperationOutcome.Usage(
    (bump?.let { "Unknown release bump '$it'" } ?: "Release requires a bump") +
      "; pass bump:patch, bump:minor, or bump:major.",
  )

private const val CHANGELOG_STEP = "operation.release.changelog"
private const val RELEASE_DIRECTIVE_RESOURCE = "/skillbill/engine/operation/release/release-directive.md"
private const val LAST_RELEASE_TAG = "last_release_tag"
private const val REMOTE_HEAD = "remote_head"
private const val RELEASE_VERSION = "release_version"
