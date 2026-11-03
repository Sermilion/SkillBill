package skillbill.engine.goalrunner.preflight

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.model.SpecBundleScope
import skillbill.application.decomposition.resolveDecompositionManifest
import skillbill.contracts.issuekey.normalizeIssueKey
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.goal.GoalPreflightPort
import skillbill.engine.goal.GoalPreflightRequest
import skillbill.engine.goal.GoalPreflightResult
import skillbill.engine.goal.goalRepositoryIdentityCandidates
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator

@Inject
public class GoalPreflightService(
  private val continuationLookup: FeatureTaskContinuationLookupService,
  private val manifestStore: GoalRunnerManifestStore,
  agentAddonSelectionPort: AgentAddonSelectionPort,
  externalAgentAddonSourceConfigPort: ExternalAgentAddonSourceConfigPort,
  private val manifestFileStore: DecompositionManifestStore,
  private val manifestValidator: DecompositionManifestValidator,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val projectStore: ProjectStorePort,
) : GoalPreflightPort {
  private val gateBlockBuilder =
    GoalPreflightGateBlockBuilder(
      manifestStore,
      agentAddonSelectionPort,
      externalAgentAddonSourceConfigPort,
      manifestFileStore,
    )
  private val lookupResolver = GoalPreflightLookupResolver(gateBlockBuilder)

  public override fun preflight(request: GoalPreflightRequest): GoalPreflightResult {
    GoalPreflightInputValidation.requireInvokedAgentId(request.invokedAgentId)
    GoalPreflightInputValidation.requireOptionalIdentity("agent_override_id", request.agentOverrideId)
    val root = GoalPreflightInputValidation.resolveRepositoryRoot(request.repoRoot, repositoryEnclosingRootPort)
    val normalizedIssueKey = GoalPreflightInputValidation.normalizeIssueKey(request.issueKey)
    val projectedManifest =
      resolveDecompositionManifest(
        scope = SpecBundleScope(root, projectStore.resolve(root)),
        issueKey = normalizedIssueKey,
        fileStore = manifestFileStore,
        validator = manifestValidator,
        recoverPending = false,
      )
    val manifestState =
      manifestStore.readByIssueKeyIfPresent(
        normalizedIssueKey,
        root,
      )
    val manifest = manifestState?.manifest ?: projectedManifest
    if (manifest != null) {
      GoalPreflightInputValidation.requireManifestIssueKey(manifest.issueKey, normalizedIssueKey)
    }
    val lookup =
      goalRepositoryIdentityCandidates(root, repositoryEnclosingRootPort).firstNotNullOfOrNull { identity ->
        continuationLookup.lookupIfPresent(normalizedIssueKey, identity)
          .takeIf { it != FeatureTaskContinuationLookupResult.NoMatch }
      } ?: FeatureTaskContinuationLookupResult.NoMatch
    val occupyingParent = manifestStore.loadOccupyingByIssueKey(normalizedIssueKey, root)
    val effectiveLookup =
      adjustGoalPreflightContinuationLookup(
        lookup,
        occupyingParent?.state,
        occupyingParent?.workflowStatus,
      )
    return lookupResolver.resolve(
      GoalPreflightLookupInput(
        lookup = effectiveLookup,
        normalizedIssueKey = normalizedIssueKey,
        manifest = manifest,
        manifestState = manifestState,
        request = request,
        root = root,
      ),
    )
  }
}
