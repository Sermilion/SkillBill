package skillbill.engine.featuretask.slot.attempt

import skillbill.application.decomposition.baseBranch
import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProjectionRejection
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.engine.featuretask.phase.core.toMeasurementFailureClassification
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposer
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.phase.prompt.directives.PriorAttemptCorrection
import skillbill.engine.featuretask.runloop.core.DeclaredLaunchArgs
import skillbill.engine.featuretask.runloop.core.LaunchMeasurementContextReady
import skillbill.engine.featuretask.runloop.core.LaunchPreparation
import skillbill.engine.featuretask.runloop.core.LaunchPreparationRejected
import skillbill.engine.featuretask.runloop.core.LaunchPreparationRejectedArgs
import skillbill.engine.featuretask.runloop.core.LaunchRejectionMeasurementContext
import skillbill.engine.featuretask.runloop.core.LaunchSeamRejectionArgs
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PreparedLaunch
import skillbill.engine.featuretask.runloop.core.PreparedLaunchReady
import skillbill.engine.featuretask.runloop.core.RepositoryCheckpointResolutionArgs
import skillbill.engine.featuretask.runloop.core.resolveLaunchRejectionAttribution
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopOutputVerification
import skillbill.engine.featuretask.runloop.settlement.FeatureTaskRuntimeRunLoopValidationScope
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runner.LaunchResult
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.model.audit.QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

object PhaseLaunchPreparation {
  internal fun prepareLaunchForCapture(
    context: PhaseAttemptEnvironment,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    iteration: Int?,
    priorCorrection: PriorAttemptCorrection?,
    prompt: PhaseStepPromptSource,
  ): LaunchPreparation {
    with(context) {
      val measurementContext =
        when (
          val resolution = PhaseLaunchPreparation.resolveLaunchMeasurementContext(context, run)
        ) {
          is LaunchMeasurementContextReady -> resolution.value
          is LaunchPreparationRejected -> return resolution
          is PreparedLaunchReady -> error("Unexpected launch measurement result.")
        }
      return PhaseLaunchPreparation.prepareDeclaredLaunch(
        context,
        DeclaredLaunchArgs(
          run,
          state,
          iteration,
          priorCorrection,
          measurementContext,
          prompt,
        ),
      )
    }
  }

  internal fun resolveLaunchMeasurementContext(
    context: PhaseAttemptEnvironment,
    run: PhaseRun,
  ): LaunchPreparation {
    with(context) {
      val producerIteration =
        run.declaration.projectionDeclarations
          .map { declaration ->
            val phaseId = declaration.producerIteration.phaseId
            state.outputFor(phaseId)?.let { FeatureTaskRuntimeProducerIteration(phaseId, it.iteration) }
              ?: declaration.producerIteration
          }
          .maxByOrNull(FeatureTaskRuntimeProducerIteration::iteration)
          ?: FeatureTaskRuntimeProducerIteration(run.phaseId, 1)
      return try {
        LaunchMeasurementContextReady(
          LaunchRejectionMeasurementContext(
            producerIteration = producerIteration,
            repositoryCheckpoint =
              with(FeatureTaskRuntimeRunLoopOutputVerification) {
                resolveRepositoryCheckpoint(
                  RepositoryCheckpointResolutionArgs(
                    recorder = recorder,
                    goalContinuationRecorder = goalContinuationRecorder,
                    phaseGates = phaseGates,
                    session = session,
                    run = run,
                  ),
                )
              },
          ),
        )
      } catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError) {
        recordLaunchSeamRejection(
          recorder,
          LaunchSeamRejectionArgs(
            run = run,
            state = state,
            classification = FeatureTaskRuntimeProjectionFailureClassification.BUDGET_OVERFLOW,
            sourceLabel = error.projectionName,
            fallbackProducerIteration = producerIteration,
            repositoryCheckpoint = null,
          ),
        )
        LaunchPreparationRejected(
          LaunchResult.projectionRejected(
            "Feature-task-runtime phase '${run.phaseId}' could not resolve its repository checkpoint: ${error.message}",
          ),
        )
      }
    }
  }

  internal fun prepareDeclaredLaunch(
    context: PhaseAttemptEnvironment,
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
        currentProducerIteration = { phaseId -> state.outputFor(phaseId)?.iteration },
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
    context: PhaseAttemptEnvironment,
    args: DeclaredLaunchArgs,
  ): LaunchPreparation {
    with(context) {
      val run = args.run
      val state = args.state
      val priorCorrection = args.priorCorrection
      val measurementContext = args.context
      return try {
        PreparedLaunchReady(
          PhaseLaunchPreparation.prepareLaunch(
            context,
            run = run,
            iteration = args.iteration,
            priorCorrection = priorCorrection,
            repositoryCheckpoint = measurementContext.repositoryCheckpoint,
            prompt = args.prompt,
          ),
        )
      } catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError) {
        rejectedHandoffLaunch(recorder, run, state, error, measurementContext)
      } catch (error: InvalidFeatureTaskRuntimePlanningProjectionSchemaError) {
        rejectedPlanningProjectionLaunch(recorder, run, state, error, measurementContext)
      } catch (error: InvalidWorkflowStateSchemaError) {
        rejectedDurableBriefingLaunch(recorder, run, state, error, measurementContext)
      }
    }
  }

  private fun rejectedHandoffLaunch(
    recorder: PhaseRunRecords,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    error: InvalidFeatureTaskRuntimeHandoffProjectionError,
    context: LaunchRejectionMeasurementContext,
  ): LaunchPreparationRejected =
    launchPreparationRejected(
      recorder,
      LaunchPreparationRejectedArgs(
        run = run,
        state = state,
        classification = error.failureKind.toMeasurementFailureClassification(),
        sourceLabel = error.projectionName,
        measurement = context,
        message =
          "Feature-task-runtime phase '${run.phaseId}' could not build its declared handoff " +
            "projection: ${error.message}",
      ),
    )

  private fun rejectedPlanningProjectionLaunch(
    recorder: PhaseRunRecords,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    error: InvalidFeatureTaskRuntimePlanningProjectionSchemaError,
    context: LaunchRejectionMeasurementContext,
  ): LaunchPreparationRejected {
    PhaseLaunchPreparation.recordLaunchSeamRejection(
      recorder,
      LaunchSeamRejectionArgs(
        run = run,
        state = state,
        classification = FeatureTaskRuntimeProjectionFailureClassification.INVALID_CONTRACT,
        sourceLabel = error.projectionName ?: "planning_projection",
        fallbackProducerIteration = context.producerIteration,
        repositoryCheckpoint = context.repositoryCheckpoint,
      ),
    )
    return LaunchPreparationRejected(
      LaunchResult.recordRejected(
        QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION,
        error.message.orEmpty(),
      ),
    )
  }

  private fun rejectedDurableBriefingLaunch(
    recorder: PhaseRunRecords,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    error: InvalidWorkflowStateSchemaError,
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
    context: PhaseAttemptEnvironment,
    run: PhaseRun,
    iteration: Int?,
    priorCorrection: PriorAttemptCorrection?,
    repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
    prompt: PhaseStepPromptSource,
  ): PreparedLaunch {
    with(context) {
      val resolvedBranchRecord = recorder.loadResolvedBranch(run.request.workflowId)
      val handoff =
        assembleLaunchHandoff(
          context,
          run,
          repositoryCheckpoint,
          resolvedBranchRecord,
        )
      recorder.validateHandoffDeclarations(handoff.projectionDeclarations)
      val sharedEvidence =
        FeatureTaskRuntimeRunLoopOutputVerification.resolveSharedReviewEvidence(
          phaseGates,
          run,
          repositoryCheckpoint,
        )
      val briefing =
        FeatureTaskRuntimePhaseBriefingAssembler.assemble(
          handoff,
          run.request.workflowId,
          phaseGates.planningProjectionValidator,
          run.request.agentAddonSelection,
          sharedEvidence?.reference,
          strategyFor(run.phaseId).briefingInvariantFields(run.phaseId),
        )
      if (!run.policy.singleAgentSession) {
        recorder.recordPhaseBriefing(
          run.request.workflowId,
          briefing,
          sharedEvidence?.measurement,
        )
      }
      val inputs =
        PhaseLaunchPreparation
          .composeLaunchPromptInputs(context, run, handoff, priorCorrection, briefing)
          .copy(
            phaseSettlement = iteration?.let(runState::settlementTarget),
          )
      return PreparedLaunch(briefing, PhaseLaunchPreparation.composeLaunchPrompt(context, run, inputs, prompt))
    }
  }

  private fun assembleLaunchHandoff(
    context: PhaseAttemptEnvironment,
    run: PhaseRun,
    repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
    resolvedBranchRecord: FeatureTaskRuntimeResolvedBranch?,
  ) = with(context) {
    FeatureTaskRuntimeHandoffContract.assembleHandoff(
      FeatureTaskRuntimeHandoffAssemblyRequest(
        declaration = run.declaration,
        runInvariants = run.request.runInvariants,
        recordedOutputs = state.outputs(run.declaration.consumedUpstreamPhaseIds),
        drivingVerdict = run.reentry?.drivingVerdict,
        repairLedger = null,
        repositoryCheckpoint = repositoryCheckpoint,
        expectedRepositoryCheckpoint =
          stepHooks(run).expectedLaunchCheckpoint(run, repositoryCheckpoint?.fingerprint)
            ?.let(::FeatureTaskRuntimeRepositoryCheckpoint),
        branchIdentity = resolvedBranchRecord?.branch,
        baseBranch = resolvedBranchRecord?.baseBranch ?: "main",
        validationDepth = run.request.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
        unselectedStepIds = unselectedStepIds(),
      ),
    ).copy(
      recordedFindingVerdicts = stepHooks(run).handoffFindingVerdicts(stepState(run)),
    )
  }

  private fun composeLaunchPrompt(
    context: PhaseAttemptEnvironment,
    run: PhaseRun,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
    prompt: PhaseStepPromptSource,
  ): String =
    FeatureTaskRuntimePhasePromptComposer.compose(inputs, prompt) +
      context.stepHooks(run).launchPromptSupplement(run, context, context.stepState(run))

  private fun composeLaunchPromptInputs(
    context: PhaseAttemptEnvironment,
    run: PhaseRun,
    handoff: FeatureTaskRuntimePhaseHandoff,
    priorCorrection: PriorAttemptCorrection?,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  ): FeatureTaskRuntimePhasePromptComposeInputs {
    with(context) {
      val context = this
      val resolvedBranchRecord = recorder.loadResolvedBranch(run.request.workflowId)
      val (passNumber, depthResolution, executedTier) = stepHooks(run).launchReviewTier(run, stepState(run))
      return FeatureTaskRuntimePhasePromptComposeInputs(
        issueKey = run.request.issueKey,
        briefing = briefing,
        suppressDecomposition = isGoalContinuationRun(run.request),
        codeReviewMode = executedTier,
        reviewPassNumber = passNumber,
        goalSubtaskReviewInput = run.goalReviewInput,
        baselineUntrackedPaths = resolvedBranchRecord?.baselineUntrackedPaths.orEmpty(),
        resolvedReviewTier = depthResolution?.let { executedTier },
        reviewDecidingRule = depthResolution?.decidingRule,
        repairLedger = handoff.repairLedger,
        priorReviewContext = null,
        priorSchemaFailure = priorCorrection?.schemaGateReason,
        priorTerminalFailure = priorCorrection?.retryableTerminalReason,
        priorFindingCoverage = priorCorrection?.findingCoverageReason,
        correctiveRepairContext = priorCorrection?.correctiveRepairContext,
        operatorBlockRetry =
          session.operatorBlockRetry
            ?.takeIf { it.phaseId == run.phaseId && !session.operatorBlockRetryCompleted },
        implementationContinuation =
          FeatureTaskRuntimeRunLoopOutputVerification.implementationContinuationFor(recorder, run),
        validationGateFindings = run.validationGateFindings,
        validationGateTriagePlan = run.validationGateTriagePlan,
        validationGateRepair = run.validationGateRepair,
        validationGateTriage = run.validationGateTriage,
        agentRunValidateFallback = run.agentRunValidateFallback,
        packBuildCommand =
          if (stepHooks(run).carriesPackBuildCommand) {
            FeatureTaskRuntimeRunLoopValidationScope.packBuildCommand(
              phaseGates,
              recorder,
              goalContinuationRecorder,
              session,
              run,
            )
          } else {
            null
          },
        auditRetryFocusHint =
          session.auditRetryFocusHint?.takeIf {
            run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
          },
        mutating = run.policy.mutating,
        singleAgentSession = run.policy.singleAgentSession,
      )
    }
  }
}
