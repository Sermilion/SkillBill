package skillbill.engine.featuretask.slot.codereview.verify

import skillbill.application.review.spec.toProjectionPayload
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.engine.featuretask.persist.workflowArtifactEntryMap
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeOutputVerification
import skillbill.engine.featuretask.review.finding.promptSection
import skillbill.engine.featuretask.review.finding.resolvedBodiesPromptSection
import skillbill.engine.featuretask.review.finding.selectionsRequiringBodyDelivery
import skillbill.engine.featuretask.review.finding.validateBoundarySelectionsDelivered
import skillbill.engine.featuretask.review.finding.validateDispositionBoundaryBodies
import skillbill.engine.featuretask.review.finding.validateDispositionBoundaryContext
import skillbill.engine.featuretask.review.finding.validateDispositionBoundaryProvenance
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseFindingEvidenceContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.codereview.reviewSpecPath
import skillbill.engine.featuretask.slot.state.PhaseReviewPassState
import skillbill.engine.featuretask.slot.state.PhaseVerifyFindingsStepBinding
import skillbill.goalrunner.subtaskreview.FeatureTaskRuntimeVerificationSignalKeys
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewStructuredFindingsParse
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.goalrunner.subtaskreview.model.UnaddressedFindingLedgerScope
import skillbill.goalrunner.subtaskreview.verificationBoundaryFindingPaths
import skillbill.ports.repository.toFileLocation
import skillbill.review.context.model.execution.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.execution.SpecIntentResolution
import skillbill.review.context.model.hunk.ReviewContextBudgetPolicy
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.validateDispositionCoverage
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object VerifyFindingsEvidence {
  fun launchSections(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
    state: PhaseVerifyFindingsStepBinding,
  ): String {
    val checkpoint = state.findingVerificationCheckpoint()
    val boundarySelection = state.verificationBoundarySelection()?.takeIf { it.isNotEmpty() }
    val resolution =
      context.findingEvidence().specIntentProjectionResolver.resolve(
        SpecIntentProjectionResolveRequest(
          repoRoot = run.request.repoRoot.toFileLocation(),
          explicitSpecPath = reviewSpecPath(run)?.toFileLocation(),
          branchName = state.resolvedBranchName() ?: "HEAD",
          changedPaths = emptyList(),
          budget = ReviewContextBudgetPolicy.DEFAULT,
        ),
      )
    val boundarySections = boundarySections(run, context.findingEvidence(), state)
    val memory = context.findingEvidence().findingVerificationBoundaryMemory
    return buildString {
      when (resolution) {
        is SpecIntentResolution.Resolved -> {
          appendLine()
          appendLine("## Spec intent projection (verify_findings)")
          appendLine(JsonCodec.mapToJsonString(resolution.projection.toProjectionPayload()))
        }
        is SpecIntentResolution.None -> Unit
      }
      append(memory.promptSection(boundarySections))
      if (boundarySelection != null) {
        append(
          memory.resolvedBodiesPromptSection(
            repoRoot = run.request.repoRoot,
            sections = boundarySections,
            selectionsByFindingId = boundarySelection,
          ),
        )
      }
      if (!checkpoint.isNullOrEmpty()) {
        appendLine()
        appendLine("## Persisted verify_findings checkpoint")
        appendLine(
          "Reuse these in-flight dispositions verbatim unless repository evidence contradicts them; " +
            "do not mint a second verification pass.",
        )
        appendLine(
          checkpoint.joinToString(prefix = "[", postfix = "]") { disposition ->
            JsonCodec.mapToJsonString(workflowArtifactEntryMap(disposition.asWorkflowArtifactEntry()))
          },
        )
      }
    }
  }

  fun retainCheckpoint(
    state: PhaseVerifyFindingsStepBinding,
    outputText: String,
  ) {
    val outputMap =
      JsonCodec
        .parseObjectOrNull(outputText)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?.toWorkflowArtifactMap()
        ?: return
    val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
    if (dispositions.isEmpty()) return
    state.persistFindingVerificationCheckpoint(dispositions)
  }

  fun boundaryBodyDelivery(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseVerifyFindingsStepBinding,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck {
    val dispositions = coveredDispositions(state, outputMap) ?: return PhaseStepOutputCheck.Accept
    val sections = boundarySections(run, context.findingEvidence(), state)
    val memory = context.findingEvidence().findingVerificationBoundaryMemory
    val invalid =
      memory.validateDispositionBoundaryContext(sections, dispositions)
        ?: memory.validateDispositionBoundaryProvenance(sections, dispositions)
    if (invalid != null) return PhaseStepOutputCheck.Reject(invalid)
    val selections = memory.selectionsRequiringBodyDelivery(sections, dispositions)
    val delivered = selections.isEmpty() || state.verificationBoundarySelection() != null
    if (delivered) return PhaseStepOutputCheck.Accept
    state.persistVerificationBoundarySelection(selections)
    state.persistFindingVerificationCheckpoint(dispositions)
    return PhaseStepOutputCheck.Redeliver(
      "Selected boundary headings recorded; re-read the briefing with resolved entry bodies and re-emit " +
        "finding_dispositions before verify_findings can settle.",
    )
  }

  fun completionRejection(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseVerifyFindingsStepBinding,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? =
    boundaryDispositionGate(run, context, state, outputMap)
      ?: findingVerificationDisposition(outputMap, reviewFindingIds(state))

  fun recordRejectedFindings(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseVerifyFindingsStepBinding,
    verifyOutput: FeatureTaskRuntimeWorkflowArtifactMap,
  ) {
    if (!isGoalContinuationRun(run.request)) return
    val continuation = run.request.goalContinuation ?: return
    val reviewOutput = state.completedStepEnvelope(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) ?: return
    val passNumber =
      (state as? PhaseReviewPassState)
        ?.completedReviewPassCount()
        ?.takeIf { it > 0 }
        ?: 1
    val recordedVerdicts = state.recordedFindingVerdicts(reviewOutput)
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
      RuntimeDiagnosticsBestEffortWarning.record(context.diagnostics, record)
    }
    if (rejectedResult.findings.isEmpty()) return
    state.appendRejectedVerificationFindings(passNumber, rejectedResult.findings)
  }

  private fun boundaryDispositionGate(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseVerifyFindingsStepBinding,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    val dispositions = coveredDispositions(state, outputMap) ?: return null
    val sections = boundarySections(run, context.findingEvidence(), state)
    val memory = context.findingEvidence().findingVerificationBoundaryMemory
    return memory.validateDispositionBoundaryContext(sections, dispositions)
      ?: memory.validateDispositionBoundaryProvenance(sections, dispositions)
      ?: state.verificationBoundarySelection().let { persisted ->
        memory.validateBoundarySelectionsDelivered(sections, dispositions, persisted)
          ?: persisted?.let {
            memory.validateDispositionBoundaryBodies(
              repoRoot = run.request.repoRoot,
              sections = sections,
              dispositions = dispositions,
              persistedSelections = it,
            )
          }
      }
  }

  private fun findingVerificationDisposition(
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
    reviewFindingIds: Set<String>,
  ): String? {
    if (reviewFindingIds.isEmpty()) {
      return null
    }
    val dispositionsKey = FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS
    val dispositionsRaw =
      outputMap[SharedPayloadKeys.PRODUCED_OUTPUTS]
        ?.let(JsonCodec::anyToStringAnyMap)
        ?.get(dispositionsKey) as? List<*>
        ?: return "verify_findings reported 'completed' without produced_outputs.$dispositionsKey."
    return runCatching {
      FeatureTaskRuntimeFindingVerificationDisposition.parseList(
        dispositionsRaw,
        "produced_outputs.$dispositionsKey",
      )
    }.fold(
      onSuccess = { validateDispositionCoverage(it, reviewFindingIds) },
      onFailure = { failure -> failure.message ?: "finding verification dispositions are not contract-safe." },
    )
  }

  private fun coveredDispositions(
    state: PhaseVerifyFindingsStepBinding,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>? {
    val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
    if (dispositions.isEmpty()) return null
    return dispositions.takeIf { validateDispositionCoverage(it, reviewFindingIds(state)) == null }
  }

  private fun boundarySections(
    run: PhaseRun,
    evidence: PhaseFindingEvidenceContext,
    state: PhaseVerifyFindingsStepBinding,
  ): List<FeatureTaskRuntimeFindingBoundaryMemorySection> {
    val reviewOutput = state.completedStepEnvelope(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
    val recordedVerdicts = reviewOutput?.let(state::recordedFindingVerdicts).orEmpty()
    val findings =
      reviewOutput
        ?.let {
          GoalSubtaskReviewStructuredFindingsParse.structuredFindings(it, recordedVerdicts)
        }.orEmpty()
    return evidence.findingVerificationBoundaryMemory.sectionsForFindings(
      run.request.repoRoot,
      findings.mapNotNull { finding ->
        val findingId = finding.findingId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        FeatureTaskRuntimeFindingBoundaryMemoryRequest(
          findingId = findingId,
          findingPaths = GoalSubtaskReviewSummaryReducer.verificationBoundaryFindingPaths(finding),
        )
      },
    )
  }

  private fun reviewFindingIds(state: PhaseVerifyFindingsStepBinding): Set<String> {
    val reviewOutput =
      state.completedStepEnvelope(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) ?: return emptySet()
    val recordedVerdicts = state.recordedFindingVerdicts(reviewOutput)
    return GoalSubtaskReviewStructuredFindingsParse
      .structuredFindings(reviewOutput, recordedVerdicts)
      .mapNotNull { it.findingId }
      .toSet()
  }
}

private fun PhaseAttemptLaunchHookContext.findingEvidence(): PhaseFindingEvidenceContext =
  this as? PhaseFindingEvidenceContext ?: error("Finding evidence requires the accepted verification hook context.")

private fun PhaseStepOutputContext.findingEvidence(): PhaseFindingEvidenceContext =
  this as? PhaseFindingEvidenceContext ?: error("Finding evidence requires the accepted verification output context.")
