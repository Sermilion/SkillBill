package skillbill.engine.featuretask.slot.audit

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.audit.AcceptanceGapAuditScope
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionInteractionTraceRound
import skillbill.engine.featuretask.phase.core.auditProseValue
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.slot.PhaseCompletedRoundHooks
import skillbill.engine.featuretask.slot.PhaseCompletionRejectionHooks
import skillbill.engine.featuretask.slot.PhaseLaunchPromptHooks
import skillbill.engine.featuretask.slot.PhaseStepHookContextKind
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseAuditOutputContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.audit.claim.NoChangeClaimResolution
import skillbill.engine.featuretask.slot.state.PhaseQaStepBinding
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGapReportParser
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGapStatus
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceGapVerificationResolver
import skillbill.workflow.taskruntime.model.audit.AcceptanceRepairDecision
import skillbill.workflow.taskruntime.model.audit.AcceptanceRepairStep
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangeClaimValidation
import skillbill.workflow.taskruntime.model.audit.auditNeedsClarification
import skillbill.workflow.taskruntime.model.audit.awaitsAuditClarification
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

internal object AcceptanceAuditRound :
  PhaseStepHooks<PhaseQaStepBinding>,
  PhaseLaunchPromptHooks<PhaseQaStepBinding>,
  PhaseCompletionRejectionHooks<PhaseQaStepBinding>,
  PhaseCompletedRoundHooks {
  override val contextKind = PhaseStepHookContextKind.AUDIT

  override fun launchPromptSupplement(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
    state: PhaseQaStepBinding,
  ): String = AcceptanceQaReaudit.promptSupplement(state)

  private const val REPEATED_VERDICTLESS_AUDIT_REASON =
    "runtime: audit completed two rounds in a row without a verdict. The audit agent most likely could not reach " +
      "the sona settlement tools (feature_task_phase_complete). Make the sona MCP server available " +
      "to the agent, then resume the goal."

  private val NO_CHANGE_WORDS: Set<String> =
    setOf(
      FeatureTaskRuntimeVerdict.NO_CHANGE_CONFIRMED.wireValue,
      FeatureTaskRuntimeVerdict.NO_CHANGE_REJECTED.wireValue,
    )

  override fun completionRejection(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseQaStepBinding,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    val repair = (run.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair
    if (repair != null && AcceptanceCriterionInteractionTraceRound.active(repair)) {
      return interactionTraceRejection(run, outputMap)
    }
    val claimed = auditsClaim(context)
    val verdict = outputMap[SharedPayloadKeys.VERDICT] as? String
    acceptanceGapRejection(run, outputMap)?.let { return it }
    return when {
      claimed -> noChangeRoundRejection(context, outputMap)
      verdict in NO_CHANGE_WORDS ->
        "Audit verdict '$verdict' requires a no-change claim on the implementation output."
      else -> null
    }
  }

  private fun acceptanceGapRejection(
    run: PhaseRun,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    val scope = (run.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair ?: return null
    val completed =
      (outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() == WorkflowStepStatus.COMPLETED
    if (run.phaseId != scope.steps?.audit || !completed) return null
    val prose = auditProseValue(outputMap) ?: return null
    val discovery = AcceptanceGapAuditScope.discoveryCriteria(scope) != null
    return when {
      discovery && auditNeedsClarification(outputMap[SharedPayloadKeys.VERDICT] as? String) -> null
      scope.snapshot == null -> initialDiscoveryRejection(scope, prose)
      else -> scopedGapRejection(scope, checkNotNull(scope.snapshot), prose)
    }
  }

  private fun scopedGapRejection(
    scope: AcceptanceCriterionRepairRunState,
    state: AcceptanceCriterionRepairState,
    prose: String,
  ): String? {
    val malformed =
      when {
        state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION ->
          verificationReportRejection(state, prose)
        AcceptanceRepairDecision.auditKind(state) == AcceptanceRepairStep.Discover ->
          discoveryReportRejection(state, prose)
        else -> null
      }
    return malformed?.let { reaskGapOutput(scope, state, it) }
  }

  private fun discoveryReportRejection(
    state: AcceptanceCriterionRepairState,
    prose: String,
  ): String? {
    val open =
      AcceptanceCriterionGapReportParser.openCriteria(prose)
        ?: return "End the discovery report with an Open criteria line naming only the listed criteria."
    val discoveryRefs = AcceptanceRepairDecision.discoveryRefs(state)
    return AcceptanceCriterionGapReportParser.parse(prose, open).malformed
      ?: open.firstOrNull { it !in discoveryRefs }?.let { "Open criteria names $it outside the discovery scope." }
  }

  private fun verificationReportRejection(
    state: AcceptanceCriterionRepairState,
    prose: String,
  ): String? {
    val criterion = state.criteria.singleOrNull { it.canonicalRef == state.currentCriterionRef } ?: return null
    val verification = AcceptanceGapVerificationResolver.resolve(state, criterion, prose, treeMatches = true)
    return verification.missingGapIds.takeIf { it.isNotEmpty() }?.let { missing ->
      val forms =
        missing.joinToString("; ") { gapId ->
          "$gapId: closed @ <path>:<line> or $gapId: open @ <path>:<line>"
        }
      "Missing status lines for ${missing.joinToString()}. Verify is read-only, and closed gaps are settled. " +
        "Report each listed gap using exactly one line: $forms. A line range is allowed. Retry once."
    }
  }

  private fun initialDiscoveryRejection(
    scope: AcceptanceCriterionRepairRunState,
    prose: String,
  ): String? {
    if (!scope.freshDiscoveryRequired()) return null
    val open =
      AcceptanceCriterionGapReportParser.openCriteria(prose)
        ?: return reaskInitialDiscovery(scope, "End discovery with an Open criteria line.")
    val refs = AcceptanceGapAuditScope.discoveryCriteria(scope).orEmpty().map { it.first }.toSet()
    val malformed =
      AcceptanceCriterionGapReportParser.parse(prose, open).malformed
        ?: open.firstOrNull { it !in refs }?.let { "Open criteria names $it outside the authored catalog." }
    return malformed?.let { reaskInitialDiscovery(scope, it) }
  }

  private fun reaskInitialDiscovery(
    scope: AcceptanceCriterionRepairRunState,
    reason: String,
  ): String? = reaskGapOutput(scope, null, "Initial acceptance gap report is malformed. $reason")

  private fun reaskGapOutput(
    scope: AcceptanceCriterionRepairRunState,
    state: AcceptanceCriterionRepairState?,
    reason: String,
  ): String? {
    val key = AcceptanceGapAuditScope.key(scope, state) ?: return null
    if (scope.gapReask?.scopeKey == key) return null
    val verify = state?.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION
    val reask =
      if (verify) {
        reason
      } else {
        "$reason Report every numbered gap using AC-002.G1: <missing production behavior> @ <path>:<line> " +
          "or a line range, then retry once."
      }
    scope.recordGapReask(key, reask)
    return reask
  }

  private fun interactionTraceRejection(
    run: PhaseRun,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    val completed =
      (outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() == WorkflowStepStatus.COMPLETED
    val scope =
      (run.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair
        ?.takeIf { completed && run.phaseId == it.steps?.audit }
        ?: return null
    val prose = auditProseValue(outputMap) ?: return null
    return AcceptanceCriterionInteractionTraceRound.reaskRejection(scope, prose)
  }

  override fun settleCompletedRound(
    context: PhaseStepOutputContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AttemptResult? {
    val auditContext =
      context as? PhaseAuditOutputContext
        ?: error("Audit settlement requires the accepted audit output context.")
    return auditContext.settleAuditRound(
      capture,
      attested,
      outputMap,
      repeatedVerdictlessRejection(capture, outputMap),
    )
  }

  private fun repeatedVerdictlessRejection(
    capture: ValidatedOutputCapture,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    if (!auditNeedsClarification(outputMap[SharedPayloadKeys.VERDICT] as? String)) return null
    val repair = (capture.run.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair?.snapshot
    if (repair?.awaitsAuditClarification() != true) return null
    return REPEATED_VERDICTLESS_AUDIT_REASON
  }

  private fun noChangeRoundRejection(
    context: PhaseStepOutputContext,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
      return null
    }
    if (claimValidation(context) is FeatureTaskRuntimeNoChangeClaimValidation.Rejected) return null
    return when (outputMap[SharedPayloadKeys.VERDICT] as? String) {
      FeatureTaskRuntimeVerdict.NO_CHANGE_CONFIRMED.wireValue -> null
      FeatureTaskRuntimeVerdict.NO_CHANGE_REJECTED.wireValue ->
        if (auditProseValue(outputMap) == null) {
          "Audit reported no_change_rejected without reasons. Give the specific per-criterion reasons in the value."
        } else {
          null
        }
      else -> "Audit on a no-change claim must report verdict no_change_confirmed or no_change_rejected."
    }
  }

  private fun claimValidation(context: PhaseStepOutputContext): FeatureTaskRuntimeNoChangeClaimValidation =
    NoChangeClaimResolution.validate(context.progress, context.request.runInvariants.acceptanceCriteria)

  override fun acceptedOutput(
    context: PhaseStepOutputContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
      return attested
    }
    return if (auditsClaim(context)) {
      noChangeAcceptedOutput(context, attested)
    } else {
      verifyAcceptedOutput(capture, outputMap, attested)
    }
  }

  private fun verifyAcceptedOutput(
    capture: ValidatedOutputCapture,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val repair =
      (capture.run.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair?.snapshot
        ?.takeIf { it.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION }
        ?: return attested
    val criterion = repair.criteria.singleOrNull { it.canonicalRef == repair.currentCriterionRef } ?: return attested
    val open = criterion.gaps.filter { it.status == AcceptanceCriterionGapStatus.OPEN }
    if (open.isEmpty()) return attested
    val prose = auditProseValue(outputMap).orEmpty()
    val verification = AcceptanceGapVerificationResolver.resolve(repair, criterion, prose, treeMatches = true)
    val verdict =
      if (verification.openGapsAfter == 0) {
        FeatureTaskRuntimeVerdict.SATISFIED
      } else {
        FeatureTaskRuntimeVerdict.PROGRESS
      }
    return stampVerdict(attested, verdict)
  }

  private fun auditsClaim(context: PhaseStepOutputContext): Boolean {
    val audit = context as? PhaseAuditOutputContext ?: error("Audit output requires the accepted audit context.")
    return audit.auditsNoChangeClaim && NoChangeClaimResolution.activeClaimMap(context.progress) != null
  }

  private fun noChangeAcceptedOutput(
    context: PhaseStepOutputContext,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val validation = claimValidation(context) as? FeatureTaskRuntimeNoChangeClaimValidation.Rejected ?: return attested
    return stampVerdict(
      attested,
      FeatureTaskRuntimeVerdict.NO_CHANGE_REJECTED,
      "No-change claim rejected: ${validation.reasons.joinToString(" ")}",
    )
  }

  private fun stampVerdict(
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    verdict: FeatureTaskRuntimeVerdict,
    prose: String? = null,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val envelope = normalizedOutput.envelopeWireMap().toMutableMap()
    envelope[SharedPayloadKeys.VERDICT] = verdict.wireValue
    if (prose != null) {
      val produced =
        JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]).orEmpty().toMutableMap()
      produced[SharedPayloadKeys.VALUE] =
        listOfNotNull(auditProseValue(normalizedOutput.envelopeWireMap()), prose).joinToString("\n\n")
      envelope[SharedPayloadKeys.PRODUCED_OUTPUTS] = produced
    }
    return NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(FeatureTaskRuntimeWorkflowArtifactMap.from(envelope))
  }
}
