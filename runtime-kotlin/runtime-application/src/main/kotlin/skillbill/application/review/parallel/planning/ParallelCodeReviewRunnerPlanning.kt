package skillbill.application.review.parallel.planning

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.branchName
import skillbill.application.review.learnings.ReviewLearningsResolver
import skillbill.application.review.model.ParallelCodeReviewPlanned
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.application.review.parallel.runner.PARALLEL_REVIEW_SHARED_EVIDENCE_WORKFLOW_ID
import skillbill.application.review.parallel.runner.ParallelCodeReviewCompiledLaunches
import skillbill.application.review.parallel.runner.ParallelCodeReviewInitialRun
import skillbill.application.review.parallel.runner.PlanningPrepareArgs
import skillbill.application.review.parallel.verification.ParallelCodeReviewRunnerLanePlanRecording
import skillbill.application.review.spec.SpecIntentProjectionResolver
import skillbill.application.reviewevidence.ReviewCommitRange
import skillbill.application.reviewevidence.SharedReviewEvidenceProjection
import skillbill.application.reviewevidence.SharedReviewEvidenceQuery
import skillbill.application.reviewevidence.SharedReviewEvidenceRecord
import skillbill.application.reviewevidence.SharedReviewEvidenceResolution
import skillbill.application.reviewevidence.model.DiffResolution
import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.error.shellcontent.ReviewHunkEvidenceLocatorMissingError
import skillbill.install.model.SupportedAgent
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.diff.model.ReviewDiffQuery
import skillbill.ports.diff.model.ReviewIndexEntry
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.repository.toFileLocation
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import skillbill.ports.review.repository.ReviewSpecialistContractProvider
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.execution.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.execution.SpecIntentResolution
import skillbill.review.context.model.execution.toCodeReviewExecutionMode
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

@Inject
class ParallelCodeReviewRunnerPlanning(
  private val diffResolver: DiffResolverPort,
  private val repoLocalConfig: RepoLocalConfigPort,
  private val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  private val reviewSpecialistContractProvider: ReviewSpecialistContractProvider,
  private val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
  private val sharedEvidenceLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort?,
  private val specIntentProjectionResolver: SpecIntentProjectionResolver,
  private val rubricPlanning: ParallelCodeReviewRunnerRubricPlanning,
  private val lanePlanRecording: ParallelCodeReviewRunnerLanePlanRecording,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val reviewLearningsResolver: ReviewLearningsResolver,
) {
  internal fun prepareInitialRun(
    originalRequest: ParallelCodeReviewRequest,
  ): ParallelCodeReviewPlanned<ParallelCodeReviewInitialRun> {
    val agent1 =
      when (val resolved = resolveAgent(originalRequest.agent1Id, "--agent1")) {
        is ParallelCodeReviewPlanned.Failed -> return resolved
        is ParallelCodeReviewPlanned.Ready -> resolved.value
      }
    val revisions =
      when (val resolved = resolveReviewRevisions(originalRequest)) {
        is DiffResolution.Unresolved -> return resolved.toPlanningFailed()
        is DiffResolution.Resolved -> resolved.value
      }
    val sharedEvidence =
      when (
        val resolved =
          SharedReviewEvidenceResolution(sharedEvidenceResolver, diffResolver).resolve(
            SharedReviewEvidenceQuery(
              repoRoot = originalRequest.repoRoot,
              workflowId = originalRequest.reviewRunId ?: PARALLEL_REVIEW_SHARED_EVIDENCE_WORKFLOW_ID,
              scope = originalRequest.scope,
              range = ReviewCommitRange(revisions.first, revisions.second),
              suppliedDiff = hasSuppliedDiff(originalRequest),
            ),
          ) { resolveDiff(originalRequest, revisions) }
      ) {
        is DiffResolution.Unresolved -> return resolved.toPlanningFailed()
        is DiffResolution.Resolved -> resolved.value
      }
    return prepareWithEvidence(originalRequest, agent1, revisions, sharedEvidence)
  }

  private fun prepareWithEvidence(
    originalRequest: ParallelCodeReviewRequest,
    agent1: SupportedAgent,
    revisions: Pair<String, String>,
    sharedEvidence: SharedReviewEvidenceRecord,
  ): ParallelCodeReviewPlanned<ParallelCodeReviewInitialRun> {
    val diffText = sharedEvidence.aggregateDiff
    val evidence = ReviewDiffEvidence.parse(diffText)
    val detection =
      when (val detected = detectStack(evidence)) {
        is ParallelCodeReviewPlanned.Failed -> return detected
        is ParallelCodeReviewPlanned.Ready -> detected.value
      }
    val budget =
      repoLocalConfig.readRepoLocalConfig(ReadRepoLocalConfigRequest(originalRequest.repoRoot))
        .config.reviewContextBudget
    val lane1ResolvedMode = resolvedMode(originalRequest)
    val request = originalRequest.withResolvedTier(lane1ResolvedMode.toCodeReviewExecutionMode())
    val resolvedMode = ReviewExecutionModePolicy.resolve(request.resolvedTier ?: request.codeReviewMode)
    val reviewSessionId = request.reviewSessionId ?: mintReviewSessionId()
    val learnings =
      reviewLearningsResolver.resolve(
        repoRoot = request.repoRoot,
        routedSkill = routedReviewSkillName(detection.routed),
        reviewSessionId = reviewSessionId,
      )
    val prepareArgs =
      PlanningPrepareArgs(
        request = request,
        revisions = revisions,
        diffText = diffText,
        evidence = evidence,
        sharedSequence = sharedEvidence.sequence,
        routedManifests = detection.routed,
        manifests = detection.manifests,
        ownedPathsBySlug = detection.ownedPathsBySlug,
        agentIds = listOf(agent1.id),
        budget = budget,
        evidenceStorePath = sharedEvidence.storePath,
        learningsReferences = learnings.references,
      )
    val compiled =
      when (val prepared = prepare(prepareArgs)) {
        is ParallelCodeReviewPlanned.Failed -> return prepared
        is ParallelCodeReviewPlanned.Ready -> prepared.value
      }
    return ParallelCodeReviewPlanned.Ready(
      ParallelCodeReviewInitialRun(
        request = request,
        detection = detection,
        resolvedMode = resolvedMode,
        agent1Id = agent1.id,
        preparedLaunchRequests = compiled.toRun,
        compiledLaunchRequests = compiled.all,
        budget = budget,
        specIntentResolution = compiled.specIntentResolution,
        reviewSessionId = reviewSessionId,
        appliedLearnings = learnings.appliedSummary,
      ),
    )
  }

  fun completeEmptySuppliedDelta(
    request: ParallelCodeReviewRequest,
    recordAdjudicationBoundary: (String) -> Unit,
  ): ParallelCodeReviewResult {
    val budget =
      repoLocalConfig.readRepoLocalConfig(ReadRepoLocalConfigRequest(request.repoRoot))
        .config.reviewContextBudget
    val specIntent =
      specIntentProjectionResolver.resolve(
        SpecIntentProjectionResolveRequest(
          repoRoot = request.repoRoot.toFileLocation(),
          explicitSpecPath = request.specPath?.toFileLocation(),
          branchName = currentHeadBranchName(request.repoRoot),
          changedPaths = emptyList(),
          budget = budget,
        ),
      )
    lanePlanRecording.recordSpecIntent(request.reviewRunId, specIntent)
    request.reviewRunId?.let { runId ->
      if (specIntent is SpecIntentResolution.Resolved) {
        recordAdjudicationBoundary(runId)
      }
    }
    return ParallelCodeReviewResult(
      mergeResult = ParallelReviewMergeResult(findings = emptyList(), formattedOutput = "NO_FINDINGS"),
      lane1 =
        ParallelReviewLaneStatus(
          agentId = request.agent1Id,
          success = true,
          reviewDisposition = ReviewLaneReviewDisposition.COMPLETE,
        ),
    )
  }

  private fun resolvedMode(request: ParallelCodeReviewRequest) =
    ReviewExecutionModePolicy.resolveWithRule(
      requested = request.resolvedTier ?: request.codeReviewMode,
    ).resolvedMode

  private fun prepare(args: PlanningPrepareArgs): ParallelCodeReviewPlanned<ParallelCodeReviewCompiledLaunches> {
    if (
      sharedEvidenceLocatorReader != null &&
      args.evidenceStorePath.isNullOrBlank()
    ) {
      throw ReviewHunkEvidenceLocatorMissingError(args.evidenceStorePath.orEmpty())
    }
    val plannedRubrics =
      rubricPlanning.resolvePlannedRubrics(
        args.evidence,
        args.routedManifests,
        args.manifests,
        args.ownedPathsBySlug,
      )
    val (baseRevision, headRevision) = args.revisions
    val commitSequence =
      when (val projected = SharedReviewEvidenceProjection.project(args.sharedSequence, args.evidence)) {
        is DiffResolution.Unresolved -> return projected.toPlanningFailed()
        is DiffResolution.Resolved -> projected.value
      }
    val specIntentResolution = resolveSpecIntent(args.request, args.evidence, args.budget)
    val compiled =
      ParallelReviewPreparationCompiler.compile(
        input =
          ParallelReviewPreparationInput(
            diff = args.diffText,
            evidence = args.evidence,
            commitSequence = commitSequence,
            stack = args.routedManifests.joinToString("+") { it.slug }.ifBlank { null },
            agents = args.agentIds,
            repositoryEnclosingRootPort = repositoryEnclosingRootPort,
            repoRoot = args.request.repoRoot,
            routedPacks = args.routedManifests.map { it.slug },
            lanes = plannedRubrics,
            reviewRunId = args.request.reviewRunId,
            baseRevision = baseRevision,
            headRevision = headRevision,
            prelaunchExpansions = args.request.prelaunchExpansions,
            baselineUntrackedPolicy = args.request.baselineUntrackedPolicy,
            specIntentResolution = specIntentResolution,
            evidenceStorePath = args.evidenceStorePath,
            learningsReferences = args.learningsReferences,
          ),
        budget = args.budget,
        envelopeValidator = reviewContextEnvelopeValidator,
        specialistContract = reviewSpecialistContractProvider.authoritativeContract(),
        hunkLocatorReader = sharedEvidenceLocatorReader,
      )
    val selected = lanePlanRecording.selectLaunchesForResume(args.request.reviewRunId, compiled)
    lanePlanRecording.recordPlannedLanes(args.request.reviewRunId, plannedRubrics, selected)
    lanePlanRecording.recordSpecIntent(args.request.reviewRunId, specIntentResolution)
    return ParallelCodeReviewPlanned.Ready(
      ParallelCodeReviewCompiledLaunches(
        all = compiled,
        toRun = selected,
        specIntentResolution = specIntentResolution,
      ),
    )
  }

  private fun resolveSpecIntent(
    request: ParallelCodeReviewRequest,
    evidence: ReviewDiffEvidence,
    budget: ReviewContextBudgetPolicy,
  ): SpecIntentResolution =
    specIntentProjectionResolver.resolve(
      SpecIntentProjectionResolveRequest(
        repoRoot = request.repoRoot.toFileLocation(),
        explicitSpecPath = request.specPath?.toFileLocation(),
        branchName = currentHeadBranchName(request.repoRoot),
        changedPaths = evidence.files.map { it.path },
        budget = budget,
      ),
    )

  fun currentHeadBranchName(repoRoot: Path): String = diffResolver.currentBranchName(repoRoot).orEmpty()

  internal fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): String? = diffResolver.resolveCommit(repoRoot, revision)

  internal fun mergeBase(
    repoRoot: Path,
    revision: String,
  ): String? = diffResolver.mergeBase(repoRoot, revision)

  internal fun pullRequestBaseCommit(repoRoot: Path): String? = diffResolver.pullRequestBaseCommit(repoRoot)

  internal fun indexEntries(repoRoot: Path): List<ReviewIndexEntry>? = diffResolver.indexEntries(repoRoot)

  internal fun untrackedPaths(repoRoot: Path): List<String>? = diffResolver.untrackedPaths(repoRoot)

  internal fun diff(
    repoRoot: Path,
    query: ReviewDiffQuery,
  ): String? = diffResolver.diff(repoRoot, query)

  internal fun readDiff(
    path: Path,
    maxBytes: Long,
  ): String? = diffResolver.readDiff(path, maxBytes)

  internal fun reviewWorktreeFileIdentities(
    root: Path,
    paths: List<String>,
  ): Map<String, ReviewCheckpointFileIdentity> = diffResolver.reviewWorktreeFileIdentities(root, paths)

  internal fun installedManifests(): List<PlatformManifest> = rubricPlanning.installedManifests()
}
