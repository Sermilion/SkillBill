package skillbill.engine.featuretask.slot.qa

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.qa.QaReceiptPayloadKeys
import skillbill.contracts.qa.QaRepairStatePayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.qa.execution.QaExecutionLifecycle
import skillbill.engine.featuretask.qa.receipt.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.qa.receipt.QaIncompleteReason
import skillbill.engine.featuretask.qa.receipt.QaPhaseOutputKeys
import skillbill.engine.featuretask.qa.receipt.QaReceipt
import skillbill.engine.featuretask.qa.receipt.QaReceiptSettlement
import skillbill.engine.featuretask.qa.receipt.QaReceiptValidator
import skillbill.engine.featuretask.qa.receipt.QaSettledRun
import skillbill.engine.featuretask.qa.receipt.QaVerdict
import skillbill.engine.featuretask.qa.receipt.qaOutcomeReason
import skillbill.engine.featuretask.qa.receipt.restrictedToCriteria
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseExecutionBindingKind
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseQaStepBinding
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.PhaseStepBinding
import skillbill.error.featuretask.qa.invalidQaReceipt
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.audit.canonicalAcceptanceCriterionRef
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

@Inject
class QaStrategy(
  private val receipts: QaReceiptSettlement,
  private val receiptValidator: QaReceiptValidator,
  private val execution: QaExecutionLifecycle,
  private val outcomes: FeatureTaskQaOutcomeRecorder,
) : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA to
        PhaseStepPolicy(
          mutating = false,
          singleAgentSession = true,
          readOnlyIdle = false,
          fileMutating = false,
          generationScoped = false,
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX to
        PhaseStepPolicy(
          mutating = true,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = true,
          generationScoped = false,
          extendsOwnedInventory = true,
        ),
    )

  override val slot: PhaseSlot = PhaseSlot.QA
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val optionalSteps: Set<String> = setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX)
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA

  override fun executionBindingKind(stepId: String): PhaseExecutionBindingKind = PhaseExecutionBindingKind.QA_FINDINGS

  override val loopRules: PhaseLoopRules = QaLoopRules(outcomes)

  override fun resumeRules(stepId: String): PhaseResumeRules =
    if (stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX) QaResumeRules else PhaseResumeRules.None

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return if (stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX) {
      QaPromptSections.FIX_DIRECTIVE
    } else {
      QaPromptSections.DIRECTIVE
    }
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections {
    val qaStep = stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA
    return PhaseStepPromptSections(
      taskDirective = directiveFor(stepId),
      runsValidationGate = qaStep,
      runsBuildGate = qaStep,
      valueContent = if (qaStep) QaPromptSections.VALUE_CONTENT else QaPromptSections.FIX_VALUE_CONTENT,
    )
  }

  override fun withPreparedRun(
    run: PhaseRun,
    execute: (PhaseRun) -> PhaseOutcome,
  ): PhaseOutcome {
    if (run.phaseId != entryStep || run.qaExecution != null) return execute(run)
    val recordsGoalOutcome = run.request.admittedExecution == null && run.request.qaSelection != null
    val policy =
      when (
        requireNotNull(run.request.admittedExecution?.plan?.qaSelection ?: run.request.qaSelection) {
          "QA selection is required for an in-memory QA run."
        }
      ) {
        FeatureTaskRuntimeQaSelection.AUTO -> QaPolicy.AUTO
        FeatureTaskRuntimeQaSelection.OFF -> QaPolicy.OFF
      }
    if (recordsGoalOutcome) outcomes.recordAttempted(run.request.workflowId)
    val result =
      execution.withExecution(run.request.repoRoot, policy, acceptanceCriteria = criterionIds(run).toSet()) { qa ->
        execute(run.copy(qaExecution = qa))
      }
    if (recordsGoalOutcome) {
      val completed = result.value as? PhaseOutcome.Completed
      if (completed != null) {
        val output = completed.output.normalizedOutput ?: invalid("completed QA step has no normalized output.")
        if (output.phaseId != entryStep || output.status != "completed") {
          invalid("completed QA step has no accepted completed QA output.")
        }
        recordGoalOutcome(run.request.workflowId, output, result.expected.treeIdentity)
      }
    }
    return result.value
  }

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome {
    val qa = run.qaExecution
    val target = qa?.target
    if (run.phaseId != entryStep) return runAgentStep(run, state)
    if (target !is QaExecutionTarget.Skipped) {
      if (run.request.admittedExecution != null) outcomes.recordAttempted(run.request.workflowId)
      val outcome = runAgentStep(run, state)
      val stopped = outcome as? PhaseOutcome.Blocked
      if (stopped == null || qa == null || qa.policy == QaPolicy.STANDALONE) return outcome
      val warning = "QA recorded incomplete because the QA agent step stopped: ${stopped.reason}"
      return runtimeCompletion(
        run,
        state,
        warning,
        receiptValidator.incomplete(warning, qa.expected, qa.policy, QaIncompleteReason.INTERRUPTED),
      )
    }
    return runtimeCompletion(
      run,
      state,
      target.detail,
      receiptValidator.skipped(target.reason, target.detail, qa.expected, qa.policy),
    )
  }

  private fun runtimeCompletion(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
    summary: String,
    receipt: QaReceipt,
  ): PhaseOutcome {
    val base =
      NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
        FeatureTaskRuntimeWorkflowArtifactMap.from(
          mapOf(
            SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
            SharedPayloadKeys.PHASE_ID to run.phaseId,
            SharedPayloadKeys.STATUS to "completed",
            SharedPayloadKeys.SUMMARY to summary,
            SharedPayloadKeys.PRODUCED_OUTPUTS to emptyMap<String, Any?>(),
          ),
        ),
      )
    val enriched = hooks.withReceipt(run, state, base, receipt)
    val verdict = QaVerdictRule(receiptValidator).verdictFor(null, enriched.envelopeWireMap())
    val output = enriched.copy(verdict = verdict.wireValue)
    val iteration = state.nextStepIteration()
    state.settleRuntimeAuthoredCompletion(run.phaseId, output)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, output.canonicalJson, output, null),
    )
  }

  override fun verdictRule(
    stepId: String,
    diagnostics: RuntimeDiagnostics,
  ): FeatureTaskRuntimeStepVerdictRule? =
    if (stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA) QaVerdictRule(receiptValidator) else null

  override fun stepHooks(stepId: String): PhaseStepHooks {
    policies.policyOf(stepId)
    return if (stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX) repairHooks else hooks
  }

  private fun criterionIds(run: PhaseRun): List<String> =
    run.request.runInvariants.acceptanceCriteria.indices.map { canonicalAcceptanceCriterionRef(it + 1) }

  private fun findingSupplement(state: PhaseStepBinding): String {
    val findings = (state as PhaseQaStepBinding).qaRepairState()?.openFindings.orEmpty()
    if (findings.isEmpty()) return ""
    return "\n\nOpen QA findings. Re-exercise the flows in these receipts " +
      "and report assertions for every affected criterion.\n" +
      findings.joinToString("\n") { finding ->
        "- ${finding.id}: criteria=${finding.affectedCriteria.joinToString()}; " +
          "receipts=${finding.receiptRefs.joinToString()}"
      }
  }

  private val repairHooks = QaRepairHooks(execution, outcomes)

  private val hooks =
    object : PhaseStepHooks {
      override fun launchPromptSupplement(
        run: PhaseRun,
        context: PhaseAttemptLaunchHookContext,
        state: PhaseStepBinding,
      ): String = run.qaExecution?.let(QaPromptSections::supplement).orEmpty() + findingSupplement(state)

      override fun beforeAgentLaunch(
        run: PhaseRun,
        context: PhaseAttemptLaunchHookContext,
        state: PhaseStepBinding,
      ): String? = MISSING_WORKSPACE.takeIf { run.qaExecution == null }

      override fun checkValidatedOutput(
        run: PhaseRun,
        context: PhaseStepOutputContext,
        state: PhaseAcceptedStepExecution,
        outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
      ): PhaseStepOutputCheck {
        val qa = run.qaExecution ?: return PhaseStepOutputCheck.Block(MISSING_WORKSPACE)
        execution.warnIfChanged(run.request.repoRoot, qa.expected)
        return PhaseStepOutputCheck.Accept
      }

      override fun interpretedOutput(
        run: PhaseRun,
        context: PhaseStepOutputContext,
        state: PhaseAcceptedStepExecution,
        output: NormalizedFeatureTaskRuntimePhaseOutput,
      ): NormalizedFeatureTaskRuntimePhaseOutput {
        if (output.status == "blocked" || output.status == "failed") return output
        val qa = run.qaExecution ?: return output
        return withReceipt(run, state, output, receipts.settle(qa))
      }

      fun withReceipt(
        run: PhaseRun,
        state: PhaseAcceptedStepExecution,
        output: NormalizedFeatureTaskRuntimePhaseOutput,
        settledReceipt: QaReceipt,
      ): NormalizedFeatureTaskRuntimePhaseOutput {
        val qa = checkNotNull(run.qaExecution)
        val criterionIds = criterionIds(run)
        val executionWarnings = execution.outputWarnings(run.request.repoRoot, qa)
        val restricted =
          settledReceipt.copy(warnings = (settledReceipt.warnings + executionWarnings).distinct())
            .restrictedToCriteria(criterionIds.toSet())
        val record =
          JsonCodec.parseObjectOrNull(output.canonicalJson)
            ?.let(JsonCodec::jsonElementToValue)
            ?.let(JsonCodec::anyToStringAnyMap)
            ?: invalid("normalized QA output does not encode as an object.")
        val produced =
          JsonCodec.anyToStringAnyMap(record[SharedPayloadKeys.PRODUCED_OUTPUTS])
            ?: invalid("normalized QA output has no produced_outputs object.")
        val subtaskId = run.request.goalContinuation?.subtaskId ?: 1
        val previous = (state as PhaseQaStepBinding).qaRepairState() ?: QaRepairState(subtaskId)
        if (previous.subtaskId != subtaskId) invalid("QA repair state belongs to another subtask.")
        val repairWarning = QaRepairHooks.noChangeWarning(state, previous, qa.expected.treeIdentity)
        val settled = receipts.observe(previous, restricted, qa)
        val exhausted =
          settled.openFindings.isNotEmpty() &&
            settled.consumedAttempts >= FeatureTaskRuntimePhaseWorkflowDefinition.MAX_QA_FIX_ATTEMPTS
        val exhaustedWarning =
          (
            "QA findings ${settled.openFindings.joinToString { it.id }} remain after " +
              "${settled.consumedAttempts} qa_fix attempts; the run continues and reports them."
          ).takeIf { exhausted }
        val receipt = restricted.copy(warnings = restricted.warnings + listOfNotNull(repairWarning, exhaustedWarning))
        receipts.recordDiagnostics(receipt)
        val enriched =
          record +
            (
              SharedPayloadKeys.PRODUCED_OUTPUTS to
                (
                  produced +
                    mapOf(
                      QaPhaseOutputKeys.RECEIPT to receipt.wire,
                      QaPhaseOutputKeys.HEAD_REVISION to qa.expected.headRevision,
                      QaPhaseOutputKeys.TREE_IDENTITY to qa.expected.treeIdentity,
                      QaPhaseOutputKeys.EVIDENCE_PREFIX to qa.expected.evidencePrefix,
                      QaPhaseOutputKeys.RECEIPT_REF to qa.workspace.receiptPath.toString(),
                      QaPhaseOutputKeys.CRITERION_IDS to criterionIds,
                      QaPhaseOutputKeys.WARNINGS to receipt.warnings,
                      QaPhaseOutputKeys.REPAIRS_EXHAUSTED to exhausted,
                      QaPhaseOutputKeys.OPEN_FINDINGS to
                        settled.openFindings.map { finding ->
                          mapOf(
                            QaReceiptPayloadKeys.ID to finding.id,
                            QaReceiptPayloadKeys.AFFECTED_CRITERIA to finding.affectedCriteria,
                            QaRepairStatePayloadKeys.RECEIPT_REFS to finding.receiptRefs,
                          )
                        },
                    )
                )
            )
        return NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(
          JsonCodec.mapToJsonString(enriched),
          "qa",
        ).copy(qaRepairState = settled)
      }
    }

  private fun invalid(reason: String): Nothing = throw invalidQaReceipt(reason)

  private fun recordGoalOutcome(
    workflowId: String,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
    testedTreeIdentity: String,
  ) {
    val wire = output.envelopeWireMap()
    val verdict = QaVerdictRule(receiptValidator).verdictFor(null, wire)
    val produced =
      JsonCodec.anyToStringAnyMap(wire[SharedPayloadKeys.PRODUCED_OUTPUTS])
        ?: invalid("accepted QA output has no produced_outputs object.")
    val receipt =
      JsonCodec.anyToStringAnyMap(produced[QaPhaseOutputKeys.RECEIPT])
        ?: invalid("accepted QA output has no runtime-validated receipt.")
    val receiptVerdict =
      QaVerdict.fromWire(receipt[QaReceiptPayloadKeys.VERDICT] as? String)
        ?: invalid("accepted QA receipt has no known verdict.")
    val effectiveVerdict =
      if (verdict == FeatureTaskRuntimeVerdict.QA_FINDINGS) QaVerdict.FINDINGS_OPEN else receiptVerdict
    val reason = qaOutcomeReason(effectiveVerdict, receipt, produced)
    val attempts =
      output.qaRepairState?.consumedAttempts
        ?: invalid("accepted QA output has no repair-attempt state.")
    outcomes.recordSettled(
      workflowId,
      effectiveVerdict,
      reason,
      attempts,
      QaSettledRun(testedTreeIdentity, receiptVerdict, receipt, produced[QaPhaseOutputKeys.RECEIPT_REF] as? String),
    )
  }

  companion object {
    const val ID = "agent-qa"
    private const val MISSING_WORKSPACE = "QA execution was not prepared before step admission."
  }
}
