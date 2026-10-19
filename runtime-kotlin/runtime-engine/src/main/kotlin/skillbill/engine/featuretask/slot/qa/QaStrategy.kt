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
import skillbill.engine.featuretask.qa.receipt.QaPhaseOutputKeys
import skillbill.engine.featuretask.qa.receipt.QaReceipt
import skillbill.engine.featuretask.qa.receipt.QaReceiptSettlement
import skillbill.engine.featuretask.qa.receipt.QaReceiptValidator
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
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.audit.canonicalAcceptanceCriterionRef
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding
import skillbill.workflow.taskruntime.model.qa.QaRepairAttempt
import skillbill.workflow.taskruntime.model.qa.QaRepairAttemptOutcome
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

  override val loopRules: PhaseLoopRules = QaLoopRules

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
  ): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = directiveFor(stepId),
      valueContent =
        if (stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX) {
          QaPromptSections.FIX_VALUE_CONTENT
        } else {
          QaPromptSections.VALUE_CONTENT
        },
    )

  override fun withPreparedRun(
    run: PhaseRun,
    execute: (PhaseRun) -> PhaseOutcome,
  ): PhaseOutcome {
    if (run.phaseId != entryStep || run.qaExecution != null) return execute(run)
    val policy =
      when (checkNotNull(run.request.admittedExecution?.plan?.qaSelection)) {
        FeatureTaskRuntimeQaSelection.AUTO -> QaPolicy.AUTO
        FeatureTaskRuntimeQaSelection.OFF -> QaPolicy.OFF
      }
    return execution.withExecution(run.request.repoRoot, policy) { qa ->
      execute(run.copy(qaExecution = qa))
    }.value
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
      return runAgentStep(run, state)
    }
    val receipt = receiptValidator.skipped(target.reason, target.detail, qa.expected, qa.policy)
    val base =
      NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
        FeatureTaskRuntimeWorkflowArtifactMap.from(
          mapOf(
            SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
            SharedPayloadKeys.PHASE_ID to run.phaseId,
            SharedPayloadKeys.STATUS to "completed",
            SharedPayloadKeys.SUMMARY to target.detail,
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

  private val repairHooks =
    object : PhaseStepHooks {
      override fun launchPromptSupplement(
        run: PhaseRun,
        context: PhaseAttemptLaunchHookContext,
        state: PhaseStepBinding,
      ): String = findingSupplement(state)

      override fun interpretedOutput(
        run: PhaseRun,
        context: PhaseStepOutputContext,
        state: PhaseAcceptedStepExecution,
        output: NormalizedFeatureTaskRuntimePhaseOutput,
      ): NormalizedFeatureTaskRuntimePhaseOutput {
        if (output.status != "completed") return output
        val previous =
          (state as PhaseQaStepBinding).qaRepairState()
            ?: invalid("qa_fix completed without the QA findings state it repairs.")
        val number = previous.consumedAttempts + 1
        val attempt =
          QaRepairAttempt(
            attemptNumber = number,
            receiptTreeIdentity =
              previous.lastReceiptTreeIdentity ?: invalid("qa_fix repaired findings without a tested tree identity."),
            consumedFindingIds = previous.openFindings.map(QaOpenFinding::id),
            repairOutputRef = "${run.request.workflowId}:${run.phaseId}:attempt-$number",
            outcome = QaRepairAttemptOutcome.COMPLETED,
          )
        return output.copy(qaRepairState = previous.recordRepair(attempt))
      }
    }

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
        execution.warnIfChanged(run.request.repoRoot, qa.expected)
        return withReceipt(run, state, output, receipts.settle(qa))
      }

      fun withReceipt(
        run: PhaseRun,
        state: PhaseAcceptedStepExecution,
        output: NormalizedFeatureTaskRuntimePhaseOutput,
        receipt: QaReceipt,
      ): NormalizedFeatureTaskRuntimePhaseOutput {
        val qa = checkNotNull(run.qaExecution)
        receipts.recordDiagnostics(receipt)
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
        val settled = receipts.observe(previous, receipt, qa)
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
                      QaPhaseOutputKeys.CRITERION_IDS to
                        run.request.runInvariants.acceptanceCriteria.indices.map {
                          canonicalAcceptanceCriterionRef(it + 1)
                        },
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

  companion object {
    const val ID = "agent-qa"
    private const val MISSING_WORKSPACE = "QA execution was not prepared before step admission."
  }
}
