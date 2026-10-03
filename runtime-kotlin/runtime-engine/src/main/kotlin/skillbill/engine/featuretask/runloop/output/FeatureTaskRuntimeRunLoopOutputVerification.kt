package skillbill.engine.featuretask.runloop.output

import skillbill.application.decomposition.baseBranch
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.lifecycle.checkpoint.goalScopedBaselinePaths
import skillbill.engine.featuretask.lifecycle.continuation.matches
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeImplementationContinuation
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeSharedReviewEvidenceResolved
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeImplementationObligations
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.engine.featuretask.phase.core.featureTaskRuntimeImplementationContinuationFrom
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeSharedReviewEvidenceResolver
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
import skillbill.engine.featuretask.runloop.checkpoint.goalStartBaselinePaths
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.CheckpointRevisions
import skillbill.engine.featuretask.runloop.core.CompletionProjectionRejectionArgs
import skillbill.engine.featuretask.runloop.core.PersistAcceptedOutputArgs
import skillbill.engine.featuretask.runloop.core.PersistStandardAcceptedOutputArgs
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestArgs
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestAttachments
import skillbill.engine.featuretask.runloop.core.PhaseStateWriteArgs
import skillbill.engine.featuretask.runloop.core.RepositoryCheckpointResolutionArgs
import skillbill.engine.featuretask.runloop.core.TerminalOutputAttemptArgs
import skillbill.engine.featuretask.runloop.core.isFeatureSpecPathForIssue
import skillbill.engine.featuretask.runloop.core.reconcileCheckpointPathInventory
import skillbill.engine.featuretask.runloop.observability.completedEvent
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.runner.boundedSchemaGateDetail
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptPlanAuthorization
import skillbill.engine.featuretask.slot.attempt.PhaseOutputSettlementContext
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence

object FeatureTaskRuntimeRunLoopOutputVerification {
  internal fun implementationObligations(run: PhaseRun): FeatureTaskRuntimeImplementationObligations =
    FeatureTaskRuntimeImplementationObligations(
      plannedTaskIds = emptyList(),
      carriedRepairItemIds = emptyList(),
      loopId = run.reentry?.loopId,
      edgeIteration = run.reentry?.edgeIteration,
    )

  internal fun implementationContinuationFor(
    recorder: PhaseRunRecords,
    run: PhaseRun,
  ): FeatureTaskRuntimeImplementationContinuation? {
    if (!run.policy.mutating) return null
    val attempts =
      recorder.loadImplementationAttempts(run.request.workflowId)
        ?: return null
    return featureTaskRuntimeImplementationContinuationFrom(run.phaseId, attempts, implementationObligations(run))
      ?.takeIf { it.priorValueSegments.isNotEmpty() }
  }

  internal fun completionProjectionRejection(
    context: PhaseOutputSettlementContext,
    args: CompletionProjectionRejectionArgs,
  ): Pair<String, String>? =
    (context as? PhaseAttemptPlanAuthorization)
      ?.let { planAuthorization ->
        FeatureTaskRuntimeRunLoopOutputVerification
          .immediateConsumerProjectionGateReason(
            context = context,
            planAuthorization = planAuthorization,
            args = args,
          )?.let { "consumer-projection" to it }
      }

  internal fun immediateConsumerProjectionGateReason(
    context: PhaseOutputSettlementContext,
    planAuthorization: PhaseAttemptPlanAuthorization,
    args: CompletionProjectionRejectionArgs,
  ): String? {
    with(context) {
      val run = args.run
      val iteration = args.iteration
      val normalizedOutput = args.normalizedOutput
      val repairEvidence = args.repairEvidence
      val repositoryFingerprint = args.repositoryFingerprint
      if (!args.checksImmediateConsumerProjection) return null
      if (run.validationGateFindings != null) return null
      val producerIndex = transitionDeclaration.forwardPhaseIds.indexOf(run.phaseId)
      if (producerIndex < 0 || producerIndex == transitionDeclaration.forwardPhaseIds.lastIndex) return null
      val consumerPhaseId = transitionDeclaration.forwardPhaseIds[producerIndex + 1]
      val declaration =
        phaseDeclaration(
          consumerPhaseId,
          run.request.runInvariants.featureSize,
          planAuthorization.unselectedStepIds(),
        )
      val currentOutput =
        FeatureTaskRuntimePhaseOutput(
          phaseId = run.phaseId,
          iteration = iteration,
          payload = normalizedOutput.canonicalJson,
          normalizedOutput = normalizedOutput,
          repairEvidence = repairEvidence,
        )
      val outputs = progress.outputs().filterNot { it.phaseId == run.phaseId } + currentOutput
      val sessionObservations = settlementCoupling().sessionObservations
      val resolvedFingerprint =
        repositoryFingerprint?.takeIf(String::isNotBlank)
          ?: gitOperations
            .repositoryFingerprint(run.request.repoRoot)
            .value
            .takeIf(String::isNotBlank)
      val checkpoint =
        resolvedFingerprint
          ?.let(::FeatureTaskRuntimeRepositoryCheckpoint)
      val handoff =
        FeatureTaskRuntimeHandoffContract.assembleHandoff(
          FeatureTaskRuntimeHandoffAssemblyRequest(
            declaration = declaration,
            runInvariants = run.request.runInvariants,
            recordedOutputs = outputs,
            repositoryCheckpoint = checkpoint,
            expectedRepositoryCheckpoint = checkpoint,
            branchIdentity = sessionObservations.resolvedBranch,
            baseBranch =
              recorder
                .loadResolvedBranch(run.request.workflowId)
                ?.baseBranch
                ?: "main",
          ),
        )
      return try {
        FeatureTaskRuntimePhaseBriefingAssembler.assemble(
          handoff,
          run.request.workflowId,
          run.request.agentAddonSelection,
        )
        null
      } catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError) {
        "Phase '${run.phaseId}' reported 'completed' but its output cannot satisfy immediate consumer " +
          "'$consumerPhaseId': ${boundedSchemaGateDetail(error.message.orEmpty())}"
      }
    }
  }

  internal fun resolveSharedReviewEvidence(
    sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
    diffResolver: DiffResolverPort,
    run: PhaseRun,
    checkpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
  ): FeatureTaskRuntimeSharedReviewEvidenceResolved? {
    val declared =
      run.declaration.projectionDeclarations.any {
        it.sourceRef == FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence
      }
    if (!declared) return null
    return FeatureTaskRuntimeSharedReviewEvidenceResolver(
      sharedEvidenceResolver,
      diffResolver,
    ).resolve(run.request.repoRoot, run.request.workflowId, checkpoint, run.phaseId)
  }

  internal fun resolveRepositoryCheckpoint(
    args: RepositoryCheckpointResolutionArgs,
  ): FeatureTaskRuntimeRepositoryCheckpoint? =
    if (args.run.declaration.projectionDeclarations.none { projection ->
        projection.checkpointPolicy != FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED
      }
    ) {
      null
    } else {
      buildRepositoryCheckpoint(args)
    }

  internal fun terminalOutputAttempt(
    progress: FeatureTaskRuntimeProgressSnapshotAccess,
    loopTransitions: FeatureTaskRuntimeRunTransitionOwner,
    recorder: PhaseRunRecords,
    args: TerminalOutputAttemptArgs,
    blockedDisposition: FeatureTaskRuntimeFailureDisposition,
  ): AttemptResult {
    val run = args.run
    val iteration = args.iteration
    val reason = args.reason
    val outputMap = args.normalizedOutput.envelopeWireMap()
    val normalizedOutput = args.normalizedOutput
    val repairEvidence = args.repairEvidence
    val observability = args.observability
    val fileManifest = args.fileManifest
    val disposition =
      FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(
        outputMap,
        blockedDisposition,
      )
    if (disposition.retryOnResume && !run.policy.singleAgentSession) {
      val value = (outputMap[SharedPayloadKeys.PRODUCED_OUTPUTS] as? Map<*, *>)?.get(SharedPayloadKeys.VALUE)
      val continuationOutput =
        normalizedOutput.takeIf { run.policy.mutating && !(value as? String).isNullOrBlank() }
      return AttemptResult.RetryableTerminal(reason, fileManifest, disposition, normalizedOutput, continuationOutput)
    }
    return AttemptResult.settled(
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
        progress,
        loopTransitions,
        recorder,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = reason,
          observability = observability,
          payload = BlockAndPersistPayload(fileManifest = fileManifest, normalizedOutput = normalizedOutput),
          failureDisposition = disposition,
        ),
      ),
    )
  }

  internal fun persistAcceptedOutput(
    context: PhaseOutputSettlementContext,
    args: PersistAcceptedOutputArgs,
  ): AttemptResult {
    with(context) {
      val run = args.run
      val iteration = args.iteration
      val normalizedOutput = args.normalizedOutput
      val repairEvidence = args.repairEvidence
      val observability = args.observability
      val fileManifest = args.fileManifest
      val repositoryFingerprint = args.repositoryFingerprint
      val outputText = normalizedOutput.canonicalJson
      if (run.validationGateFindings != null) {
        return FeatureTaskRuntimeRunLoopOutputVerification.validationGatePersistedAttempt(
          run,
          iteration,
          normalizedOutput,
          repairEvidence,
          outputText,
        )
      }
      FeatureTaskRuntimeRunLoopOutputVerification
        .persistStandardAcceptedOutput(
          context,
          PersistStandardAcceptedOutputArgs(
            accepted =
              PersistAcceptedOutputArgs(
                run = run,
                iteration = iteration,
                normalizedOutput = normalizedOutput,
                repairEvidence = repairEvidence,
                observability = observability,
                fileManifest = fileManifest,
                repositoryFingerprint = repositoryFingerprint,
              ),
            outputText = outputText,
          ),
        )?.let { return it }
      observability.completedEvent(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
      return completedAttemptResult(run, iteration, outputText, normalizedOutput, repairEvidence)
    }
  }

  internal fun buildRepositoryCheckpoint(
    args: RepositoryCheckpointResolutionArgs,
  ): FeatureTaskRuntimeRepositoryCheckpoint? {
    val run = args.run
    val resolvedBranchRecord = args.recorder.loadResolvedBranch(run.request.workflowId)
    args.coupledRunTransitions.observeResolvedBranchForCheckpoint(resolvedBranchRecord?.branch)
    val goalReviewState = args.goalContinuationRecorder.reviewState(run.request.workflowId)
    val revisions =
      FeatureTaskRuntimeRunLoopOutputVerification.resolveCheckpointRevisions(
        args.gitOperations,
        run = run,
        headRevision = resolvedBranchRecord?.branch?.takeIf(String::isNotBlank) ?: "HEAD",
        baseRevision = goalReviewState?.reviewBaseSha ?: resolvedBranchRecord?.reviewBaseSha,
      ) ?: return null
    val ownedPaths =
      resolveCheckpointOwnedPaths(
        args = args,
        persistedOwnedPaths = resolvedBranchRecord?.workflowOwnedPaths,
        baselineOwnedPaths =
          goalScopedBaselinePaths(
            resolvedBranchRecord?.baselineOwnedPaths
              ?: goalReviewState?.baselineUntrackedPaths
              ?: resolvedBranchRecord?.baselineUntrackedPaths.orEmpty(),
            args.recorder.goalStartBaselinePaths(run.request),
          ),
        revisions = revisions,
      ) ?: return null
    val fingerprint =
      args.gitOperations
        .repositoryCheckpointFingerprint(
          run.request.repoRoot,
          revisions.base,
          revisions.head,
          ownedPaths,
        ).takeIf { it is WorkflowGitOperationResult.Ok }
        ?.value
        ?.takeIf(String::isNotBlank) ?: return null
    return FeatureTaskRuntimeRepositoryCheckpoint(
      fingerprint = fingerprint,
      baseRef = revisions.base,
      headRef = revisions.head,
      workingTreeOwnedPaths = ownedPaths,
    )
  }

  internal fun resolveCheckpointOwnedPaths(
    args: RepositoryCheckpointResolutionArgs,
    persistedOwnedPaths: List<String>?,
    baselineOwnedPaths: List<String>,
    revisions: CheckpointRevisions,
  ): List<String>? {
    val run = args.run
    val workingTreePaths =
      FeatureTaskRuntimeRunLoopOutputVerification.checkpointOwnedPaths(
        args.gitOperations,
        run,
        baselineOwnedPaths,
      ) ?: return null
    val committedPaths =
      revisions.base
        ?.let { base ->
          (
            args.gitOperations
              .runtimePhaseChangedPathsBetweenCommits(run.request.repoRoot, base, revisions.head)
              as? WorkflowGitNameListResult.Listed
          )?.names
            ?.distinct()
            ?.sorted()
            ?: return null
        }.orEmpty()
    val durableInventory = persistedOwnedPaths.orEmpty().filter(String::isNotBlank)
    val discovered =
      if (args.session.checkpointOwnershipDecided && durableInventory.isNotEmpty()) {
        durableInventory
      } else {
        (durableInventory + workingTreePaths).distinct()
      }
    val inventory =
      reconcileCheckpointPathInventory(
        repoRoot = run.request.repoRoot,
        issueKey = run.request.issueKey,
        specReference = run.request.runInvariants.specReference,
        workflowId = run.request.workflowId,
        paths = (discovered + committedPaths).distinct(),
      ).sorted()
    return inventory.takeIf {
      args.recorder.recordWorkflowOwnedPaths(
        run.request.workflowId,
        inventory,
      )
    }
  }

  internal fun resolveCheckpointRevisions(
    gitOperations: WorkflowGitOperations,
    run: PhaseRun,
    headRevision: String,
    baseRevision: String?,
  ): CheckpointRevisions? {
    val immutableHead =
      gitOperations
        .resolveCommit(run.request.repoRoot, headRevision)
        .takeIf { it is WorkflowGitOperationResult.Ok }
        ?.value
        ?.takeIf(String::isNotBlank)
        ?: gitOperations
          .headCommitSha(run.request.repoRoot)
          .takeIf { it is WorkflowGitOperationResult.Ok }
          ?.value
          ?.takeIf(String::isNotBlank)
        ?: return null
    val immutableBase =
      baseRevision?.let { revision ->
        gitOperations
          .resolveCommit(run.request.repoRoot, revision)
          .takeIf { it is WorkflowGitOperationResult.Ok }
          ?.value
          ?.takeIf(String::isNotBlank)
          ?: revision.takeIf { it.matches(Regex("^[0-9a-fA-F]{40,64}$")) }
      }
    if (baseRevision != null && immutableBase == null) return null
    return CheckpointRevisions(base = immutableBase, head = immutableHead)
  }

  internal fun checkpointOwnedPaths(
    gitOperations: WorkflowGitOperations,
    run: PhaseRun,
    baselineOwnedPaths: List<String>,
  ): List<String>? {
    val owned = gitOperations.repositoryOwnedPaths(run.request.repoRoot)
    if (owned !is WorkflowGitNameListResult.Listed) return null
    val baseline = baselineOwnedPaths.toSet()
    val paths =
      owned.names
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot { it in baseline }
        .filterNot { path -> isFeatureSpecPathForIssue(path, run.request.issueKey) }
        .distinct()
        .sorted()
    return paths
  }

  internal fun validationGatePersistedAttempt(
    run: PhaseRun,
    iteration: Int,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
    outputText: String,
  ): AttemptResult =
    AttemptResult.settled(
      PhaseOutcome.completed(
        FeatureTaskRuntimePhaseOutput(
          run.phaseId,
          iteration,
          outputText,
          normalizedOutput,
          repairEvidence,
        ),
      ),
    )

  internal fun persistStandardAcceptedOutput(
    context: PhaseOutputSettlementContext,
    args: PersistStandardAcceptedOutputArgs,
  ): AttemptResult? {
    with(context) {
      val accepted = args.accepted
      val run = accepted.run
      val iteration = accepted.iteration
      val normalizedOutput = accepted.normalizedOutput
      val repairEvidence = accepted.repairEvidence
      val observability = accepted.observability
      val fileManifest = accepted.fileManifest
      val repositoryFingerprint = accepted.repositoryFingerprint
      val outputText = args.outputText
      val phaseState =
        FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
          request,
          context.settlementCoupling().progress,
          goalContinuationRecorder,
          PhaseStateRequestArgs(
            write =
              PhaseStateWriteArgs(
                run = run,
                iteration = iteration,
                status = STATUS_COMPLETED,
                finished = true,
                outputArtifact = outputText,
              ),
            extras =
              PhaseStateRequestAttachments(
                fileManifest = fileManifest,
                normalizedOutput = normalizedOutput,
                repairEvidence = repairEvidence,
                repositoryFingerprint = repositoryFingerprint,
              ),
          ),
        )
      val inMemoryOutput =
        FeatureTaskRuntimePhaseOutput(
          run.phaseId,
          iteration,
          outputText,
          normalizedOutput,
          repairEvidence,
        )
      val persisted =
        coupledRunTransitions.persistAuthoritativePhaseCompletion(
          recorder = recorder,
          phaseState = phaseState,
          inMemoryOutput = inMemoryOutput,
        )
      if (!persisted) {
        val blockCoupling = context.settlementCoupling()
        return AttemptResult.settled(
          FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
            blockCoupling.progress,
            blockCoupling.transitions,
            recorder,
            PhaseBlockRequest(
              run = run,
              attemptCount = iteration,
              reason = "Validated phase output could not be persisted to the authoritative workflow record.",
              observability = observability,
              payload = BlockAndPersistPayload(fileManifest = fileManifest),
              failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
            ),
          ),
        )
      }
      return null
    }
  }

  internal fun completedAttemptResult(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ): AttemptResult =
    AttemptResult.settled(
      PhaseOutcome.completed(
        FeatureTaskRuntimePhaseOutput(
          run.phaseId,
          iteration,
          outputText,
          normalizedOutput,
          repairEvidence,
        ),
      ),
    )
}
