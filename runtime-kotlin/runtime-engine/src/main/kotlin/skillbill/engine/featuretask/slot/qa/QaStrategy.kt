package skillbill.engine.featuretask.slot.qa

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.qa.QaOutcomePayloadKeys
import skillbill.contracts.qa.QaRepairStatePayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.qa.execution.QaExecutionLifecycle
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecords
import skillbill.engine.featuretask.qa.report.QaPhaseOutputKeys
import skillbill.engine.featuretask.qa.report.QaReport
import skillbill.engine.featuretask.qa.report.QaReportSettlement
import skillbill.engine.featuretask.qa.report.QaReports
import skillbill.engine.featuretask.qa.report.QaSettledRun
import skillbill.engine.featuretask.qa.report.QaVerdict
import skillbill.engine.featuretask.qa.report.qaOutcomeReason
import skillbill.engine.featuretask.qa.report.qaReportRef
import skillbill.engine.featuretask.qa.report.qaVerdictOf
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
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
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
  private val reports: QaReportSettlement,
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
      execution.withExecution(run.request.repoRoot, policy) { qa -> execute(run.copy(qaExecution = qa)) }
    if (recordsGoalOutcome) {
      val completed = result.value as? PhaseOutcome.Completed
      if (completed != null) {
        val output = completed.output.normalizedOutput ?: error("completed QA step has no normalized output.")
        check(output.phaseId == entryStep && output.status == "completed") {
          "completed QA step has no accepted completed QA output."
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
      return runtimeCompletion(run, state, warning, QaReports.missing(warning))
    }
    return runtimeCompletion(run, state, target.detail, QaReports.skipped(target.reason, target.detail))
  }

  private fun runtimeCompletion(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
    summary: String,
    report: QaReport,
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
    val enriched = hooks.withReport(run, state, base, report)
    val verdict = QaVerdictRule.verdictFor(null, enriched.envelopeWireMap())
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
    if (stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA) QaVerdictRule else null

  override fun stepHooks(stepId: String): PhaseStepHooks {
    policies.policyOf(stepId)
    return if (stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX) repairHooks else hooks
  }

  private fun findingSupplement(state: PhaseStepBinding): String {
    val findings = (state as PhaseQaStepBinding).qaRepairState()?.openFindings.orEmpty()
    if (findings.isEmpty()) return ""
    return "\n\nOpen QA findings. Re-exercise the flows in these reports and say in the new report " +
      "whether each finding is fixed.\n" +
      findings.joinToString("\n") { finding -> "- ${finding.id}: reports=${finding.receiptRefs.joinToString()}" }
  }

  private val repairHooks = QaRepairHooks(execution, outcomes, reports)

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
        qa.callerBaseline?.let { caller -> execution.callerChanges(run.request.repoRoot, caller) }
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
        return withReport(run, state, output, reports.settle(qa))
      }

      fun withReport(
        run: PhaseRun,
        state: PhaseAcceptedStepExecution,
        output: NormalizedFeatureTaskRuntimePhaseOutput,
        settledReport: QaReport,
      ): NormalizedFeatureTaskRuntimePhaseOutput {
        val qa = checkNotNull(run.qaExecution)
        val executionWarnings = execution.outputWarnings(run.request.repoRoot, qa)
        val warned = settledReport.copy(warnings = (settledReport.warnings + executionWarnings).distinct())
        val record =
          JsonCodec.parseObjectOrNull(output.canonicalJson)
            ?.let(JsonCodec::jsonElementToValue)
            ?.let(JsonCodec::anyToStringAnyMap)
            ?: error("normalized QA output does not encode as an object.")
        val produced =
          JsonCodec.anyToStringAnyMap(record[SharedPayloadKeys.PRODUCED_OUTPUTS])
            ?: error("normalized QA output has no produced_outputs object.")
        val subtaskId = run.request.goalContinuation?.subtaskId ?: 1
        val previous = (state as PhaseQaStepBinding).qaRepairState() ?: QaRepairState(subtaskId)
        check(previous.subtaskId == subtaskId) { "QA repair state belongs to another subtask." }
        val repairWarning = QaRepairHooks.noChangeWarning(state, previous, qa.expected.treeIdentity)
        val settled = reports.observe(previous, warned, qa)
        val exhausted =
          settled.openFindings.isNotEmpty() &&
            settled.consumedAttempts >= FeatureTaskRuntimePhaseWorkflowDefinition.MAX_QA_FIX_ATTEMPTS
        val exhaustedWarning =
          (
            "QA findings ${settled.openFindings.joinToString { it.id }} remain after " +
              "${settled.consumedAttempts} qa_fix attempts; the run continues and reports them."
          ).takeIf { exhausted }
        val report = warned.copy(warnings = warned.warnings + listOfNotNull(repairWarning, exhaustedWarning))
        reports.recordDiagnostics(report)
        val enriched =
          record +
            (
              SharedPayloadKeys.PRODUCED_OUTPUTS to
                (
                  produced +
                    listOfNotNull(
                      QaPhaseOutputKeys.VERDICT to report.verdict.wireValue,
                      report.reason?.let { QaPhaseOutputKeys.REASON to it },
                      report.text?.let { QaPhaseOutputKeys.REPORT_TEXT to it },
                      QaPhaseOutputKeys.REPORT_REF to qa.workspace.reportPath.toString(),
                      QaPhaseOutputKeys.HEAD_REVISION to qa.expected.headRevision,
                      QaPhaseOutputKeys.TREE_IDENTITY to qa.expected.treeIdentity,
                      QaPhaseOutputKeys.EVIDENCE_PREFIX to qa.expected.evidencePrefix,
                      QaPhaseOutputKeys.WARNINGS to report.warnings,
                      QaPhaseOutputKeys.REPAIRS_EXHAUSTED to exhausted,
                      QaPhaseOutputKeys.OPEN_FINDINGS to
                        settled.openFindings.map { finding ->
                          mapOf(
                            QaOutcomePayloadKeys.ID to finding.id,
                            QaRepairStatePayloadKeys.AFFECTED_CRITERIA to finding.affectedCriteria,
                            QaRepairStatePayloadKeys.RECEIPT_REFS to finding.receiptRefs,
                          )
                        },
                    ).toMap()
                )
            )
        return NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(
          JsonCodec.mapToJsonString(enriched),
          "qa",
        ).copy(qaRepairState = settled)
      }
    }

  private fun recordGoalOutcome(
    workflowId: String,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
    testedTreeIdentity: String,
  ) {
    val wire = output.envelopeWireMap()
    val verdict = QaVerdictRule.verdictFor(null, wire)
    val produced =
      JsonCodec.anyToStringAnyMap(wire[SharedPayloadKeys.PRODUCED_OUTPUTS])
        ?: error("accepted QA output has no produced_outputs object.")
    val reportVerdict = qaVerdictOf(produced) ?: error("accepted QA output has no known QA verdict.")
    val report = FeatureTaskQaOutcomeRecords.reportFrom(produced)
    val legacyFindings = FeatureTaskQaOutcomeRecords.legacyFindingsFrom(produced, reportVerdict)
    val legacyDiagnostics = FeatureTaskQaOutcomeRecords.legacyDiagnosticsFrom(produced, reportVerdict)
    if (report?.warnings?.isNotEmpty() == true) reports.recordDiagnostics(report)
    if (legacyDiagnostics.isNotEmpty()) {
      reports.recordDiagnostics(
        QaReport(
          text = null,
          verdict = reportVerdict,
          reason = null,
          findings = emptyList(),
          reference = qaReportRef(produced),
          warnings = legacyDiagnostics,
        ),
      )
    }
    val effectiveVerdict =
      if (verdict == FeatureTaskRuntimeVerdict.QA_FINDINGS) QaVerdict.FINDINGS_OPEN else reportVerdict
    val attempts = output.qaRepairState?.consumedAttempts ?: error("accepted QA output has no repair-attempt state.")
    outcomes.recordSettled(
      workflowId,
      effectiveVerdict,
      qaOutcomeReason(effectiveVerdict, produced),
      attempts,
      QaSettledRun(
        testedTreeIdentity,
        reportVerdict,
        report,
        qaReportRef(produced),
        legacyFindings,
      ),
    )
  }

  companion object {
    const val ID = "agent-qa"
    private const val MISSING_WORKSPACE = "QA execution was not prepared before step admission."
  }
}
