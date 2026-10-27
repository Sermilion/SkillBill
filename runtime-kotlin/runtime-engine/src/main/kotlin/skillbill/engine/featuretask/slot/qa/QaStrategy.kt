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
import skillbill.engine.featuretask.slot.PhaseBindingFamily
import skillbill.engine.featuretask.slot.PhaseFailureContinuationOutput
import skillbill.engine.featuretask.slot.PhaseLoopCapability
import skillbill.engine.featuretask.slot.PhasePreparedRunCapability
import skillbill.engine.featuretask.slot.PhaseResumeCapability
import skillbill.engine.featuretask.slot.PhaseStepDescriptor
import skillbill.engine.featuretask.slot.PhaseStepFailure
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.PhaseVerdictCapability
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.state.PhaseQaStepBinding
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

@Inject
class QaStrategy(
  private val reports: QaReportSettlement,
  private val execution: QaExecutionLifecycle,
  private val outcomes: FeatureTaskQaOutcomeRecorder,
) : PhaseStrategy(),
  PhasePreparedRunCapability by QaPreparedRun(reports, execution, outcomes),
  PhaseResumeCapability by QaResume,
  PhaseLoopCapability by QaLoopCapability,
  PhaseVerdictCapability {
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

  private val qaLoopRules = QaLoopRules(outcomes)

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

  override fun stepDescriptor(stepId: String): PhaseStepDescriptor<*> {
    policies.policyOf(stepId)
    val stepHooks = if (stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX) repairHooks else hooks
    val stepLoopRules = qaLoopRules.takeIf { stepId == entryStep }
    return PhaseStepDescriptor(PhaseBindingFamily.QaFindings, stepHooks, stepLoopRules) { run, state ->
      runStep(run, state)
    }
  }

  private fun runStep(
    run: PhaseRun,
    state: PhaseQaStepBinding,
  ): PhaseOutcome {
    val target = run.qaExecution?.target
    if (run.phaseId != entryStep) return runAgentStep(run, state)
    if (target !is QaExecutionTarget.Skipped) {
      if (run.request.admittedExecution != null) outcomes.recordAttempted(run.request.qaOutcomeWorkflowId)
      return runAgentStep(run, state)
    }
    return runtimeCompletion(run, state, target.detail, QaReports.skipped(target.reason, target.detail))
  }

  private fun runtimeCompletion(
    run: PhaseRun,
    state: PhaseQaStepBinding,
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

  private fun findingSupplement(state: PhaseQaStepBinding): String {
    val findings = state.qaRepairState()?.openFindings.orEmpty()
    if (findings.isEmpty()) return ""
    return "\n\nOpen QA findings. Re-exercise the flows in these reports and say in the new report " +
      "whether each finding is fixed.\n" +
      findings.joinToString("\n") { finding -> "- ${finding.id}: reports=${finding.receiptRefs.joinToString()}" }
  }

  private val stops = QaStopContinuation(outcomes)

  private val repairHooks = QaRepairHooks(execution, outcomes, reports, stops)

  private val hooks =
    object : PhaseStepHooks<PhaseQaStepBinding> {
      override fun failureContinuationOutput(
        run: PhaseRun,
        failure: PhaseStepFailure,
        state: PhaseQaStepBinding,
      ): PhaseFailureContinuationOutput? = stops.outputFor(run, failure, state)

      override fun launchPromptSupplement(
        run: PhaseRun,
        context: PhaseAttemptLaunchHookContext,
        state: PhaseQaStepBinding,
      ): String = run.qaExecution?.let(QaPromptSections::supplement).orEmpty() + findingSupplement(state)

      override fun beforeAgentLaunch(
        run: PhaseRun,
        context: PhaseAttemptLaunchHookContext,
        state: PhaseQaStepBinding,
      ): String? = MISSING_WORKSPACE.takeIf { run.qaExecution == null }

      override fun checkValidatedOutput(
        run: PhaseRun,
        context: PhaseStepOutputContext,
        state: PhaseQaStepBinding,
        outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
      ): PhaseStepOutputCheck {
        val qa = run.qaExecution ?: return PhaseStepOutputCheck.Block(MISSING_WORKSPACE)
        qa.callerBaseline?.let { caller -> execution.callerChanges(run.request.repoRoot, caller) }
        return PhaseStepOutputCheck.Accept
      }

      override fun interpretedOutput(
        run: PhaseRun,
        context: PhaseStepOutputContext,
        state: PhaseQaStepBinding,
        output: NormalizedFeatureTaskRuntimePhaseOutput,
      ): NormalizedFeatureTaskRuntimePhaseOutput {
        if (output.status == "blocked" || output.status == "failed") return output
        val qa = run.qaExecution ?: return output
        return withReport(run, state, output, reports.settle(qa))
      }

      fun withReport(
        run: PhaseRun,
        state: PhaseQaStepBinding,
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
        val subtaskId = run.request.qaSubtaskId()
        val previous = state.repairStateOrInitial(subtaskId)
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

  companion object {
    const val ID = "agent-qa"
    private const val MISSING_WORKSPACE = "QA execution was not prepared before step admission."
  }
}

private object QaResume : PhaseResumeCapability {
  override fun resumeRules(stepId: String): PhaseResumeRules =
    if (stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX) QaResumeRules else PhaseResumeRules.None
}

private class QaPreparedRun(
  private val reports: QaReportSettlement,
  private val execution: QaExecutionLifecycle,
  private val outcomes: FeatureTaskQaOutcomeRecorder,
) : PhasePreparedRunCapability {
  override fun withPreparedRun(
    run: PhaseRun,
    execute: (PhaseRun) -> PhaseOutcome,
  ): PhaseOutcome {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA || run.qaExecution != null) {
      return execute(
        run,
      )
    }
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
    if (recordsGoalOutcome) outcomes.recordAttempted(run.request.qaOutcomeWorkflowId)
    val result =
      execution.withExecution(run.request.repoRoot, policy) { qa -> execute(run.copy(qaExecution = qa)) }
    if (recordsGoalOutcome) {
      val completed = result.value as? PhaseOutcome.Completed
      if (completed != null) {
        val output = completed.output.normalizedOutput ?: error("completed QA step has no normalized output.")
        check(output.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA && output.status == "completed") {
          "completed QA step has no accepted completed QA output."
        }
        recordGoalOutcome(run.request.qaOutcomeWorkflowId, output, result.expected.treeIdentity)
      }
    }
    return result.value
  }

  private fun recordGoalOutcome(
    workflowId: String,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
    testedTreeIdentity: String,
  ) {
    val wire = output.envelopeWireMap()
    val verdict = QaVerdictRule.verdictFor(output.verdict?.let(FeatureTaskRuntimeVerdict::fromWire), wire)
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
        FeatureTaskQaFailureKind.fromWire(produced[QaPhaseOutputKeys.FAILURE_KIND] as? String),
      ),
    )
  }
}
