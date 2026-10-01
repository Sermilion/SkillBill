package skillbill.engine.featuretask.slot.codereview

import skillbill.application.review.governed.stubGovernedReviewEvidenceEndpointBinder
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunner
import skillbill.application.review.snapshot.diffForChanges
import skillbill.application.review.snapshot.parallelCodeReviewRunnerOf
import skillbill.application.review.snapshot.simulateGovernedEvidenceReads
import skillbill.application.review.snapshot.sparseReviewPack
import skillbill.application.review.spec.ReviewSpecAdjudicationRunner
import skillbill.application.review.spec.SpecIntentProjectionExtractor
import skillbill.application.review.spec.SpecIntentProjectionResolver
import skillbill.application.review.verification.ReviewClaimVerificationRunner
import skillbill.config.model.RepoLocalConfig
import skillbill.infrastructure.contracts.review.ReviewContextSchemaValidator
import skillbill.infrastructure.contracts.workflow.decomposition.DecompositionManifestSchemaValidator
import skillbill.infrastructure.workflow.decomposition.FileSystemDecompositionManifestFileStore
import skillbill.infrastructure.workflow.review.broker.FileSystemReviewEvidenceBroker
import skillbill.infrastructure.workflow.review.specialists.ClasspathReviewSpecialistContractProvider
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diff.DiffResolverPortDefaults
import skillbill.ports.diff.model.ReviewDiffQuery
import skillbill.ports.diff.model.ReviewIndexEntry
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.evidence.ReviewEvidenceBrokerFactory
import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import skillbill.ports.review.model.ReviewOwnedFileEvidence
import skillbill.ports.review.preparation.ReviewRubricResolver
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path
import java.util.Collections

internal const val DELEGATED_SPECIALIST_ISSUE_KEY = "code-review"
internal const val DELEGATED_REVIEWED_PATH = "src/Foo.kt"
internal const val DELEGATED_FINDING_MESSAGE = "the delegated lane saw the connection leak on the error path"
private const val DELEGATED_FINDING_REGISTER =
  "- [F-001] Blocker | High | specialist=bill-kotlin-code-review-architecture | " +
    "path=\"$DELEGATED_REVIEWED_PATH\" | line=1 | $DELEGATED_FINDING_MESSAGE"

internal fun scriptedDelegatedReviewRunner(
  database: DatabaseSessionFactory,
  home: Path,
  lanes: LaneScript,
): ParallelCodeReviewRunner {
  val envelopeValidator = ReviewContextSchemaValidator()
  return parallelCodeReviewRunnerOf(
    diffResolver = WorktreeDiffResolver(diffForChanges(DELEGATED_REVIEWED_PATH to "val connection = open()")),
    repoLocalConfig = DefaultRepoLocalConfig,
    reviewContextEnvelopeValidator = envelopeValidator,
    reviewRubricResolver = GovernedRubricResolver,
    reviewSpecialistContractProvider = ClasspathReviewSpecialistContractProvider(),
    database = database,
    installedPackCatalog =
      InstalledPlatformPackCatalogPort { listOf(sparseReviewPack("kotlin", "architecture", emptyMap())) },
    specIntentProjectionResolver =
      SpecIntentProjectionResolver(
        FileSystemDecompositionManifestFileStore(),
        DecompositionManifestSchemaValidator(),
        SpecIntentProjectionExtractor(envelopeValidator, FileSystemDecompositionManifestFileStore()),
      ),
    parentReviewLauncher = lanes,
    reviewEvidenceBrokerFactory = ReviewEvidenceBrokerFactory(::FileSystemReviewEvidenceBroker),
    governedEvidenceEndpointBinder = stubGovernedReviewEvidenceEndpointBinder(home.resolve("review-endpoint")),
  )
}

internal class LaneScript(
  private val onSpecialistLaunch: () -> Unit = {},
) : GoalRunnerSubtaskLauncher {
  val launches: MutableList<GoalRunnerSubtaskLaunchRequest> = Collections.synchronizedList(mutableListOf())

  @Volatile var fixed: Boolean = false

  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
    launches += request
    val lane = request.skillRunRequest
    simulateGovernedEvidenceReads(lane)
    if (lane.issueKey == DELEGATED_SPECIALIST_ISSUE_KEY) onSpecialistLaunch()
    return agentRunLaunchFacts(
      agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
      stdout =
        when (lane.issueKey) {
          DELEGATED_SPECIALIST_ISSUE_KEY -> if (fixed) "NO_FINDINGS" else DELEGATED_FINDING_REGISTER
          ReviewClaimVerificationRunner.ISSUE_KEY -> """{"claim_verdict":"confirmed"}"""
          ReviewSpecAdjudicationRunner.ISSUE_KEY -> """{"scope_disposition":"in_scope"}"""
          else -> "NO_FINDINGS"
        },
      stderr = "",
    )
  }
}

private class WorktreeDiffResolver(private val diff: String) : DiffResolverPortDefaults() {
  override fun reviewWorktreeFileIdentities(
    root: Path,
    paths: List<String>,
  ): Map<String, ReviewCheckpointFileIdentity> = emptyMap()

  override fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): String = revision

  override fun firstParentCommits(
    repoRoot: Path,
    base: String,
    head: String,
  ): List<String> = emptyList()

  override fun indexEntries(repoRoot: Path): List<ReviewIndexEntry> = emptyList()

  override fun untrackedPaths(repoRoot: Path): List<String> = emptyList()

  override fun diff(
    repoRoot: Path,
    query: ReviewDiffQuery,
  ): String = diff
}

private object DefaultRepoLocalConfig : RepoLocalConfigPort {
  override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest) =
    ReadRepoLocalConfigResult(RepoLocalConfig.defaults())
}

private object GovernedRubricResolver : ReviewRubricResolver {
  override fun resolve(manifest: PlatformManifest?): ResolvedReviewRubric =
    ResolvedReviewRubric("parallel-code-review", "governed rubric body for parallel-code-review")

  override fun resolve(
    manifest: PlatformManifest?,
    evidence: List<ReviewOwnedFileEvidence>,
    specialistSkillName: String,
  ): ResolvedReviewRubric =
    ResolvedReviewRubric(
      rubricId = specialistSkillName,
      body = "governed rubric body for $specialistSkillName",
      area = specialistSkillName.substringAfter("-code-review-", "generic"),
    )
}
