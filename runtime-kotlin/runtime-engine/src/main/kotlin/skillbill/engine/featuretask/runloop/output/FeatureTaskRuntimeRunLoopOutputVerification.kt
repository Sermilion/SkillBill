package skillbill.engine.featuretask.runloop.output

import skillbill.application.decomposition.baseBranch
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.checkpoint.goalScopedBaselinePaths
import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.lifecycle.continuation.matches
import skillbill.engine.featuretask.lifecycle.continuation.reviewState
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeVerificationGateReasons
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeImplementationContinuation
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeSharedReviewEvidenceResolved
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeImplementationObligations
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.engine.featuretask.phase.core.featureTaskRuntimeImplementationContinuationFrom
import skillbill.engine.featuretask.phase.planning.producerProjectionGateReason
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeOutputVerification
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeSharedReviewEvidenceResolver
import skillbill.engine.featuretask.review.finding.selectionsRequiringBodyDelivery
import skillbill.engine.featuretask.review.finding.validateBoundarySelectionsDelivered
import skillbill.engine.featuretask.review.finding.validateDispositionBoundaryBodies
import skillbill.engine.featuretask.review.finding.validateDispositionBoundaryContext
import skillbill.engine.featuretask.review.finding.validateDispositionBoundaryProvenance
import skillbill.engine.featuretask.runloop.checkpoint.goalStartBaselinePaths
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.BoundaryBodyDeliveryDecision
import skillbill.engine.featuretask.runloop.core.CheckpointRevisions
import skillbill.engine.featuretask.runloop.core.CompletionProjectionRejectionArgs
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.PersistAcceptedOutputArgs
import skillbill.engine.featuretask.runloop.core.PersistRejectedVerificationFindingsArgs
import skillbill.engine.featuretask.runloop.core.PersistStandardAcceptedOutputArgs
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseReviewPersistenceArgs
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestArgs
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestAttachments
import skillbill.engine.featuretask.runloop.core.PhaseStateWriteArgs
import skillbill.engine.featuretask.runloop.core.RepositoryCheckpointResolutionArgs
import skillbill.engine.featuretask.runloop.core.TerminalOutputAttemptArgs
import skillbill.engine.featuretask.runloop.core.isFeatureSpecPathForIssue
import skillbill.engine.featuretask.runloop.core.qualityGateSelection
import skillbill.engine.featuretask.runloop.core.reconcileCheckpointPathInventory
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.observability.completedEvent
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.runner.boundedSchemaGateDetail
import skillbill.engine.featuretask.runner.mutatingReconciliationGateReason
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.engine.goalrunner.status.completed
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewStructuredFindingsParse
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.goalrunner.subtaskreview.model.StructuredGoalReviewFinding
import skillbill.goalrunner.subtaskreview.model.UnaddressedFindingLedgerScope
import skillbill.goalrunner.subtaskreview.verificationBoundaryFindingPaths
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputSourceLocation
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.validateDispositionCoverage
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

object FeatureTaskRuntimeRunLoopOutputVerification {
  internal fun implementationObligations(run: PhaseRun): FeatureTaskRuntimeImplementationObligations =
    FeatureTaskRuntimeImplementationObligations(
      plannedTaskIds = emptyList(),
      carriedRepairItemIds = emptyList(),
      loopId = run.reentry?.loopId,
      edgeIteration = run.reentry?.edgeIteration,
    )

  internal fun implementationContinuationFor(
    recorder: FeatureTaskRuntimePhaseRecorder,
    run: PhaseRun,
  ): FeatureTaskRuntimeImplementationContinuation? {
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(run.phaseId)) return null
    val attempts =
      recorder.loadImplementationAttempts(run.request.workflowId)
        ?: return null
    return featureTaskRuntimeImplementationContinuationFrom(run.phaseId, attempts, implementationObligations(run))
      ?.takeIf { it.priorValueSegments.isNotEmpty() }
  }

  internal fun completionProjectionRejection(
    context: FeatureTaskRuntimeRunLoopContext,
    args: CompletionProjectionRejectionArgs,
  ): Pair<String, String>? =
    with(context) {
      producerProjectionGateReason(
        args.run.phaseId,
        args.normalizedOutput.envelopeWireMap(),
        phaseGates.planningProjectionValidator,
      )?.let { "producer-projection" to it }
        ?: FeatureTaskRuntimeRunLoopOutputVerification.immediateConsumerProjectionGateReason(
          context = context,
          args = args,
        )?.let { "consumer-projection" to it }
        ?: FeatureTaskRuntimeRunLoopOutputVerification.outputVerificationGateReason(
          state,
          recorder,
          phaseGates,
          args.run,
          args.normalizedOutput.envelopeWireMap(),
        )?.let { "output-verification" to it }
    }

  internal fun firstValidatedOutputRejection(
    phaseId: String,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): Pair<String, String>? =
    mutatingReconciliationGateReason(
      phaseId,
      outputMap,
    )?.let { "mutating-reconciliation" to it }

  internal fun immediateConsumerProjectionGateReason(
    context: FeatureTaskRuntimeRunLoopContext,
    args: CompletionProjectionRejectionArgs,
  ): String? {
    with(context) {
      val run = args.run
      val iteration = args.iteration
      val normalizedOutput = args.normalizedOutput
      val repairEvidence = args.repairEvidence
      val repositoryFingerprint = args.repositoryFingerprint
      if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) return null
      if (run.validationGateFindings != null) return null
      val producerIndex = transitions.forwardPhaseIds.indexOf(run.phaseId)
      if (producerIndex < 0 || producerIndex == transitions.forwardPhaseIds.lastIndex) return null
      val consumerPhaseId = transitions.forwardPhaseIds[producerIndex + 1]
      val declaration =
        phaseDeclaration(
          consumerPhaseId,
          run.request.runInvariants.featureSize,
          qualityGateSelection(request),
        )
      val currentOutput =
        FeatureTaskRuntimePhaseOutput(
          phaseId = run.phaseId,
          iteration = iteration,
          payload = normalizedOutput.canonicalJson,
          normalizedOutput = normalizedOutput,
          repairEvidence = repairEvidence,
        )
      val outputs = state.outputs().filterNot { it.phaseId == run.phaseId } + currentOutput
      val resolvedFingerprint =
        repositoryFingerprint?.takeIf(String::isNotBlank)
          ?: phaseGates.gitOperations.repositoryFingerprint(run.request.repoRoot).value.takeIf(String::isNotBlank)
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
            branchIdentity = session.resolvedBranch,
            baseBranch =
              recorder.loadResolvedBranch(run.request.workflowId)
                ?.baseBranch
                ?: "main",
          ),
        )
      return try {
        FeatureTaskRuntimePhaseBriefingAssembler.assemble(
          handoff,
          run.request.workflowId,
          phaseGates.planningProjectionValidator,
          run.request.agentAddonSelection,
        )
        null
      } catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError) {
        "Phase '${run.phaseId}' reported 'completed' but its output cannot satisfy immediate consumer " +
          "'$consumerPhaseId': ${boundedSchemaGateDetail(error.message.orEmpty())}"
      }
    }
  }

  internal fun recordedFindingVerdictsForFixHandoff(
    recorder: FeatureTaskRuntimePhaseRecorder,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
  ): List<ReviewFindingVerdict> {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX) {
      return emptyList()
    }
    val review = state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) ?: return emptyList()
    val envelope =
      review.normalizedOutput?.envelopeWireMap()
        ?: JsonCodec.parseObjectOrNull(review.payload)
          ?.let { JsonCodec.jsonElementToValue(it) }
          ?.let(JsonCodec::anyToStringAnyMap)
        ?: return emptyList()
    return recorder.recordedFindingVerdicts(envelope)
  }

  internal fun resolveSharedReviewEvidence(
    phaseGates: FeatureTaskRuntimePhaseGates,
    run: PhaseRun,
    checkpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
  ): FeatureTaskRuntimeSharedReviewEvidenceResolved? {
    val declared =
      run.declaration.projectionDeclarations.any {
        it.sourceRef == FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence
      }
    if (!declared) return null
    return FeatureTaskRuntimeSharedReviewEvidenceResolver(
      phaseGates.sharedEvidenceResolver,
      phaseGates.diffResolver,
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

  internal fun completedPhaseRepositoryFingerprint(
    phaseGates: FeatureTaskRuntimePhaseGates,
    run: PhaseRun,
  ) = if (
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
  ) {
    phaseGates.gitOperations.repositoryFingerprint(run.request.repoRoot)
  } else {
    null
  }

  internal fun terminalOutputAttempt(
    request: FeatureTaskRuntimeRunRequest,
    state: FeatureTaskRuntimeRunState,
    recorder: FeatureTaskRuntimePhaseRecorder,
    observability: FeatureTaskRuntimeRunObservability,
    args: TerminalOutputAttemptArgs,
  ): AttemptResult {
    val run = args.run
    val iteration = args.iteration
    val reason = args.reason
    val outputMap = args.normalizedOutput.envelopeWireMap()
    val normalizedOutput = args.normalizedOutput
    val repairEvidence = args.repairEvidence
    val observability = args.observability
    val fileManifest = args.fileManifest
    val disposition = FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(run.phaseId, outputMap)
    val operatorTerminalQualityGate =
      !disposition.retryOnResume &&
        run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
    if (operatorTerminalQualityGate) {
      return AttemptResult.settled(
        FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
          request,
          state,
          recorder,
          observability,
          PhaseBlockRequest(
            run = run,
            attemptCount = iteration,
            reason = reason,
            observability = observability,
            payload = BlockAndPersistPayload(fileManifest = fileManifest),
            failureDisposition = disposition,
          ),
        ),
      )
    }
    return if (
      disposition.retryOnResume &&
      FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(run.phaseId)
    ) {
      AttemptResult.retryableTerminal(reason, fileManifest, disposition)
    } else {
      AttemptResult.settled(
        FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
          request,
          state,
          recorder,
          observability,
          PhaseBlockRequest(
            run = run,
            attemptCount = iteration,
            reason = reason,
            observability = observability,
            payload = BlockAndPersistPayload(fileManifest = fileManifest),
            failureDisposition = disposition,
          ),
        ),
      )
    }
  }

  internal fun outputVerificationGateReason(
    state: FeatureTaskRuntimeRunState,
    recorder: FeatureTaskRuntimePhaseRecorder,
    phaseGates: FeatureTaskRuntimePhaseGates,
    run: PhaseRun,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? =
    findingVerificationBoundaryDispositionGate(
      state,
      recorder,
      phaseGates,
      run,
      outputMap,
    )
      ?: auditRemovedVerdictGate(run.phaseId, outputMap)
      ?: FeatureTaskRuntimeVerificationGateReasons.reviewVerificationSignal(run.phaseId, outputMap)
      ?: FeatureTaskRuntimeVerificationGateReasons.findingVerificationDisposition(
        run.phaseId,
        outputMap,
        FeatureTaskRuntimeRunLoopOutputVerification.reviewFindingIdsForVerification(state, recorder),
      )

  private fun auditRemovedVerdictGate(
    phaseId: String,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
    val wire = (outputMap[SharedPayloadKeys.VERDICT] as? String)?.trim()
    if (wire == FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue) {
      return "Feature-task-runtime verdict '${FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue}' is removed " +
        "(audit phase output); repair gaps in this session and emit satisfied."
    }
    return null
  }

  internal fun findingVerificationBoundarySections(
    state: FeatureTaskRuntimeRunState,
    recorder: FeatureTaskRuntimePhaseRecorder,
    phaseGates: FeatureTaskRuntimePhaseGates,
    run: PhaseRun,
  ): List<FeatureTaskRuntimeFindingBoundaryMemorySection> {
    val reviewOutput =
      state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
        ?.normalizedOutput?.envelopeWireMap()
    val recordedVerdicts =
      reviewOutput?.let {
        recorder.recordedFindingVerdicts(
          it,
        )
      }.orEmpty()
    val findings =
      reviewOutput?.let {
        GoalSubtaskReviewStructuredFindingsParse.structuredFindings(it, recordedVerdicts)
      }.orEmpty()
    return phaseGates.findingVerificationBoundaryMemory.sectionsForFindings(
      run.request.repoRoot,
      findings.mapNotNull { finding ->
        val findingId = finding.findingId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        FeatureTaskRuntimeFindingBoundaryMemoryRequest(
          findingId = findingId,
          findingPaths = findingPathsForBoundaryMemory(finding),
        )
      },
    )
  }

  private fun findingPathsForBoundaryMemory(finding: StructuredGoalReviewFinding): List<String> =
    GoalSubtaskReviewSummaryReducer.verificationBoundaryFindingPaths(finding)

  internal fun findingVerificationBoundaryBodyDeliveryDecision(
    state: FeatureTaskRuntimeRunState,
    recorder: FeatureTaskRuntimePhaseRecorder,
    phaseGates: FeatureTaskRuntimePhaseGates,
    run: PhaseRun,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): BoundaryBodyDeliveryDecision {
    FeatureTaskRuntimeRunLoopOutputVerification.verifyFindingsBoundaryContext(
      state,
      recorder,
      run,
      outputMap,
    )?.let { return it }
    val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
    val sections = findingVerificationBoundarySections(state, recorder, phaseGates, run)
    FeatureTaskRuntimeRunLoopOutputVerification.verifyFindingsBoundaryValidationFailure(
      phaseGates,
      sections,
      dispositions,
    )?.let { return it }
    val selections =
      phaseGates.findingVerificationBoundaryMemory.selectionsRequiringBodyDelivery(
        sections,
        dispositions,
      )
    val delivered =
      if (selections.isEmpty()) {
        true
      } else {
        recorder.loadFindingVerificationBoundarySelection(
          run.request.workflowId,
        ) != null
      }
    if (delivered) return BoundaryBodyDeliveryDecision.NotApplicable
    recorder.persistFindingVerificationBoundarySelection(
      workflowId = run.request.workflowId,
      selections = selections,
    )
    recorder.persistFindingVerificationCheckpoint(
      workflowId = run.request.workflowId,
      dispositions = dispositions,
    )
    return BoundaryBodyDeliveryDecision.ContinueDecision.of(
      "Selected boundary headings recorded; re-read the briefing with resolved entry bodies and re-emit " +
        "finding_dispositions before verify_findings can settle.",
    )
  }

  internal fun findingVerificationBoundaryDispositionGate(
    state: FeatureTaskRuntimeRunState,
    recorder: FeatureTaskRuntimePhaseRecorder,
    phaseGates: FeatureTaskRuntimePhaseGates,
    run: PhaseRun,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? =
    findingVerificationBoundaryDispositionGateImpl(
      state,
      recorder,
      phaseGates,
      run,
      outputMap,
    )

  internal fun findingVerificationBoundaryDispositionGateImpl(
    state: FeatureTaskRuntimeRunState,
    recorder: FeatureTaskRuntimePhaseRecorder,
    phaseGates: FeatureTaskRuntimePhaseGates,
    run: PhaseRun,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    val dispositions =
      FeatureTaskRuntimeRunLoopOutputVerification.verifyFindingsDispositionGateContext(
        state,
        recorder,
        run,
        outputMap,
      ) ?: return null
    val sections = findingVerificationBoundarySections(state, recorder, phaseGates, run)
    FeatureTaskRuntimeRunLoopOutputVerification.verifyFindingsDispositionGateValidationFailure(
      phaseGates,
      sections,
      dispositions,
    )?.let { return it }
    val persisted =
      recorder.loadFindingVerificationBoundarySelection(
        run.request.workflowId,
      )
    val memory = phaseGates.findingVerificationBoundaryMemory
    memory.validateBoundarySelectionsDelivered(sections, dispositions, persisted)?.let { return it }
    return if (persisted != null) {
      memory.validateDispositionBoundaryBodies(
        repoRoot = run.request.repoRoot,
        sections = sections,
        dispositions = dispositions,
        persistedSelections = persisted,
      )
    } else {
      null
    }
  }

  internal fun persistVerifyFindingsCheckpointIfPresent(
    recorder: FeatureTaskRuntimePhaseRecorder,
    run: PhaseRun,
    outputText: String,
  ) {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) return
    val outputMap =
      JsonCodec.parseObjectOrNull(outputText)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?.toWorkflowArtifactMap()
        ?: return
    val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
    if (dispositions.isEmpty()) return
    recorder.persistFindingVerificationCheckpoint(
      workflowId = run.request.workflowId,
      dispositions = dispositions,
    )
  }

  internal fun reviewFindingIdsForVerification(
    state: FeatureTaskRuntimeRunState,
    recorder: FeatureTaskRuntimePhaseRecorder,
  ): Set<String> {
    val reviewOutput =
      state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
        ?.normalizedOutput?.envelopeWireMap()
        ?: return emptySet()
    val recordedVerdicts = recorder.recordedFindingVerdicts(reviewOutput)
    return GoalSubtaskReviewStructuredFindingsParse.structuredFindings(reviewOutput, recordedVerdicts)
      .mapNotNull { it.findingId }
      .toSet()
  }

  internal fun structuralRepairEvidenceFromSchemaError(
    error: InvalidFeatureTaskRuntimePhaseOutputSchemaError,
  ): FeatureTaskRuntimePhaseOutputRepairEvidence? {
    val originalDigest = error.structuralRepairOriginalDigest
    val repairedDigest = error.structuralRepairRepairedDigest
    val format = error.structuralRepairFormat
    val operation = error.structuralRepairOperation
    val sourceLabel = error.structuralRepairSourceLabel
    val sourceOffset = error.structuralRepairSourceOffset
    val sourceLine = error.structuralRepairSourceLine
    val sourceColumn = error.structuralRepairSourceColumn
    if (
      listOf(
        originalDigest,
        repairedDigest,
        format,
        operation,
        sourceLabel,
        sourceOffset,
        sourceLine,
        sourceColumn,
      ).any { it == null }
    ) {
      return null
    }
    return FeatureTaskRuntimePhaseOutputRepairEvidence(
      format =
        FeatureTaskRuntimePhaseOutputFormat.fromWire(
          requireNotNull(format),
        ),
      originalDigest = requireNotNull(originalDigest),
      repairedDigest = requireNotNull(repairedDigest),
      operation =
        FeatureTaskRuntimePhaseOutputRepairOperation.fromWire(
          requireNotNull(operation),
        ),
      sourceLocation =
        FeatureTaskRuntimePhaseOutputSourceLocation(
          sourceLabel = requireNotNull(sourceLabel),
          offset = requireNotNull(sourceOffset),
          line = requireNotNull(sourceLine),
          column = requireNotNull(sourceColumn),
        ),
    )
  }

  internal fun persistAcceptedOutput(
    context: FeatureTaskRuntimeRunLoopContext,
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
      val reviewArgs = PhaseReviewPersistenceArgs(run, iteration, observability, fileManifest)
      if (isGoalReviewRun(run)) {
        with(FeatureTaskRuntimeRunLoopReviewCompletion) {
          ReviewOutputPersistenceContext(
            request = context.request,
            state = context.state,
            recorder = context.recorder,
            observability = observability,
            goalContinuationRecorder = context.goalContinuationRecorder,
          ).persistGoalReviewCompletion(
            reviewArgs,
            normalizedOutput,
            repairEvidence,
          )
        }?.let { outcome ->
          return AttemptResult.settled(outcome)
        }
      } else {
        FeatureTaskRuntimeRunLoopOutputVerification.persistStandardAcceptedOutput(
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
      }
      observability.completedEvent(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
      return completedAttemptResult(run, iteration, outputText, normalizedOutput, repairEvidence)
    }
  }

  internal fun buildRepositoryCheckpoint(
    args: RepositoryCheckpointResolutionArgs,
  ): FeatureTaskRuntimeRepositoryCheckpoint? {
    val run = args.run
    val resolvedBranchRecord = args.recorder.loadResolvedBranch(run.request.workflowId)
    args.session.transitionResolvedBranch(resolvedBranchRecord?.branch)
    val goalReviewState = args.goalContinuationRecorder.reviewState(run.request.workflowId)
    val revisions =
      FeatureTaskRuntimeRunLoopOutputVerification.resolveCheckpointRevisions(
        args.phaseGates,
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
      args.phaseGates.gitOperations.repositoryCheckpointFingerprint(
        run.request.repoRoot,
        revisions.base,
        revisions.head,
        ownedPaths,
      ).takeIf { it is WorkflowGitOperationResult.Ok }?.value?.takeIf(String::isNotBlank) ?: return null
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
        args.phaseGates,
        run,
        baselineOwnedPaths,
      ) ?: return null
    val committedPaths =
      revisions.base?.let { base ->
        (
          args.phaseGates.gitOperations
            .runtimePhaseChangedPathsBetweenCommits(run.request.repoRoot, base, revisions.head)
            as? WorkflowGitNameListResult.Listed
        )
          ?.names
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
    phaseGates: FeatureTaskRuntimePhaseGates,
    run: PhaseRun,
    headRevision: String,
    baseRevision: String?,
  ): CheckpointRevisions? {
    val immutableHead =
      phaseGates.gitOperations.resolveCommit(run.request.repoRoot, headRevision)
        .takeIf { it is WorkflowGitOperationResult.Ok }?.value?.takeIf(String::isNotBlank)
        ?: phaseGates.gitOperations.headCommitSha(run.request.repoRoot)
          .takeIf { it is WorkflowGitOperationResult.Ok }?.value?.takeIf(String::isNotBlank)
        ?: return null
    val immutableBase =
      baseRevision?.let { revision ->
        phaseGates.gitOperations.resolveCommit(run.request.repoRoot, revision)
          .takeIf { it is WorkflowGitOperationResult.Ok }?.value?.takeIf(String::isNotBlank)
          ?: revision.takeIf { it.matches(Regex("^[0-9a-fA-F]{40,64}$")) }
      }
    if (baseRevision != null && immutableBase == null) return null
    return CheckpointRevisions(base = immutableBase, head = immutableHead)
  }

  internal fun checkpointOwnedPaths(
    phaseGates: FeatureTaskRuntimePhaseGates,
    run: PhaseRun,
    baselineOwnedPaths: List<String>,
  ): List<String>? {
    val owned = phaseGates.gitOperations.repositoryOwnedPaths(run.request.repoRoot)
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

  internal fun verifyFindingsBoundaryContext(
    state: FeatureTaskRuntimeRunState,
    recorder: FeatureTaskRuntimePhaseRecorder,
    run: PhaseRun,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): BoundaryBodyDeliveryDecision? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) {
      return BoundaryBodyDeliveryDecision.NotApplicable
    }
    val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
    if (dispositions.isEmpty()) return BoundaryBodyDeliveryDecision.NotApplicable
    if (validateDispositionCoverage(
        dispositions,
        FeatureTaskRuntimeRunLoopOutputVerification.reviewFindingIdsForVerification(state, recorder),
      ) != null
    ) {
      return BoundaryBodyDeliveryDecision.NotApplicable
    }
    return null
  }

  internal fun verifyFindingsBoundaryValidationFailure(
    phaseGates: FeatureTaskRuntimePhaseGates,
    sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): BoundaryBodyDeliveryDecision? {
    val memory = phaseGates.findingVerificationBoundaryMemory
    memory.validateDispositionBoundaryContext(sections, dispositions)?.let {
      return BoundaryBodyDeliveryDecision.RejectDecision.of(it)
    }
    memory.validateDispositionBoundaryProvenance(sections, dispositions)?.let {
      return BoundaryBodyDeliveryDecision.RejectDecision.of(it)
    }
    return null
  }

  internal fun verifyFindingsDispositionGateContext(
    state: FeatureTaskRuntimeRunState,
    recorder: FeatureTaskRuntimePhaseRecorder,
    run: PhaseRun,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) return null
    val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
    if (dispositions.isEmpty()) return null
    if (validateDispositionCoverage(
        dispositions,
        FeatureTaskRuntimeRunLoopOutputVerification.reviewFindingIdsForVerification(state, recorder),
      ) != null
    ) {
      return null
    }
    return dispositions
  }

  internal fun verifyFindingsDispositionGateValidationFailure(
    phaseGates: FeatureTaskRuntimePhaseGates,
    sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): String? {
    val memory = phaseGates.findingVerificationBoundaryMemory
    memory.validateDispositionBoundaryContext(sections, dispositions)?.let { return it }
    memory.validateDispositionBoundaryProvenance(sections, dispositions)?.let { return it }
    return null
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
    context: FeatureTaskRuntimeRunLoopContext,
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
      FeatureTaskRuntimeRunLoopOutputVerification.persistRejectedVerificationFindingsIfNeeded(
        context,
        run,
        normalizedOutput,
      )
      val persisted =
        recorder.recordCompletedPhase(
          FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
            request,
            state,
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
          ),
        )
      if (!persisted) {
        return AttemptResult.settled(
          FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
            request,
            state,
            recorder,
            observability,
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

  private fun persistRejectedVerificationFindingsIfNeeded(
    context: FeatureTaskRuntimeRunLoopContext,
    run: PhaseRun,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ) {
    with(context) {
      if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) return
      persistRejectedVerificationFindings(
        PersistRejectedVerificationFindingsArgs(
          state,
          recorder,
          goalContinuationRecorder,
          diagnostics,
          run,
          normalizedOutput.envelopeWireMap(),
        ),
      )
    }
  }

  private fun persistRejectedVerificationFindings(args: PersistRejectedVerificationFindingsArgs) {
    val state = args.state
    val recorder = args.recorder
    val goalContinuationRecorder = args.goalContinuationRecorder
    val diagnostics = args.diagnostics
    val run = args.run
    val verifyOutput = args.verifyOutput
    if (!isGoalContinuationRun(run.request)) return
    val continuation = run.request.goalContinuation ?: return
    val reviewOutput =
      state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
        ?.normalizedOutput?.envelopeWireMap()
        ?: return
    val reviewState = goalContinuationRecorder.reviewState(run.request.workflowId)
    val passNumber = reviewState?.completedPassCount?.takeIf { it > 0 } ?: 1
    val recordedVerdicts = recorder.recordedFindingVerdicts(reviewOutput)
    val rejectedResult =
      GoalSubtaskReviewSummaryReducer.rejectedVerificationFindings(
        verifyOutput = verifyOutput,
        reviewOutput = reviewOutput,
        scope =
          UnaddressedFindingLedgerScope(
            issueKey = continuation.parentIssueKey,
            subtaskId = continuation.subtaskId,
            workflowId = run.request.workflowId,
            reviewPassNumber = passNumber,
          ),
        recordedVerdicts = recordedVerdicts,
      )
    rejectedResult.truncationRecords.forEach { record ->
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, record)
    }
    if (rejectedResult.findings.isEmpty()) return
    recorder.appendRejectedVerificationFindings(
      workflowId = run.request.workflowId,
      passNumber = passNumber,
      rejected = rejectedResult.findings,
    )
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
