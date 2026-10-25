package skillbill.engine.goalrunner.assessment

import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.containableFailure
import skillbill.engine.featuretask.model.core.PhaseInstructions
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.goalrunner.assessment.model.GoalAssessmentDisposition
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRegressionResult
import skillbill.goalrunner.assessment.model.GoalAssessmentRegressionState
import skillbill.goalrunner.assessment.model.GoalAssessmentRole
import skillbill.goalrunner.assessment.model.GoalAssessmentRound
import skillbill.goalrunner.assessment.model.GoalAssessmentRun
import skillbill.goalrunner.assessment.model.GoalAssessmentRunStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentScenarioResult
import skillbill.goalrunner.assessment.model.GoalAssessmentScenarioVerdict
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.decomposition.branchForFinalPullRequest
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal class GoalAssessmentRunServices(
  val phaseRunEntry: PhaseRunEntry,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val activityStampWriter: AgentActivityStampWriter,
  val progress: GoalAssessmentProgress,
  val diagnostics: RuntimeDiagnostics,
)

internal class GoalAssessmentRoundRun(
  private val services: GoalAssessmentRunServices,
  private val state: GoalRunnerManifestState,
  private val request: GoalRunnerRunRequest,
  private val bundle: GoalAssessmentBundle,
  private val prior: GoalAssessmentRecord,
  existing: GoalAssessmentRound?,
  roundNumber: Int,
) {
  private var round: GoalAssessmentRound =
    existing ?: GoalAssessmentRound(
      round = roundNumber,
      headSha = bundle.headSha,
      scopeFiles = emptyList(),
      assessorRuns = emptyList(),
      findings = emptyList(),
      scenarios = emptyList(),
      regressions = emptyList(),
      fix = null,
    )
  private var persisted = true

  fun execute(): GoalAssessmentRoundResult {
    planned().filterNot(::isRecorded).forEach(::runAssessor)
    val usable = round.assessorRuns.any { it.role != GoalAssessmentRole.VERIFIER && it.status == COMPLETED }
    if (!usable) return unavailable("every assessor failed or produced no usable output")
    verify()
    save()
    val record = recordOf(null)
    if (persisted) return GoalAssessmentRoundResult.Assessed(record, GoalAssessmentVerdicts.fixSetOf(round))
    warn("the verified round could not be persisted, so it grants no fix authority.")
    return GoalAssessmentRoundResult.Assessed(record, GoalAssessmentFixSet(emptyList(), emptyList()))
  }

  private fun planned(): List<PlannedRun> =
    bundle.subtasks.filter { it.range != GoalAssessmentCommitRange.NoCommit }
      .map { PlannedRun(GoalAssessmentRole.SUBTASK, it.id) } + PlannedRun(GoalAssessmentRole.CROSS_SUBTASK, null)

  private fun isRecorded(planned: PlannedRun): Boolean =
    round.assessorRuns.any { it.role == planned.role && it.subtaskId == planned.subtaskId }

  private fun missingInput(planned: PlannedRun): String? {
    val subtask = bundle.subtasks.firstOrNull { it.id == planned.subtaskId } ?: return null
    val range = subtask.range
    return when {
      range is GoalAssessmentCommitRange.Unavailable -> range.reason
      subtask.specText == null -> "the spec of subtask ${subtask.id} could not be read"
      else -> null
    }
  }

  private fun runAssessor(planned: PlannedRun) {
    missingInput(planned)?.let { reason ->
      warn("${planned.label()} did not run because its input is unavailable: $reason.")
      record(GoalAssessmentRun(planned.role, planned.subtaskId, GoalAssessmentRunStatus.FAILED))
      return
    }
    val focus =
      planned.subtaskId?.let { GoalAssessmentFocus.Subtask(it) } ?: GoalAssessmentFocus.CrossSubtask
    val launched = launch(focus, planned.label())
    val parsed = (launched as? Launch.Value)?.let { GoalAssessmentLineParser.parseAssessor(it.text) }
    val report = (parsed as? GoalAssessmentParse.Parsed)?.report
    (parsed as? GoalAssessmentParse.Malformed)?.let { warn("${planned.label()} output was malformed: ${it.reason}") }
    report?.let { absorb(planned, it) }
    record(GoalAssessmentRun(planned.role, planned.subtaskId, statusOf(launched, report != null)))
  }

  private fun verify() {
    val candidates = GoalAssessmentVerdicts.candidatesOf(round.findings)
    if (candidates.isEmpty() || round.assessorRuns.any { it.role == GoalAssessmentRole.VERIFIER }) return
    val launched = launch(GoalAssessmentFocus.Verifier(candidates), "the verifier")
    val parsed = (launched as? Launch.Value)?.let { GoalAssessmentLineParser.parseVerifier(it.text) }
    val report = (parsed as? GoalAssessmentParse.Parsed)?.report
    (parsed as? GoalAssessmentParse.Malformed)?.let { warn("the verifier output was malformed: ${it.reason}") }
    report?.let { applyVerdicts(it, candidates) }
    record(GoalAssessmentRun(GoalAssessmentRole.VERIFIER, null, statusOf(launched, report != null)))
  }

  private fun statusOf(
    launched: Launch,
    reported: Boolean,
  ): GoalAssessmentRunStatus =
    when {
      launched is Launch.Failed -> GoalAssessmentRunStatus.FAILED
      !reported -> GoalAssessmentRunStatus.MALFORMED
      else -> COMPLETED
    }

  private fun applyVerdicts(
    report: GoalVerifierReport,
    candidates: List<GoalAssessmentVerifierCandidate>,
  ) {
    val refs = candidates.map { it.findingRef }.toSet()
    val ignored = report.verdicts.count { it.findingRef !in refs }
    if (ignored > 0 || report.rejectedLines.isNotEmpty()) {
      warn("the verifier named $ignored unknown candidates and left ${report.rejectedLines.size} lines unparsed.")
    }
    val applied = GoalAssessmentVerdicts.apply(round.findings, report)
    val unsettled = applied.count { it.findingRef in refs && it.verification == GoalAssessmentVerification.UNVERIFIED }
    if (unsettled > 0) warn("$unsettled candidates stay unverified: the verifier gave no single verdict for them.")
    round = round.copy(findings = applied)
  }

  private fun absorb(
    planned: PlannedRun,
    report: GoalAssessorReport,
  ) {
    if (report.rejectedLines.isNotEmpty()) {
      warn("${planned.label()} left ${report.rejectedLines.size} lines outside the grammar.")
    }
    val used = nextRefNumber()
    val accepted = report.findings.take(minOf(MAX_FINDINGS_PER_RUN, MAX_REF_NUMBER - used + 1).coerceAtLeast(0))
    if (accepted.size < report.findings.size) warn("${planned.label()} reported more findings than fit; dropped some.")
    val added =
      accepted.mapIndexed { index, candidate ->
        val needsVerdict = candidate.kind == GoalAssessmentFindingKind.DEFECT && candidate.severity.blocksAdvance
        GoalAssessmentFinding(
          findingRef = "GA-%03d".format(used + index),
          kind = candidate.kind,
          severity = candidate.severity,
          path = candidate.path,
          line = candidate.line,
          failureScenario = candidate.failureScenario,
          guardedRef = candidate.guardedRef,
          subtaskId = planned.subtaskId,
          originRound = round.round,
          verification =
            if (needsVerdict) GoalAssessmentVerification.UNVERIFIED else GoalAssessmentVerification.NOT_REQUIRED,
          verificationEvidence = "",
          fixRoundsSurvived = 0,
          disposition = GoalAssessmentDisposition.PR_ONLY,
          state = GoalAssessmentFindingState.OPEN,
        )
      }
    round =
      round.copy(
        findings = round.findings + added,
        scenarios = mergeScenarios(round.scenarios, report.scenarios),
        regressions = mergeRegressions(round.regressions, report.regressions),
      )
  }

  private fun mergeScenarios(
    kept: List<GoalAssessmentScenarioResult>,
    reported: List<GoalAssessmentScenarioResult>,
  ): List<GoalAssessmentScenarioResult> {
    val declared = bundle.declared?.scenarios?.map { it.scenarioRef }.orEmpty().toSet()
    val known = reported.filter { it.scenarioRef in declared }
    if (known.size < reported.size) warn("an assessor settled scenarios the parent spec does not declare; ignored.")
    return (kept + known).groupBy { it.scenarioRef }.values.map { results ->
      results.firstOrNull { it.verdict == GoalAssessmentScenarioVerdict.BROKEN } ?: results.first()
    }
  }

  private fun mergeRegressions(
    kept: List<GoalAssessmentRegressionResult>,
    reported: List<GoalAssessmentRegressionResult>,
  ): List<GoalAssessmentRegressionResult> {
    val declared = bundle.declared?.transitionalRegressions?.map { it.regressionRef }.orEmpty().toSet()
    val known = reported.filter { it.regressionRef in declared }
    if (known.size < reported.size) warn("an assessor settled regressions the parent spec does not declare; ignored.")
    return (kept + known).groupBy { it.regressionRef }.values.map { results ->
      results.firstOrNull { it.state == GoalAssessmentRegressionState.NOT_RESTORED } ?: results.first()
    }
  }

  private fun nextRefNumber(): Int {
    val refs = (prior.rounds.take(round.round - 1).flatMap { it.findings } + round.findings).map { it.findingRef }
    return (refs.maxOfOrNull { it.removePrefix("GA-").toIntOrNull() ?: 0 } ?: 0) + 1
  }

  private fun record(run: GoalAssessmentRun) {
    round = round.copy(assessorRuns = round.assessorRuns + run)
    save()
  }

  private fun save() {
    round = round.copy(findings = GoalAssessmentVerdicts.withDispositions(round.findings))
    persisted = persist(recordOf(null))
  }

  private fun unavailable(reason: String): GoalAssessmentRoundResult.Unavailable {
    val record = recordOf(GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE)
    persist(record)
    warn("assessment unavailable: $reason.")
    return GoalAssessmentRoundResult.Unavailable(record, reason)
  }

  private fun recordOf(exitReason: GoalAssessmentExitReason?): GoalAssessmentRecord {
    val rounds = prior.rounds.take(round.round - 1) + round
    val open = rounds.flatMap { it.findings }.filter { it.state == GoalAssessmentFindingState.OPEN }
    return prior.copy(rounds = rounds, exitReason = exitReason, openFindingRefs = open.map { it.findingRef })
  }

  private fun persist(record: GoalAssessmentRecord): Boolean {
    val stored =
      runCatching { services.outcomeStore.recordGoalAssessmentRecord(state.parentWorkflowId, record) }
        .getOrElse { failure ->
          warn("the assessment record could not be persisted.", containableFailure(failure))
          return false
        }
    if (!stored) warn("the assessment record for '${state.parentWorkflowId}' was not persisted.")
    return stored
  }

  private fun launch(
    focus: GoalAssessmentFocus,
    label: String,
  ): Launch {
    val branch = state.manifest.featureBranch.orEmpty().ifBlank { state.manifest.branchForFinalPullRequest() }
    val phaseRequest =
      PhaseRunRequest(
        definitionId = SkeletonDefinition.GOAL_ASSESSMENT.id,
        repoRoot = request.repoRoot,
        invokedAgentId = request.configuredAgentOverrideId ?: request.invokedAgentId,
        intake = request.issueKey,
        instructions =
          PhaseInstructions(
            GoalAssessmentPromptRenderer.render(bundle, focus),
            setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT),
          ),
        timeout = request.timeout,
        agentAddonSelection = request.agentAddonSelection,
        modelAssignment = request.modelAssignment,
        eventSink = services.progress.eventSink(state, request),
        activityOutput = services.progress.activityOutput(state, request),
        activityStampSink = services.activityStampWriter.sink(state.parentWorkflowId, parentWorkflowId = null),
      )
    val result =
      runCatching { services.phaseRunEntry.runForGoal(phaseRequest, state.parentWorkflowId, branch) }
        .getOrElse { failure ->
          val contained = containableFailure(failure)
          warn("$label failed: ${contained.javaClass.simpleName}: ${contained.message}", contained)
          return Launch.Failed
        }
    return when (result) {
      is PhaseRunResult.Completed ->
        (result.acceptedOutput ?: result.value)?.takeIf(String::isNotBlank)?.let(Launch::Value)
          ?: failed("$label completed without a value")
      is PhaseRunResult.Blocked -> failed("$label stopped at '${result.stepId}': ${result.reason}")
    }
  }

  private fun failed(message: String): Launch.Failed {
    warn(message)
    return Launch.Failed
  }

  private fun warn(
    message: String,
    cause: Throwable? = null,
  ) = RuntimeDiagnosticsBestEffortWarning.record(services.diagnostics, "goal_assessment_gate: $message", cause)

  private sealed interface Launch {
    data class Value(val text: String) : Launch

    data object Failed : Launch
  }

  private data class PlannedRun(val role: GoalAssessmentRole, val subtaskId: Int?) {
    fun label(): String = subtaskId?.let { "the subtask $it assessor" } ?: "the cross-subtask assessor"
  }

  private companion object {
    val COMPLETED = GoalAssessmentRunStatus.COMPLETED
    const val MAX_FINDINGS_PER_RUN = 50
    const val MAX_REF_NUMBER = 999
  }
}
