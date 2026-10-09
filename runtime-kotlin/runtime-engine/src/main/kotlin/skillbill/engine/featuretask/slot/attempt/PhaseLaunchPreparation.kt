package skillbill.engine.featuretask.slot.attempt

import skillbill.application.decomposition.baseBranch
import skillbill.application.review.service.RuntimeOwnedReviewMode
import skillbill.engine.featuretask.lifecycle.branch.baseBranchOrDefault
import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProjectionRejection
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeSharedReviewEvidenceResolved
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeBriefingScope
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimePhaseBriefingAssemblyResult
import skillbill.engine.featuretask.phase.core.auditProseValue
import skillbill.engine.featuretask.phase.core.toMeasurementFailureClassification
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposer
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.phase.prompt.directives.PriorAttemptCorrection
import skillbill.engine.featuretask.runloop.attempt.FeatureTaskRuntimeRunLoopHookViews.launchHookContext
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
import skillbill.engine.featuretask.runloop.core.DeclaredLaunchArgs
import skillbill.engine.featuretask.runloop.core.LaunchMeasurementContextReady
import skillbill.engine.featuretask.runloop.core.LaunchPreparation
import skillbill.engine.featuretask.runloop.core.LaunchPreparationRejected
import skillbill.engine.featuretask.runloop.core.LaunchPreparationRejectedArgs
import skillbill.engine.featuretask.runloop.core.LaunchRejectionMeasurementContext
import skillbill.engine.featuretask.runloop.core.LaunchRequiredWriteRejected
import skillbill.engine.featuretask.runloop.core.LaunchSeamRejectionArgs
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PreparedLaunch
import skillbill.engine.featuretask.runloop.core.PreparedLaunchReady
import skillbill.engine.featuretask.runloop.core.RepositoryCheckpointResolutionArgs
import skillbill.engine.featuretask.runloop.core.resolveLaunchRejectionAttribution
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopOutputVerification
import skillbill.engine.featuretask.runloop.settlement.FeatureTaskRuntimeRunLoopValidationScope
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runner.LaunchResult
import skillbill.engine.featuretask.slot.PhaseLaunchReviewTier
import skillbill.engine.featuretask.slot.audit.claim.NoChangeClaimResolution
import skillbill.engine.featuretask.slot.state.PhaseImplementFixStepBinding
import skillbill.engine.featuretask.slot.state.PhaseReviewPassState
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseStepBinding
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.error.shellcontent.invalidFeatureTaskRuntimeHandoffProjection
import skillbill.error.shellcontent.isInvalidWorkflowStateFailure
import skillbill.ports.taskruntime.model.implementationChecklistRelativePath
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfile
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

object PhaseLaunchPreparation {
  internal fun PhaseAttemptLaunchPreparationContext.prepareLaunchForCapture(
    run: PhaseRun,
    iteration: Int?,
    priorCorrection: PriorAttemptCorrection?,
    prompt: PhaseStepPromptSource,
    boundStep: PhaseStepBinding,
  ): LaunchPreparation {
    val context = this
    with(context) {
      val measurementContext =
        when (
          val resolution = PhaseLaunchPreparation.resolveLaunchMeasurementContext(context, run)
        ) {
          is LaunchMeasurementContextReady -> resolution.value
          is LaunchPreparationRejected -> return resolution
          is PreparedLaunchReady, is LaunchRequiredWriteRejected ->
            error("Unexpected launch measurement result.")
        }
      return PhaseLaunchPreparation.prepareDeclaredLaunch(
        context,
        DeclaredLaunchArgs(
          run,
          settlementCoupling().progress,
          iteration,
          priorCorrection,
          measurementContext,
          prompt,
          boundStep,
        ),
      )
    }
  }

  internal fun resolveLaunchMeasurementContext(
    context: PhaseAttemptLaunchPreparationContext,
    run: PhaseRun,
  ): LaunchPreparation {
    with(context) {
      val producerIteration =
        run.declaration.projectionDeclarations
          .map { declaration ->
            val phaseId = declaration.producerIteration.phaseId
            progress.phase(phaseId).output?.let { FeatureTaskRuntimeProducerIteration(phaseId, it.iteration) }
              ?: declaration.producerIteration
          }.maxByOrNull(FeatureTaskRuntimeProducerIteration::iteration)
          ?: FeatureTaskRuntimeProducerIteration(run.phaseId, 1)
      return LaunchMeasurementContextReady(
        LaunchRejectionMeasurementContext(
          producerIteration = producerIteration,
          repositoryCheckpoint =
            with(FeatureTaskRuntimeRunLoopOutputVerification) {
              resolveRepositoryCheckpoint(
                RepositoryCheckpointResolutionArgs(
                  recorder = recorder,
                  goalContinuationRecorder = goalContinuationRecorder,
                  gitOperations = gitOperations,
                  qualityGateCycles = qualityGateCycles,
                  coupledRunTransitions = coupledRunTransitions,
                  session = session,
                  run = run,
                  diagnostics = diagnostics,
                  extendsOwnedInventory = context::extendsOwnedInventory,
                ),
              )
            },
        ),
      )
    }
  }

  internal fun prepareDeclaredLaunch(
    context: PhaseAttemptLaunchPreparationContext,
    args: DeclaredLaunchArgs,
  ): LaunchPreparation = PhaseLaunchPreparation.prepareDeclaredLaunchBody(context, args)

  internal fun recordLaunchSeamRejection(
    recorder: PhaseRunRecords,
    args: LaunchSeamRejectionArgs,
  ) {
    val run = args.run
    val state = args.state
    val classification = args.classification
    val sourceLabel = args.sourceLabel
    val fallbackProducerIteration = args.fallbackProducerIteration
    val repositoryCheckpoint = args.repositoryCheckpoint
    val attribution =
      resolveLaunchRejectionAttribution(
        declarations = run.declaration.projectionDeclarations,
        projectionName = sourceLabel,
        currentProducerIteration = { phaseId -> state.phase(phaseId).output?.iteration },
        fallbackProducerIteration = fallbackProducerIteration,
      )
    recorder.recordProjectionRejection(
      FeatureTaskRuntimeProjectionRejection(
        workflowId = run.request.workflowId,
        consumerPhaseId = run.phaseId,
        projectionContractId = attribution.projectionContractId,
        producerIteration = attribution.producerIteration,
        repositoryCheckpointFingerprint = repositoryCheckpoint?.fingerprint,
        failureClassification = classification,
        sourceLabel = sourceLabel,
      ),
    )
  }

  internal fun launchPreparationRejected(
    recorder: PhaseRunRecords,
    args: LaunchPreparationRejectedArgs,
  ): LaunchPreparationRejected {
    PhaseLaunchPreparation.recordLaunchSeamRejection(
      recorder,
      LaunchSeamRejectionArgs(
        run = args.run,
        state = args.state,
        classification = args.classification,
        sourceLabel = args.sourceLabel,
        fallbackProducerIteration = args.measurement.producerIteration,
        repositoryCheckpoint = args.measurement.repositoryCheckpoint,
      ),
    )
    return LaunchPreparationRejected(LaunchResult.projectionRejected(args.message))
  }

  internal fun prepareDeclaredLaunchBody(
    context: PhaseAttemptLaunchPreparationContext,
    args: DeclaredLaunchArgs,
  ): LaunchPreparation {
    with(context) {
      val run = args.run
      val state = args.state
      val priorCorrection = args.priorCorrection
      val measurementContext = args.context
      return try {
        PhaseLaunchPreparation.prepareLaunch(context, args)
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.isInvalidWorkflowStateFailure())
        rejectedDurableBriefingLaunch(recorder, run, state, error, measurementContext)
      }
    }
  }

  private fun rejectedHandoffLaunch(
    recorder: PhaseRunRecords,
    run: PhaseRun,
    state: FeatureTaskRuntimeProgressSnapshotAccess,
    rejection: InvalidFeatureTaskRuntimeHandoffProjectionContext,
    measurementContext: LaunchRejectionMeasurementContext,
  ): LaunchPreparationRejected =
    launchPreparationRejected(
      recorder,
      LaunchPreparationRejectedArgs(
        run = run,
        state = state,
        classification = rejection.failureKind.toMeasurementFailureClassification(),
        sourceLabel = rejection.projectionName,
        measurement = measurementContext,
        message =
          "Feature-task-runtime phase '${run.phaseId}' could not build its declared handoff " +
            "projection: ${invalidFeatureTaskRuntimeHandoffProjection(rejection).message}",
      ),
    )

  private fun rejectedDurableBriefingLaunch(
    recorder: PhaseRunRecords,
    run: PhaseRun,
    state: FeatureTaskRuntimeProgressSnapshotAccess,
    error: SkillBillRuntimeException,
    context: LaunchRejectionMeasurementContext,
  ): LaunchPreparationRejected =
    launchPreparationRejected(
      recorder,
      LaunchPreparationRejectedArgs(
        run = run,
        state = state,
        classification = FeatureTaskRuntimeProjectionFailureClassification.UNSUPPORTED_VERSION,
        sourceLabel = "durable_briefing",
        measurement = context,
        message =
          "Feature-task-runtime phase '${run.phaseId}' rejected a durable handoff envelope at " +
            "the launch seam: ${error.message}",
      ),
    )

  internal fun prepareLaunch(
    context: PhaseAttemptLaunchPreparationContext,
    args: DeclaredLaunchArgs,
  ): LaunchPreparation {
    val run = args.run
    val state = args.state
    val priorCorrection = args.priorCorrection
    val measurementContext = args.context
    val repositoryCheckpoint = args.context.repositoryCheckpoint
    val prompt = args.prompt
    with(context) {
      val resolvedBranchRecord = recorder.loadResolvedBranch(run.request.workflowId)
      val handoff =
        assembleLaunchHandoff(
          context,
          run,
          repositoryCheckpoint,
          resolvedBranchRecord,
          args.boundStep,
        )
      recorder.validateHandoffDeclarations(handoff.projectionDeclarations)
      val sharedEvidence =
        FeatureTaskRuntimeRunLoopOutputVerification.resolveSharedReviewEvidence(
          sharedEvidenceResolver,
          diffResolver,
          run,
          repositoryCheckpoint,
        )
      val assembly =
        FeatureTaskRuntimePhaseBriefingAssembler.assemble(
          handoff,
          run.request.workflowId,
          run.request.agentAddonSelection,
          FeatureTaskRuntimeBriefingScope(
            sharedEvidence?.reference,
            briefingInvariantFields(run.phaseId),
          ),
        )
      if (assembly is FeatureTaskRuntimePhaseBriefingAssemblyResult.Rejected) {
        return PhaseLaunchPreparation.rejectedHandoffLaunch(
          recorder,
          run,
          state,
          assembly.context,
          measurementContext,
        )
      }
      val briefing = (assembly as FeatureTaskRuntimePhaseBriefingAssemblyResult.Accepted).briefing
      recordLaunchBriefing(context, args, briefing, sharedEvidence)?.let { return it }
      val inputs =
        PhaseLaunchPreparation
          .run { context.composeLaunchPromptInputs(run, handoff, priorCorrection, briefing, args.boundStep) }
          .copy(
            phaseSettlement = args.iteration?.let(::phaseSettlementTarget),
          )
      return PreparedLaunchReady(
        PreparedLaunch(
          briefing,
          PhaseLaunchPreparation.composeLaunchPrompt(context, run, inputs, prompt, args.boundStep),
        ),
      )
    }
  }

  private fun recordLaunchBriefing(
    context: PhaseAttemptLaunchPreparationContext,
    args: DeclaredLaunchArgs,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    sharedEvidence: FeatureTaskRuntimeSharedReviewEvidenceResolved?,
  ): LaunchPreparation? {
    if (args.run.policy.singleAgentSession) return null
    return when (
      val write =
        context.recorder.recordPhaseBriefing(
          args.run.request.workflowId,
          briefing,
          sharedEvidence?.measurement,
          args.iteration ?: 1,
        )
    ) {
      is RequiredPhaseWrite.Rejected ->
        write.handoffRejection?.let {
          rejectedHandoffLaunch(context.recorder, args.run, args.state, it, args.context)
        } ?: LaunchRequiredWriteRejected(write)
      RequiredPhaseWrite.Acknowledged -> null
    }
  }

  private fun assembleLaunchHandoff(
    context: PhaseAttemptLaunchPreparationContext,
    run: PhaseRun,
    repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
    resolvedBranchRecord: FeatureTaskRuntimeResolvedBranch?,
    boundStep: PhaseStepBinding,
  ) = with(context) {
    FeatureTaskRuntimeHandoffContract
      .assembleHandoff(
        FeatureTaskRuntimeHandoffAssemblyRequest(
          declaration = run.declaration,
          runInvariants = run.request.runInvariants,
          recordedOutputs = progress.outputs(run.declaration.consumedUpstreamPhaseIds),
          drivingVerdict = run.reentry?.drivingVerdict,
          repairLedger = null,
          repositoryCheckpoint = repositoryCheckpoint,
          expectedRepositoryCheckpoint =
            stepHooks(run)
              .expectedLaunchCheckpoint(run, repositoryCheckpoint?.fingerprint)
              ?.let(::FeatureTaskRuntimeRepositoryCheckpoint),
          branchIdentity = resolvedBranchRecord?.branch,
          baseBranch = gitOperations.baseBranchOrDefault(run.request.repoRoot, resolvedBranchRecord?.baseBranch),
          validationDepth = run.request.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
          unselectedStepIds = unselectedStepIds(),
        ),
      ).copy(
        recordedFindingVerdicts =
          (boundStep as? PhaseImplementFixStepBinding)?.let { stepHooks(run).handoffFindingVerdicts(it) }
            ?: emptyList(),
      )
  }

  private fun composeLaunchPrompt(
    context: PhaseAttemptLaunchPreparationContext,
    run: PhaseRun,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
    prompt: PhaseStepPromptSource,
    boundStep: PhaseStepBinding,
  ): String =
    FeatureTaskRuntimePhasePromptComposer.compose(inputs, prompt) +
      context.stepHooks(
        run,
      ).launchPromptSupplement(run, context.launchHookContext(run, context.stepHooks(run)), boundStep)

  private fun PhaseAttemptLaunchPreparationContext.composeLaunchPromptInputs(
    run: PhaseRun,
    handoff: FeatureTaskRuntimePhaseHandoff,
    priorCorrection: PriorAttemptCorrection?,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    boundStep: PhaseStepBinding,
  ): FeatureTaskRuntimePhasePromptComposeInputs {
    with(this) {
      val context = this
      val resolvedBranchRecord = recorder.loadResolvedBranch(run.request.workflowId)
      val launchReviewTier =
        (boundStep as? PhaseReviewPassState)?.let { stepHooks(run).launchReviewTier(run, it) }
          ?: PhaseLaunchReviewTier(
            passNumber = null,
            resolution = null,
            executedTier = RuntimeOwnedReviewMode.execute(run.request.runInvariants.codeReviewMode),
          )
      val checkpointArgs =
        RepositoryCheckpointResolutionArgs(
          recorder,
          goalContinuationRecorder,
          gitOperations,
          qualityGateCycles,
          coupledRunTransitions,
          session,
          run,
          diagnostics,
          this::extendsOwnedInventory,
        )
      val implementationContinuation =
        FeatureTaskRuntimeRunLoopOutputVerification.implementationContinuationFor(recorder, run)
      ImplementationChecklistLaunchPreparation.prepare(this, run, implementationContinuation)
      return FeatureTaskRuntimePhasePromptComposeInputs(
        issueKey = run.request.issueKey,
        briefing = briefing,
        suppressDecomposition = isGoalContinuationRun(run.request),
        specBundleRequired = run.request.specBundleRequired,
        specRewritable = run.request.protectedSpecSha256 == null,
        codeReviewMode = launchReviewTier.executedTier,
        reviewPassNumber = launchReviewTier.passNumber,
        goalSubtaskReviewInput = run.goalReviewInput,
        baselineUntrackedPaths = resolvedBranchRecord?.baselineUntrackedPaths.orEmpty(),
        resolvedReviewTier = launchReviewTier.resolution?.let { launchReviewTier.executedTier },
        reviewDecidingRule = launchReviewTier.resolution?.decidingRule,
        repairLedger = handoff.repairLedger,
        priorReviewContext = null,
        priorTerminalFailure = priorCorrection?.retryableTerminalReason,
        priorFindingCoverage = priorCorrection?.findingCoverageReason,
        priorAcceptanceAudit =
          auditProseValue(
            progress.phase(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
              .output?.normalizedOutput?.envelopeWireMap(),
          ),
        noChangeClaim = auditNoChangeClaim(run),
        operatorBlockRetry =
          session.operatorBlockRetry
            ?.takeIf { it.phaseId == run.phaseId && !session.operatorBlockRetryCompleted },
        implementationContinuation = implementationContinuation,
        validationGateFindings = run.validationGateFindings,
        validationGateTriagePlan = run.validationGateTriagePlan,
        validationGateRepair = run.validationGateRepair,
        validationGateTriage = run.validationGateTriage,
        agentRunValidateFallback = run.agentRunValidateFallback,
        packBuildCommand = packCommand(run, checkpointArgs, carriesBuild = true),
        packCollectAllCommand = packCommand(run, checkpointArgs, carriesBuild = false),
        mutating = run.policy.mutating,
        singleAgentSession = run.policy.singleAgentSession,
        repoRoot = run.request.repoRoot,
        stepProfile = run.launchAssignment?.profile ?: PhaseModelProfile.CANONICAL,
        implementationChecklistAddress =
          implementationChecklistRelativePath(run.request.workflowId)
            .takeIf { run.request.workflowId.isNotBlank() },
      )
    }
  }

  private fun PhaseAttemptLaunchPreparationContext.auditNoChangeClaim(run: PhaseRun): String? =
    NoChangeClaimResolution.activeClaimMap(progress)
      ?.takeIf { run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT }
      ?.let(NoChangeClaimResolution::renderForAudit)

  private fun PhaseAttemptLaunchPreparationContext.packCommand(
    run: PhaseRun,
    checkpointArgs: RepositoryCheckpointResolutionArgs,
    carriesBuild: Boolean,
  ): String? {
    val hooks = stepHooks(run)
    return when {
      carriesBuild && hooks.carriesPackBuildCommand ->
        FeatureTaskRuntimeRunLoopValidationScope.packBuildCommand(checkpointArgs)
      !carriesBuild && hooks.carriesPackValidationCommand ->
        FeatureTaskRuntimeRunLoopValidationScope.packCollectAllCommand(checkpointArgs)
      else -> null
    }
  }
}
