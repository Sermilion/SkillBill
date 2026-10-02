package skillbill.engine.goalrunner.preflight

import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.specSource
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalPreflightAgentAddon
import skillbill.engine.goalrunner.model.GoalPreflightDependency
import skillbill.engine.goalrunner.model.GoalPreflightGateBlock
import skillbill.engine.goalrunner.model.GoalPreflightRehydrateTarget
import skillbill.engine.goalrunner.model.GoalPreflightRequest
import skillbill.engine.goalrunner.model.GoalPreflightSubtask
import skillbill.engine.goalrunner.review.effectiveGoalRunnerReviewPolicy
import skillbill.engine.goalrunner.review.goalRunnerReviewPolicyMismatch
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.AgentAddonFailureCode
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.goalrunner.GoalRunnerPlanner
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.model.toPath
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigRequest
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.SpecSource.LINEAR
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import java.nio.file.Path

class GoalPreflightGateBlockBuilder(
  private val manifestStore: GoalRunnerManifestStore,
  private val agentAddonSelectionPort: AgentAddonSelectionPort,
  private val externalAgentAddonSourceConfigPort: ExternalAgentAddonSourceConfigPort,
  private val manifestFileStore: DecompositionManifestStore,
) {
  fun resolveSelection(
    request: GoalPreflightRequest,
    root: Path,
    receivingAgents: List<String>,
    parentWorkflowId: String?,
  ): HydratedAgentAddonSelection {
    val persisted =
      parentWorkflowId
        ?.takeIf(String::isNotBlank)
        ?.let { manifestStore.reviewPolicy(it)?.agentAddonSelection }
    if (request.requestedAgentAddonSlugs.isNotEmpty()) {
      if (persisted != null && persisted.entries.map { it.slug } != request.requestedAgentAddonSlugs) {
        throw SkillBillRuntimeException(
          AgentAddonFailureCode.INVALID_SELECTION,
          "Cannot change agent add-on selection on goal resume: " +
            "the parent workflow has a different durable selection.",
        )
      }
      return agentAddonSelectionPort.resolveInitial(
        repoRoot = root,
        requestedSlugs = request.requestedAgentAddonSlugs,
        consumer = AgentAddonConsumer.SKILL_BILL,
        receivingAgentIds = receivingAgents,
        externalSourceRoots =
          externalAgentAddonSourceConfigPort.readExternalAgentAddonSources(
            ExternalAgentAddonSourceConfigRequest(request.userHome, request.environment),
          ).sources.map { source -> source.path.toPath() },
      )
    }
    return if (persisted == null || persisted.entries.isEmpty()) {
      HydratedAgentAddonSelection()
    } else {
      agentAddonSelectionPort.verifyPersisted(
        persisted,
        AgentAddonConsumer.SKILL_BILL,
        receivingAgents,
      )
    }
  }

  fun build(
    request: GoalPreflightRequest,
    manifest: DecompositionManifest,
    root: Path,
    parentWorkflowId: String?,
  ): GoalPreflightGateBlock {
    val durablePolicy =
      parentWorkflowId
        ?.takeIf(String::isNotBlank)
        ?.let { manifestStore.reviewPolicy(it) }
    val mismatch =
      durablePolicy?.let {
        goalRunnerReviewPolicyMismatch(
          parentWorkflowId = parentWorkflowId.orEmpty(),
          requestedReviewMode = request.requestedReviewMode,
          persisted = it,
        )
      }
    if (mismatch != null) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError("goal preflight", mismatch)
    }
    val effectiveReviewPolicy =
      effectiveGoalRunnerReviewPolicy(
        request.requestedReviewMode,
        durablePolicy,
      )
    val selectionResult = GoalRunnerPlanner.selectNext(manifest)
    val firstRunnable =
      when (selectionResult) {
        is GoalRunnerSelection.Run -> selectionResult.decision.subtask.id
        is GoalRunnerSelection.Blocked -> null
        GoalRunnerSelection.Done -> null
      }
    val selection =
      resolveSelection(
        request = request,
        root = root,
        receivingAgents =
          listOfNotNull(
            request.invokedAgentId,
            request.agentOverrideId,
          ).filter(String::isNotBlank).distinct(),
        parentWorkflowId = parentWorkflowId,
      )
    return GoalPreflightGateBlock(
      issueKey = manifest.issueKey,
      featureName = manifest.featureName,
      subtasks = manifest.subtasks.map(::subtaskBlock),
      expectedFirstRunnableSubtask = firstRunnable,
      childAgent = request.agentOverrideId?.takeIf(String::isNotBlank) ?: request.invokedAgentId,
      childAgentOverride = request.agentOverrideId?.takeIf(String::isNotBlank),
      reviewMode = effectiveReviewPolicy.codeReviewMode.displayName(request.requestedReviewMode == null),
      agentAddons =
        selection.entries.map { entry ->
          GoalPreflightAgentAddon(
            slug = entry.persisted.slug,
            description = entry.description,
          )
        },
    )
  }

  fun rehydrateTargets(
    root: Path,
    manifest: DecompositionManifest,
  ): List<GoalPreflightRehydrateTarget> {
    if (manifest.specSource != LINEAR) return emptyList()
    val targets =
      buildList {
        add(
          GoalPreflightRehydrateTarget(
            issueKey = manifest.issueKey,
            linearIssueId = manifest.issueKey,
            targetPath = relativePath(root, manifest.parentSpecPath),
          ).takeUnless { manifestFileStore.isRegularFileWithoutRecovery(root.resolve(it.targetPath)) },
        )
        manifest.subtasks
          .filterNot {
            it.status.decompositionStatus() in setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
          }
          .forEach { subtask ->
            add(
              GoalPreflightRehydrateTarget(
                issueKey = manifest.issueKey,
                linearIssueId = subtask.linearIssueId,
                targetPath = relativePath(root, subtask.specPath),
              ).takeUnless { manifestFileStore.isRegularFileWithoutRecovery(root.resolve(it.targetPath)) },
            )
          }
      }
    return targets.filterNotNull()
  }

  private fun subtaskBlock(subtask: DecompositionSubtask): GoalPreflightSubtask =
    GoalPreflightSubtask(
      id = subtask.id,
      name = subtask.name,
      status = subtask.status,
      dependencies =
        subtask.dependencies.map { dependency ->
          GoalPreflightDependency(
            subtaskId = dependency.subtaskId,
            optional = dependency.optional,
            skipped = dependency.skipped,
            note =
              if (dependency.optional) {
                "optional dependency on subtask ${dependency.subtaskId}" +
                  if (dependency.skipped) " is skipped" else ""
              } else {
                "requires subtask ${dependency.subtaskId}"
              },
          )
        },
    )

  private fun relativePath(
    root: Path,
    rawPath: String,
  ): String {
    val path = Path.of(rawPath)
    val resolved = (if (path.isAbsolute) path else root.resolve(path)).toAbsolutePath().normalize()
    return if (resolved.startsWith(root)) {
      root.relativize(resolved).joinToString("/")
    } else {
      rawPath
    }
  }
}

private fun CodeReviewExecutionMode.displayName(omitted: Boolean): String =
  when {
    omitted && this == CodeReviewExecutionMode.INLINE -> "inline (default)"
    this == CodeReviewExecutionMode.DELEGATED -> "delegated (experimental)"
    else -> wireValue
  }
