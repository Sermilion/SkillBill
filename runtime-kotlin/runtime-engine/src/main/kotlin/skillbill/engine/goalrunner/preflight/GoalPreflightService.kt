package skillbill.engine.goalrunner.preflight

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.resolveDecompositionManifest
import skillbill.contracts.issuekey.normalizeIssueKey
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.goalrunner.goalRepositoryIdentity
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalPreflightLookupInput
import skillbill.engine.goalrunner.model.GoalPreflightRequest
import skillbill.engine.goalrunner.model.GoalPreflightResult
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator

@Inject
class GoalPreflightService(
  private val continuationLookup: FeatureTaskContinuationLookupService,
  private val manifestStore: GoalRunnerManifestStore,
  agentAddonSelectionPort: AgentAddonSelectionPort,
  externalAgentAddonSourceConfigPort: ExternalAgentAddonSourceConfigPort,
  private val manifestFileStore: DecompositionManifestStore,
  private val manifestValidator: DecompositionManifestValidator,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) {
  private val gateBlockBuilder =
    GoalPreflightGateBlockBuilder(
      manifestStore,
      agentAddonSelectionPort,
      externalAgentAddonSourceConfigPort,
      manifestFileStore,
    )
  private val lookupResolver = GoalPreflightLookupResolver(gateBlockBuilder)

  fun preflight(request: GoalPreflightRequest): GoalPreflightResult {
    GoalPreflightInputValidation.requireInvokedAgentId(request.invokedAgentId)
    GoalPreflightInputValidation.requireOptionalIdentity("agent_override_id", request.agentOverrideId)
    val root = GoalPreflightInputValidation.resolveRepositoryRoot(request.repoRoot, repositoryEnclosingRootPort)
    val normalizedIssueKey = GoalPreflightInputValidation.normalizeIssueKey(request.issueKey)
    val projectedManifest =
      resolveDecompositionManifest(
        repoRoot = root,
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
      continuationLookup.lookupIfPresent(
        issueKey = normalizedIssueKey,
        repositoryIdentity = goalRepositoryIdentity(root, repositoryEnclosingRootPort),
      )
    return lookupResolver.resolve(
      GoalPreflightLookupInput(
        lookup = lookup,
        normalizedIssueKey = normalizedIssueKey,
        manifest = manifest,
        manifestState = manifestState,
        request = request,
        root = root,
      ),
    )
  }
}
