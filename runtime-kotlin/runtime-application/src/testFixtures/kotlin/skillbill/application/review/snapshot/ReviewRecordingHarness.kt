package skillbill.application.review.snapshot

import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.application.review.governed.stubGovernedReviewEvidenceEndpointBinder
import skillbill.application.review.learnings.ReviewLearningsResolver
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelCodeReviewRunOutcome
import skillbill.application.review.model.ReviewPrelaunchExpansion
import skillbill.application.review.parallel.planning.ParallelCodeReviewRunnerPlanning
import skillbill.application.review.parallel.planning.ParallelCodeReviewRunnerRubricPlanning
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunner
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunnerLaneLaunch
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunnerResultAssembly
import skillbill.application.review.parallel.verification.ParallelCodeReviewRunnerFailureAdmission
import skillbill.application.review.parallel.verification.ParallelCodeReviewRunnerLanePlanRecording
import skillbill.application.review.parallel.verification.ParallelCodeReviewRunnerVerificationStages
import skillbill.application.review.spec.SpecIntentProjectionExtractor
import skillbill.application.review.spec.SpecIntentProjectionResolver
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.application.runtimepersistence.RuntimeOwnedPersistenceBoundary
import skillbill.config.model.RepoLocalConfig
import skillbill.infrastructure.contracts.workflow.decomposition.DecompositionManifestSchemaValidator
import skillbill.infrastructure.host.CanonicalRepositoryRoot
import skillbill.infrastructure.workflow.decomposition.FileSystemDecompositionManifestFileStore
import skillbill.infrastructure.workflow.filesystem.FileSystemDiffResolver
import skillbill.infrastructure.workflow.review.broker.FileSystemReviewEvidenceBroker
import skillbill.infrastructure.workflow.review.specialists.ClasspathReviewSpecialistContractProvider
import skillbill.install.model.SupportedAgent
import skillbill.learnings.model.LearningRecord
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.diff.DiffResolverPortDefaults
import skillbill.ports.diff.model.ReviewCommitMetadata
import skillbill.ports.diff.model.ReviewDiffQuery
import skillbill.ports.diff.model.ReviewIndexEntry
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.repository.RepositoryOriginScopeKeyPort
import skillbill.ports.repository.toFileLocation
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.ports.review.evidence.ReviewEvidenceBrokerFactory
import skillbill.ports.review.launch.NO_OP_REVIEW_LAUNCH_AGENT_STAGING
import skillbill.ports.review.launch.NO_OP_REVIEW_NATIVE_AGENT_PREFLIGHT
import skillbill.ports.review.launch.ReviewLaunchAgentStagingPort
import skillbill.ports.review.launch.ReviewNativeAgentPreflightPort
import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.ports.review.model.ReviewAccountingRecord
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.ports.review.model.ReviewIntegrationPassRecord
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewOwnedFileEvidence
import skillbill.ports.review.preparation.ReviewRubricResolver
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.review.repository.ReviewSpecialistContractProvider
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.ports.taskruntime.DERIVING_SHARED_EVIDENCE_RESOLVER
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.review.context.ReviewContextWireMap
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.context.model.packet.LANE_EVIDENCE_BYTES_DIMENSION
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewParseResult
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewPassClaimSnapshot
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageDegradationMeasurement
import skillbill.review.parallel.ParallelReviewFindingParser
import skillbill.scaffold.model.BaselineReviewCatalog
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.ReviewLaneCondition
import skillbill.scaffold.model.RoutingSignals
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import skillbill.telemetry.model.PrDescriptionGeneratedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeDiagnosticDegradationMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRejectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.TimeSource

class ReviewRecorder {
  val parentLaunches: MutableList<GoalRunnerSubtaskLaunchRequest> =
    Collections.synchronizedList(mutableListOf())
  val rubricResolutions: MutableList<String> = Collections.synchronizedList(mutableListOf())
  val diffQueries: MutableList<ReviewDiffQuery> = Collections.synchronizedList(mutableListOf())
  val savedAccounting: MutableList<ReviewAccountingRecord> =
    Collections.synchronizedList(mutableListOf())

  val durableLanes: MutableList<ReviewRunLane> = Collections.synchronizedList(mutableListOf())

  @Volatile var durableIntegrationPass: ReviewIntegrationPassRecord? = null

  val durableFindingVerdicts: MutableList<ReviewFindingVerdict> =
    Collections.synchronizedList(mutableListOf())
  val durableStageBoundaries: MutableList<ReviewStageBoundary> =
    Collections.synchronizedList(mutableListOf())

  @Volatile var durablePassClaims: ReviewPassClaimSnapshot? = null

  val durableFindingLanes: MutableMap<String, String> =
    Collections.synchronizedMap(mutableMapOf())

  @Volatile var durableSpecProjection: ReviewSpecProjectionReference? = null

  val stageDegradations: MutableList<ReviewStageDegradationMeasurement> =
    Collections.synchronizedList(mutableListOf())

  @Volatile var failStageDegradationWrite: Boolean = false

  val learningResolutions: MutableList<Pair<String?, String?>> =
    Collections.synchronizedList(mutableListOf())
  val savedSessionLearnings: MutableList<Pair<String, String>> =
    Collections.synchronizedList(mutableListOf())
  val diagnosticWarnings: MutableList<String> = Collections.synchronizedList(mutableListOf())

  val parentPrompts: List<String>
    get() = parentLaunches.mapNotNull { it.skillRunRequest.promptOverride }
}

data class RecordedWorkerResponse(
  val stdout: String = "NO_FINDINGS",
  val termination: AgentRunTermination = AgentRunTermination.Exited(0),
  val processStarted: Boolean = true,
  val mcpStartupObserved: Boolean = false,
  val liveness: AgentRunLivenessSnapshot? = null,
)

data class RecordedCommit(val sha: String, val subject: String, val diff: String)

data class ReviewHarnessConfig(
  val manifests: List<PlatformManifest>,
  val diff: String,
  val budget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
  val rubricBody: (String) -> String = { "governed rubric body for $it" },
  val response: (GoalRunnerSubtaskLaunchRequest) -> RecordedWorkerResponse = { RecordedWorkerResponse() },
  val evidenceBrokerFactory: ReviewEvidenceBrokerFactory =
    ReviewEvidenceBrokerFactory { binding -> FileSystemReviewEvidenceBroker(binding) },
  val parentLaunch: ((GoalRunnerSubtaskLaunchRequest) -> AgentRunLaunchOutcome)? = null,
  val simulateEvidenceReads: Boolean = true,
  val evidenceEndpointBinder: GovernedReviewEvidenceEndpointBinder =
    stubGovernedReviewEvidenceEndpointBinder(Files.createTempDirectory("review-endpoint")),
  val commits: List<RecordedCommit> = emptyList(),
  val learnings: List<LearningRecord> = emptyList(),
  val originScopeKeyPort: RepositoryOriginScopeKeyPort = HARNESS_ORIGIN_UNAVAILABLE,
)

fun ParallelCodeReviewRunner.reviewed(request: ParallelCodeReviewRequest): ParallelCodeReviewResult =
  when (val outcome = run(request)) {
    is ParallelCodeReviewRunOutcome.Reviewed -> outcome.result
    is ParallelCodeReviewRunOutcome.PlanningFailed -> error("planning failed: ${outcome.failure.message}")
  }

fun reviewHarness(
  config: ReviewHarnessConfig,
  recorder: ReviewRecorder,
): ParallelCodeReviewRunner {
  val database = recordingDatabase(recorder, config.learnings)
  val launcher =
    GoalRunnerSubtaskLauncher { request ->
      recorder.parentLaunches += request
      if (config.simulateEvidenceReads) simulateGovernedEvidenceReads(request.skillRunRequest)
      config.parentLaunch?.invoke(request)?.let { return@GoalRunnerSubtaskLauncher it }
      val response = config.response(request)
      agentRunLaunchFacts(
        agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
        termination = response.termination,
        stdout = response.stdout,
        stderr = "",
        liveness = response.liveness,
        processStarted = response.processStarted && response.termination != AgentRunTermination.SpawnFailed,
        mcpStartupObserved = response.mcpStartupObserved,
      ) as AgentRunLaunchOutcome
    }
  return parallelCodeReviewRunnerOf(
    diffResolver = recordingDiffResolver(config, recorder),
    repoLocalConfig =
      object : RepoLocalConfigPort {
        override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest) =
          ReadRepoLocalConfigResult(RepoLocalConfig.defaults().copy(reviewContextBudget = config.budget))
      },
    reviewContextEnvelopeValidator = AcceptingReviewContextEnvelopeValidator,
    reviewRubricResolver = recordingRubricResolver(recorder, config.rubricBody),
    reviewSpecialistContractProvider = ClasspathReviewSpecialistContractProvider(),
    database = database,
    installedPackCatalog = InstalledPlatformPackCatalogPort { config.manifests },
    specIntentProjectionResolver =
      SpecIntentProjectionResolver(
        FileSystemDecompositionManifestFileStore(),
        DecompositionManifestSchemaValidator(),
        SpecIntentProjectionExtractor(
          AcceptingReviewContextEnvelopeValidator,
          FileSystemDecompositionManifestFileStore(),
        ),
      ),
    parentReviewLauncher = launcher,
    reviewEvidenceBrokerFactory = config.evidenceBrokerFactory,
    governedEvidenceEndpointBinder = config.evidenceEndpointBinder,
    originScopeKeyPort = config.originScopeKeyPort,
    learningsDiagnostics = recordingDiagnostics(recorder),
  )
}

fun parallelCodeReviewRunnerOf(
  diffResolver: DiffResolverPort,
  repoLocalConfig: RepoLocalConfigPort,
  reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  reviewRubricResolver: ReviewRubricResolver,
  reviewSpecialistContractProvider: ReviewSpecialistContractProvider,
  database: DatabaseSessionFactory,
  installedPackCatalog: InstalledPlatformPackCatalogPort,
  specIntentProjectionResolver: SpecIntentProjectionResolver,
  parentReviewLauncher: GoalRunnerSubtaskLauncher,
  reviewEvidenceBrokerFactory: ReviewEvidenceBrokerFactory,
  governedEvidenceEndpointBinder: GovernedReviewEvidenceEndpointBinder,
  sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort =
    DERIVING_SHARED_EVIDENCE_RESOLVER,
  sharedEvidenceLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort? = null,
  nativeAgentPreflight: ReviewNativeAgentPreflightPort = NO_OP_REVIEW_NATIVE_AGENT_PREFLIGHT,
  reviewLaunchAgentStaging: ReviewLaunchAgentStagingPort = NO_OP_REVIEW_LAUNCH_AGENT_STAGING,
  registerParse: (String) -> ParallelReviewParseResult = ParallelReviewFindingParser::parse,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort = CanonicalRepositoryRoot,
  originScopeKeyPort: RepositoryOriginScopeKeyPort = HARNESS_ORIGIN_UNAVAILABLE,
  learningsDiagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
  clock: Clock = Clock.systemUTC(),
): ParallelCodeReviewRunner {
  val persistence = RuntimeOwnedPersistenceBoundary(database, NoopRuntimeDiagnostics)
  val planning =
    ParallelCodeReviewRunnerPlanning(
      diffResolver = diffResolver,
      repoLocalConfig = repoLocalConfig,
      reviewContextEnvelopeValidator = reviewContextEnvelopeValidator,
      reviewSpecialistContractProvider = reviewSpecialistContractProvider,
      sharedEvidenceResolver = sharedEvidenceResolver,
      sharedEvidenceLocatorReader = sharedEvidenceLocatorReader,
      specIntentProjectionResolver = specIntentProjectionResolver,
      rubricPlanning = ParallelCodeReviewRunnerRubricPlanning(reviewRubricResolver, installedPackCatalog),
      lanePlanRecording = ParallelCodeReviewRunnerLanePlanRecording(persistence, clock),
      repositoryEnclosingRootPort = repositoryEnclosingRootPort,
      reviewLearningsResolver =
        ReviewLearningsResolver(database, originScopeKeyPort, learningsDiagnostics),
    )
  val laneLaunch =
    ParallelCodeReviewRunnerLaneLaunch(
      parentReviewLauncher = parentReviewLauncher,
      reviewEvidenceBrokerFactory = reviewEvidenceBrokerFactory,
      governedEvidenceEndpointBinder = governedEvidenceEndpointBinder,
      reviewLaunchAgentStaging = reviewLaunchAgentStaging,
      sharedEvidenceLocatorReader = sharedEvidenceLocatorReader,
      failureAdmission = ParallelCodeReviewRunnerFailureAdmission(registerParse),
      activityStampWriter =
        AgentActivityStampWriter(database, Clock.systemUTC(), NoopRuntimeDiagnostics, TimeSource.Monotonic),
    )
  return ParallelCodeReviewRunner(
    planning = planning,
    laneLaunch = laneLaunch,
    resultAssembly =
      ParallelCodeReviewRunnerResultAssembly(
        parentReviewLauncher,
        reviewContextEnvelopeValidator,
        persistence,
        clock,
      ),
    verificationStages =
      ParallelCodeReviewRunnerVerificationStages(
        parentReviewLauncher,
        reviewContextEnvelopeValidator,
        persistence,
        clock,
      ),
    runtimeOwnedPersistence = persistence,
    nativeAgentPreflight = nativeAgentPreflight,
  )
}

private object AcceptingReviewContextEnvelopeValidator : ReviewContextEnvelopeValidator {
  override fun validate(
    envelope: ReviewContextWireMap,
    sourceLabel: String,
  ) = Unit

  override fun validateSpecIntentProjection(
    envelope: ReviewContextWireMap,
    sourceLabel: String,
  ) = Unit
}

private fun recordingDiffResolver(
  config: ReviewHarnessConfig,
  recorder: ReviewRecorder,
) = object : DiffResolverPortDefaults() {
  override fun reviewWorktreeFileIdentities(
    root: Path,
    paths: List<String>,
  ): Map<String, ReviewCheckpointFileIdentity> = emptyMap()

  override fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): String = revision

  override fun currentBranchName(repoRoot: Path): String = "HEAD"

  override fun firstParentCommits(
    repoRoot: Path,
    base: String,
    head: String,
  ): List<String> = config.commits.map { it.sha }

  override fun commitMetadata(
    repoRoot: Path,
    sha: String,
  ): ReviewCommitMetadata =
    config.commits.single { it.sha == sha }.let { commit ->
      ReviewCommitMetadata(listOf(parentOf(config.commits, commit)), commit.subject)
    }

  override fun indexEntries(repoRoot: Path): List<ReviewIndexEntry> = emptyList()

  override fun untrackedPaths(repoRoot: Path): List<String> = emptyList()

  override fun diff(
    repoRoot: Path,
    query: ReviewDiffQuery,
  ): String {
    recorder.diffQueries += query
    return when (query) {
      is ReviewDiffQuery.CommitRange ->
        config.commits.firstOrNull {
          it.sha == query.head && parentOf(config.commits, it) == query.base
        }?.diff ?: config.diff
      else -> config.diff
    }
  }
}

const val HARNESS_BASE_REVISION: String = "base-revision"

const val HARNESS_HEAD_REVISION: String = "head-revision"

private fun parentOf(
  commits: List<RecordedCommit>,
  commit: RecordedCommit,
): String = commits.getOrNull(commits.indexOf(commit) - 1)?.sha ?: HARNESS_BASE_REVISION

private fun recordingRubricResolver(
  recorder: ReviewRecorder,
  rubricBody: (String) -> String,
) = object : ReviewRubricResolver {
  override fun resolve(manifest: PlatformManifest?): ResolvedReviewRubric {
    recorder.rubricResolutions += manifest?.slug ?: "generic"
    return ResolvedReviewRubric("parallel-code-review", rubricBody("parallel-code-review"))
  }

  override fun resolve(
    manifest: PlatformManifest?,
    evidence: List<ReviewOwnedFileEvidence>,
    specialistSkillName: String,
  ): ResolvedReviewRubric {
    recorder.rubricResolutions += specialistSkillName
    return ResolvedReviewRubric(
      rubricId = specialistSkillName,
      body = rubricBody(specialistSkillName),
      area = specialistSkillName.substringAfter("-code-review-", "generic"),
    )
  }
}

private fun recordingDatabase(
  recorder: ReviewRecorder,
  seededLearnings: List<LearningRecord>,
): DatabaseSessionFactory {
  val learnings = recordingLearnings(recorder, seededLearnings)
  val reviews =
    Proxy.newProxyInstance(
      ReviewRepository::class.java.classLoader,
      arrayOf(ReviewRepository::class.java),
    ) { _, method, args ->
      when (method.name) {
        "saveAccounting" -> recorder.savedAccounting.add(args[0] as ReviewAccountingRecord).let { }
        "loadAccounting" -> null
        "recordFindingLaneAttribution" -> {
          @Suppress("UNCHECKED_CAST")
          recorder.durableFindingLanes.putAll(args[1] as Map<String, String>)
        }
        "replaceReviewRunLanes" -> {
          @Suppress("UNCHECKED_CAST")
          val lanes = args[1] as List<ReviewRunLane>
          recorder.durableLanes.clear()
          recorder.durableLanes.addAll(lanes)
        }
        "fetchReviewRunLanes" -> recorder.durableLanes.toList()
        "recordIntegrationPass" -> {
          @Suppress("UNCHECKED_CAST")
          recorder.durableIntegrationPass = args[1] as ReviewIntegrationPassRecord
        }
        "fetchIntegrationPass" -> recorder.durableIntegrationPass
        "recordFindingVerdicts" -> {
          @Suppress("UNCHECKED_CAST")
          val verdicts = args[1] as List<ReviewFindingVerdict>
          verdicts.forEach { incoming ->
            recorder.durableFindingVerdicts.removeAll {
              it.findingRef == incoming.findingRef && it.stage == incoming.stage
            }
            recorder.durableFindingVerdicts += incoming
          }
        }
        "fetchFindingVerdicts" -> recorder.durableFindingVerdicts.toList()
        "recordReviewPassClaims" -> {
          @Suppress("UNCHECKED_CAST")
          val incoming = args[1] as List<ParallelReviewMergedFinding>
          val existing = recorder.durablePassClaims?.findings
          if (incoming.isEmpty() && !existing.isNullOrEmpty()) {
            Unit
          } else {
            recorder.durablePassClaims = ReviewPassClaimSnapshot(incoming)
          }
        }
        "fetchReviewPassClaims" -> recorder.durablePassClaims
        "recordStageBoundary" -> {
          val boundary = args[1] as ReviewStageBoundary
          recorder.durableStageBoundaries.removeAll { it.stage == boundary.stage }
          recorder.durableStageBoundaries += boundary
        }
        "fetchStageBoundaries" -> recorder.durableStageBoundaries.toList()
        "recordSpecProjectionReference" -> {
          recorder.durableSpecProjection = args[1] as ReviewSpecProjectionReference
        }
        "fetchSpecProjectionReference" -> recorder.durableSpecProjection
        else -> error("Unexpected review repository call: ${method.name}")
      }
    } as ReviewRepository
  val unitOfWork =
    Proxy.newProxyInstance(
      UnitOfWork::class.java.classLoader,
      arrayOf(UnitOfWork::class.java),
    ) { _, method, _ ->
      when (method.name) {
        "getReviews" -> reviews
        "getLearnings" -> learnings
        "getLifecycleTelemetry" -> recordingLifecycleTelemetry(recorder)
        "getDbPath" -> Path.of("/tmp/recording-review.db")
        else -> error("Unexpected unit-of-work call: ${method.name}")
      }
    } as UnitOfWork
  return object : DatabaseSessionFactory {
    override fun resolveDbPath() = unitOfWork.dbPath

    override fun databaseExists() = true

    override fun <T> read(block: (UnitOfWork) -> T): T = block(unitOfWork)

    override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

    override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unitOfWork)
  }
}

private fun recordingLifecycleTelemetry(recorder: ReviewRecorder): LifecycleTelemetryRepository =
  object : LifecycleTelemetryRepository {
    override fun reviewStageDegradation(record: ReviewStageDegradationMeasurement) {
      check(!recorder.failStageDegradationWrite) { "the degradation store rejected the write" }
      recorder.stageDegradations += record
    }

    override fun featureTaskRuntimeProjectionMeasurement(record: FeatureTaskRuntimeProjectionMeasurement) = Unit

    override fun featureTaskRuntimeSharedEvidence(record: FeatureTaskRuntimeSharedEvidenceMeasurement) = Unit

    override fun featureTaskRuntimeRejection(record: FeatureTaskRuntimeRejectionMeasurement) = Unit

    override fun featureTaskRuntimeDiagnosticDegradation(record: FeatureTaskRuntimeDiagnosticDegradationMeasurement) =
      Unit

    override fun featureTaskRuntimeStarted(
      record: FeatureTaskRuntimeStartedRecord,
      level: String,
    ) = Unit

    override fun featureTaskRuntimeFinished(
      record: FeatureTaskRuntimeFinishedRecord,
      level: String,
    ) = Unit

    override fun qualityCheckStarted(
      record: QualityCheckStartedRecord,
      level: String,
    ) = Unit

    override fun qualityCheckFinished(
      record: QualityCheckFinishedRecord,
      level: String,
    ) = Unit

    override fun featureVerifyStarted(
      record: FeatureVerifyStartedRecord,
      level: String,
    ) = Unit

    override fun featureVerifyFinished(
      record: FeatureVerifyFinishedRecord,
      level: String,
    ) = Unit

    override fun prDescriptionGenerated(
      record: PrDescriptionGeneratedRecord,
      level: String,
    ) = Unit

    override fun goalStarted(
      record: GoalStartedRecord,
      level: String,
    ) = Unit

    override fun goalSubtaskFinished(
      record: GoalSubtaskFinishedRecord,
      level: String,
    ) = Unit

    override fun goalFinished(
      record: GoalFinishedRecord,
      level: String,
    ) = Unit

    override fun goalIssueFinished(
      record: GoalIssueFinishedRecord,
      level: String,
    ) = Unit
  }

private fun recordingCatalogGateway(manifests: List<PlatformManifest>): ScaffoldCatalogGateway =
  object : ScaffoldCatalogGateway {
    override fun approvedCodeReviewAreas() = emptySet<String>()

    override fun preShellFamilies() = emptySet<String>()

    override fun shelledFamilies() = emptySet<String>()

    override fun platformPackPresets() = emptyMap<String, String>()

    override fun scaffoldPayloadVersion() = "1.0"

    override fun discoverPilotedPlatformPacks(packsRoot: Path) = emptyList<PilotedPlatformPackProjection>()

    override fun discoverPlatformManifests(packsRoot: Path) = manifests

    override fun discoverBaselineReviewCatalog(packsRoot: Path) =
      BaselineReviewCatalog(packs = emptyList(), compositionEdges = emptyList(), layerSuggestions = emptyList())
  }

fun harnessRequest(
  repoRoot: Path = Files.createTempDirectory("review-e2e"),
  agent1Id: String = "codex",
  timeout: Duration? = null,
  reviewRunId: String? = null,
  prelaunchExpansions: List<ReviewPrelaunchExpansion> = emptyList(),
  codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DELEGATED,
  scope: ParallelReviewScope = ParallelReviewScope.BRANCH,
) = ParallelCodeReviewRequest(
  agent1Id = agent1Id,
  scope = scope,
  repoRoot = repoRoot,
  timeout = timeout,
  codeReviewMode = codeReviewMode,
  reviewRunId = reviewRunId,
  baseRevision = HARNESS_BASE_REVISION,
  headRevision = HARNESS_HEAD_REVISION,
  prelaunchExpansions = prelaunchExpansions,
)

fun reviewPack(
  slug: String,
  areas: List<String>,
  layers: List<CodeReviewBaselineLayer> = emptyList(),
  routingSignals: List<String> = emptyList(),
  contentSignals: List<String> = emptyList(),
  fallback: Boolean = false,
) = PlatformManifest(
  slug = slug,
  packRoot = Path.of("platform-packs", slug).toFileLocation(),
  contractVersion = "1.3",
  routingSignals =
    RoutingSignals(
      strong = routingSignals,
      tieBreakers = emptyList(),
      path = routingSignals,
      content = contentSignals,
    ),
  declaredCodeReviewAreas = areas,
  declaredFiles =
    DeclaredFiles(
      baseline =
        Path.of(
          "platform-packs",
          slug,
          "code-review",
          "bill-$slug-code-review",
          "content.md",
        ).toFileLocation(),
      areas =
        areas.associateWith {
          Path.of("platform-packs", slug, "code-review", "bill-$slug-code-review-$it", "content.md")
            .toFileLocation()
        },
    ),
  areaMetadata = emptyMap(),
  laneConditions = areas.associateWith { ReviewLaneCondition(path = listOf("*")) },
  codeReviewComposition = layers.takeIf { it.isNotEmpty() }?.let(::CodeReviewComposition),
  fallbackCapabilities = if (fallback) setOf("code-review") else emptySet(),
)

fun sparseReviewPack(
  slug: String,
  requiredArea: String,
  pathAreas: Map<String, List<String>>,
  routingSignals: List<String> = listOf("*.kt"),
): PlatformManifest {
  val areas = listOf(requiredArea) + pathAreas.keys.toList()
  return reviewPack(slug, areas, routingSignals = routingSignals).copy(
    laneConditions =
      buildMap {
        put(requiredArea, ReviewLaneCondition(required = true))
        pathAreas.forEach { (area, paths) -> put(area, ReviewLaneCondition(path = paths)) }
      },
  )
}

fun reviewLayer(
  slug: String,
  required: Boolean = true,
) = CodeReviewBaselineLayer(
  platform = slug,
  skill = "bill-$slug-code-review",
  scope = CodeReviewCompositionScope.SameReviewScope,
  required = required,
  mode = CodeReviewCompositionMode.KmpBaseline,
)

fun diffForPaths(vararg paths: String): String =
  diffForChanges(*paths.map { it to "val changed = \"$it\"" }.toTypedArray())

fun diffForChanges(vararg changes: Pair<String, String>): String =
  changes.joinToString("\n") { (path, added) ->
    """
    diff --git a/$path b/$path
    --- a/$path
    +++ b/$path
    @@ -1,2 +1,3 @@
    +$added
    """.trimIndent()
  }

fun simulateGovernedEvidenceReads(request: SkillRunRequest) {
  val broker = request.reviewEvidenceBroker ?: return
  val lane = runCatching { broker.accounting().lane }.getOrNull() ?: return
  val prompt = request.promptOverride ?: return
  val paths =
    prompt.lineSequence()
      .filter { it.startsWith("Owned paths: ") }
      .flatMap { line -> OWNED_PATH.findAll(line.removePrefix("Owned paths: ")).map { it.groupValues[1] } }
      .distinct()
      .toList()
  if (paths.isEmpty()) return
  runCatching {
    broker.readBatch(
      ReviewEvidenceBatchRequest(
        lane = lane,
        requests = paths.map { ReviewEvidenceRequest(lane = lane, path = it) },
      ),
    )
  }
}

private val OWNED_PATH = Regex("\"([^\"]+)\"")

fun reviewFileSystemDiffResolver(): DiffResolverPort = FileSystemDiffResolver()

fun brokerDenyingUnit(deniedPath: String): ReviewEvidenceBrokerFactory =
  ReviewEvidenceBrokerFactory { binding ->
    val delegate = FileSystemReviewEvidenceBroker(binding)
    val hunkId = binding.projectedHunks.first { it.path == deniedPath }.hunkId
    val commitSha = binding.assignment.assignedBundle.entries.first { hunkId in it.hunkIds }.commitSha
    val deniedUnit = "$commitSha@$deniedPath"
    object : ReviewEvidenceBroker by delegate {
      override fun accounting(): ReviewLaneAccounting =
        delegate.accounting().copy(
          budgetDimension = LANE_EVIDENCE_BYTES_DIMENSION,
          unreviewedUnits = listOf(deniedUnit),
        )
    }
  }
