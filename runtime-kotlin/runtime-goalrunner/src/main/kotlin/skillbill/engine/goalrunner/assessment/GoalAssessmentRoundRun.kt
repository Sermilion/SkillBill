package skillbill.engine.goalrunner.assessment

import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.containableFailure
import skillbill.engine.featuretask.model.core.PhaseInstructions
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.operation.unittestvalue.UnitTestValueCheckReview
import skillbill.error.core.catchingNonCancellation
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
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.branchForFinalPullRequest
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal class GoalAssessmentRunServices(
  val phaseRunEntry: PhaseRunEntry,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val activityStampWriter: AgentActivityStampWriter,
  val progress: GoalAssessmentProgress,
  val diagnostics: RuntimeDiagnostics,
  val gitOperations: WorkflowGitOperations,
) {
  fun branchOf(state: GoalRunnerManifestState): String =
    state.manifest.featureBranch.orEmpty().ifBlank { state.manifest.branchForFinalPullRequest() }

  fun persist(
    state: GoalRunnerManifestState,
    record: GoalAssessmentRecord,
  ): Boolean {
    val stored =
      catchingNonCancellation { outcomeStore.recordGoalAssessmentRecord(state.parentWorkflowId, record) }
        .getOrElse { failure ->
          warn("the assessment record could not be persisted.", containableFailure(failure))
          return false
        }
    if (!stored) warn("the assessment record for '${state.parentWorkflowId}' was not persisted.")
    return stored
  }

  fun phaseRequest(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    definition: SkeletonDefinition,
    instructions: PhaseInstructions?,
  ): PhaseRunRequest =
    PhaseRunRequest(
      definitionId = definition.id,
      repoRoot = request.repoRoot,
      invokedAgentId = request.configuredAgentOverrideId ?: request.invokedAgentId,
      intake = request.issueKey,
      instructions = instructions,
      timeout = request.timeout,
      agentAddonSelection = request.agentAddonSelection,
      modelAssignment = request.modelAssignment,
      eventSink = progress.eventSink(state, request),
      activityOutput = progress.activityOutput(state, request),
      activityStampSink = activityStampWriter.sink(state.parentWorkflowId, parentWorkflowId = null),
    )

  fun warn(
    message: String,
    cause: Throwable? = null,
  ) = RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "goal_assessment_gate: $message", cause)
}

internal class GoalAssessmentRoundRun(
  private val services: GoalAssessmentRunServices,
  private val state: GoalRunnerManifestState,
  private val request: GoalRunnerRunRequest,
  bundle: GoalAssessmentBundle,
  private val prior: GoalAssessmentRecord,
  existing: GoalAssessmentRound?,
  private val roundNumber: Int,
) {
  private var round: GoalAssessmentRound =
    existing ?: GoalAssessmentRound(
      round = roundNumber,
      headSha = bundle.headSha,
      scopeFiles = if (roundNumber == 1) bundle.scopeFiles else GoalAssessmentScope.of(prior, roundNumber),
      assessorRuns = emptyList(),
      findings = emptyList(),
      scenarios = emptyList(),
      regressions = emptyList(),
      fix = null,
    )
  private val bundle: GoalAssessmentBundle =
    if (roundNumber > 1) bundle.copy(reassessmentScope = round.scopeFiles) else bundle
  private var earlier: List<GoalAssessmentRound> = prior.rounds.take(roundNumber - 1)
  private var persisted = true
  private var verificationReady = true
  private var missingCoverage: List<String> = emptyList()
  private val previousTarget: String? = earlier.lastOrNull()?.targetRef

  fun execute(): GoalAssessmentRoundResult {
    val plan = planned()
    missingCoverage = missingDeclaredCoverage()
    plan.filterNot(::isRecorded).forEach(::runAssessor)
    val usable =
      plan.all { planned ->
        round.assessorRuns.any {
          it.role == planned.role && it.subtaskId == planned.subtaskId &&
            (planned.role != GoalAssessmentRole.CROSS_SUBTASK || missingCoverage.isEmpty()) &&
            (it.status == COMPLETED || (it.status == GoalAssessmentRunStatus.MALFORMED && hasRetainedFinding(planned)))
        }
      }
    if (!usable) {
      val reason =
        missingCoverage.takeIf { it.isNotEmpty() }?.let {
          "the cross-subtask assessor omitted declared records: ${it.joinToString(", ")}"
        } ?: "an assessor failed or produced no usable output"
      return unavailable(reason)
    }
    verify()
    if (!verificationReady) return unavailable("independent verification did not settle every selected item")
    save()
    val record = recordOf(null)
    if (persisted) return GoalAssessmentRoundResult.Assessed(record, GoalAssessmentVerdicts.fixSetOf(earlier + round))
    return unavailable("the verified round could not be persisted, so it grants no fix authority")
  }

  private fun planned(): List<PlannedRun> {
    val assessable = bundle.subtasks.filter { it.range != GoalAssessmentCommitRange.NoCommit }
    if (roundNumber == 1) {
      return listOf(PlannedRun(GoalAssessmentRole.TEST_VALUE_REVIEW, null)) +
        assessable.map { PlannedRun(GoalAssessmentRole.SUBTASK, it.id) } +
        PlannedRun(GoalAssessmentRole.CROSS_SUBTASK, null)
    }
    return if (previousTarget?.startsWith("IS-") == true || previousTarget?.startsWith("TR-") == true) {
      listOf(PlannedRun(GoalAssessmentRole.CROSS_SUBTASK, null))
    } else {
      emptyList()
    }
  }

  private fun isRecorded(planned: PlannedRun): Boolean =
    round.assessorRuns.any {
      it.role == planned.role && it.subtaskId == planned.subtaskId &&
        (planned.role != GoalAssessmentRole.CROSS_SUBTASK || missingDeclaredCoverage().isEmpty()) &&
        (it.status == COMPLETED || (it.status == GoalAssessmentRunStatus.MALFORMED && hasRetainedFinding(planned)))
    }

  private fun missingDeclaredCoverage(
    scenarios: List<GoalAssessmentScenarioResult> = round.scenarios,
    regressions: List<GoalAssessmentRegressionResult> = round.regressions,
  ): List<String> {
    if (roundNumber != 1) return emptyList()
    val declaredScenarios = bundle.declared?.scenarios?.map { it.scenarioRef }.orEmpty().toSet()
    val declaredRegressions = bundle.declared?.transitionalRegressions?.map { it.regressionRef }.orEmpty().toSet()
    return (declaredScenarios - scenarios.map { it.scenarioRef }.toSet()).sorted() +
      (declaredRegressions - regressions.map { it.regressionRef }.toSet()).sorted()
  }

  private fun hasRetainedFinding(planned: PlannedRun): Boolean =
    round.findings.any { finding ->
      when (planned.role) {
        GoalAssessmentRole.TEST_VALUE_REVIEW -> finding.kind == GoalAssessmentFindingKind.TEST_VALUE
        GoalAssessmentRole.SUBTASK -> finding.subtaskId == planned.subtaskId
        GoalAssessmentRole.CROSS_SUBTASK ->
          finding.subtaskId == null &&
            finding.kind == GoalAssessmentFindingKind.DEFECT
        else -> false
      }
    }

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
    if (planned.role == GoalAssessmentRole.TEST_VALUE_REVIEW) {
      runTestValueReview()
      return
    }
    missingInput(planned)?.let { reason ->
      warn("${planned.label()} did not run because its input is unavailable: $reason.")
      record(GoalAssessmentRun(planned.role, planned.subtaskId, GoalAssessmentRunStatus.FAILED))
      return
    }
    val focus = assessorFocus(planned)
    val launched = launch(focus, planned.label())
    val parsed = (launched as? Launch.Value)?.let { GoalAssessmentLineParser.parseAssessor(it.text) }
    val report = (parsed as? GoalAssessmentParse.Parsed)?.report
    (parsed as? GoalAssessmentParse.Malformed)?.let { warn("${planned.label()} output was malformed: ${it.reason}") }
    val accepted = report?.let { acceptReport(planned, it) } == true
    record(GoalAssessmentRun(planned.role, planned.subtaskId, statusOf(launched, accepted)))
  }

  private fun assessorFocus(planned: PlannedRun): GoalAssessmentFocus {
    return when {
      roundNumber == 1 ->
        planned.subtaskId?.let { GoalAssessmentFocus.Subtask(it) } ?: GoalAssessmentFocus.CrossSubtask
      previousTarget?.startsWith("IS-") == true -> GoalAssessmentFocus.Scenario(requireNotNull(previousTarget))
      else -> GoalAssessmentFocus.Regression(requireNotNull(previousTarget))
    }
  }

  private fun verify() {
    val candidates =
      GoalAssessmentVerdicts.candidatesOf(round.findings) +
        GoalAssessmentVerdicts.carriedCandidatesOf(earlier.flatMap { it.findings })
          .filter { it.findingRef == previousTarget }
    if (candidates.isEmpty()) return
    round.assessorRuns.firstOrNull { it.role == GoalAssessmentRole.VERIFIER }?.let {
      verificationReady = it.status == COMPLETED
      return
    }
    val launched = launch(GoalAssessmentFocus.Verifier(candidates), "the verifier")
    val parsed = (launched as? Launch.Value)?.let { GoalAssessmentLineParser.parseVerifier(it.text) }
    val report = (parsed as? GoalAssessmentParse.Parsed)?.report
    (parsed as? GoalAssessmentParse.Malformed)?.let { warn("the verifier output was malformed: ${it.reason}") }
    val verifierValid =
      report != null &&
        candidates.all { candidate ->
          val verdicts = report.verdicts.filter { it.findingRef == candidate.findingRef }
          verdicts.size == 1 && verdicts.single().evidence.isNotBlank()
        }
    val expectedRefs = candidates.map { it.findingRef }.toSet()
    val actualRefs = report?.verdicts?.map { it.findingRef }
    val exactRefs = actualRefs != null && actualRefs.toSet() == expectedRefs && actualRefs.size == expectedRefs.size
    verificationReady = verificationReady && verifierValid && exactRefs
    if (verificationReady) report?.let { applyVerdicts(it, candidates) }
    record(
      GoalAssessmentRun(GoalAssessmentRole.VERIFIER, null, statusOf(launched, verifierValid && exactRefs)),
    )
  }

  private fun runTestValueReview() {
    if (!checkoutMatchesHead()) {
      verificationReady = false
      warn("the repository is not an unchanged checkout of the assembled goal head.")
      record(GoalAssessmentRun(GoalAssessmentRole.TEST_VALUE_REVIEW, null, GoalAssessmentRunStatus.FAILED))
      return
    }
    val testPaths = reviewPaths() ?: return
    runScopedTestValueReview(testPaths)
  }

  private fun reviewPaths(): List<String>? {
    val scopedTests = UnitTestValueCheckReview.testPaths(bundle.scopeFiles)
    val testPaths =
      when (
        val result =
          services.gitOperations.reviewablePathsAtRevision(request.repoRoot, bundle.headSha, scopedTests)
      ) {
        is WorkflowGitNameListResult.Listed -> result.names
        is WorkflowGitNameListResult.Failed -> {
          verificationReady = false
          warn("the committed unit-test scope could not be resolved at the assembled head: ${result.error}")
          record(GoalAssessmentRun(GoalAssessmentRole.TEST_VALUE_REVIEW, null, GoalAssessmentRunStatus.FAILED))
          return null
        }
      }
    if (!checkoutMatchesHead()) {
      verificationReady = false
      warn("the repository changed while the committed unit-test scope was being resolved.")
      record(GoalAssessmentRun(GoalAssessmentRole.TEST_VALUE_REVIEW, null, GoalAssessmentRunStatus.FAILED))
      return null
    }
    if (testPaths.isEmpty()) return recordEmptyReview()
    return testPaths
  }

  private fun recordEmptyReview(): List<String>? {
    if (!checkoutMatchesHead()) {
      verificationReady = false
      warn("the repository changed before the empty-scope unit-test review could be recorded.")
      record(GoalAssessmentRun(GoalAssessmentRole.TEST_VALUE_REVIEW, null, GoalAssessmentRunStatus.FAILED))
      return null
    }
    record(GoalAssessmentRun(GoalAssessmentRole.TEST_VALUE_REVIEW, null, COMPLETED))
    return null
  }

  private fun runScopedTestValueReview(testPaths: List<String>) {
    val before = repositoryFingerprint()
    if (before == null) {
      verificationReady = false
      warn("the repository state could not be captured before the unit-test value review.")
      record(GoalAssessmentRun(GoalAssessmentRole.TEST_VALUE_REVIEW, null, GoalAssessmentRunStatus.FAILED))
      return
    }
    val launched = launch(GoalAssessmentFocus.TestValueReview(testPaths), "the unit-test value review")
    val after = repositoryFingerprint()
    val headAfter = currentHead()
    if (
      after == null || before != after || !reviewHeadMatches(headAfter)
    ) {
      verificationReady = false
      warn("the unit-test value review changed the repository or its read-only state could not be checked.")
      record(GoalAssessmentRun(GoalAssessmentRole.TEST_VALUE_REVIEW, null, GoalAssessmentRunStatus.FAILED))
      return
    }
    val parsed = (launched as? Launch.Value)?.let { GoalAssessmentLineParser.parseAssessor(it.text) }
    val report = (parsed as? GoalAssessmentParse.Parsed)?.report
    if (report == null) {
      verificationReady = false
      val reason = (parsed as? GoalAssessmentParse.Malformed)?.reason ?: "the review did not produce a report"
      warn("the unit-test value review was unavailable: $reason")
      record(GoalAssessmentRun(GoalAssessmentRole.TEST_VALUE_REVIEW, null, statusOf(launched, false)))
      return
    }
    val accepted = absorb(PlannedRun(GoalAssessmentRole.TEST_VALUE_REVIEW, null), report)
    record(GoalAssessmentRun(GoalAssessmentRole.TEST_VALUE_REVIEW, null, statusOf(launched, accepted)))
  }

  private fun reviewHeadMatches(head: String?): Boolean =
    bundle.headSha.equals(head, ignoreCase = true) && checkoutMatchesHead()

  private fun repositoryFingerprint(): String? =
    when (val result = services.gitOperations.repositoryFingerprint(request.repoRoot)) {
      is WorkflowGitOperationResult.Ok -> result.value.trim().takeIf(String::isNotEmpty)
      is WorkflowGitOperationResult.Failed -> null
    }

  private fun currentHead(): String? =
    (services.gitOperations.headCommitSha(request.repoRoot) as? WorkflowGitOperationResult.Ok)
      ?.value?.trim()?.takeIf(String::isNotEmpty)

  private fun checkoutMatchesHead(): Boolean {
    val head = currentHead()
    if (!bundle.headSha.equals(head, ignoreCase = true)) return false
    val current = services.gitOperations.repositoryTreeIdentity(request.repoRoot)
    val revision = services.gitOperations.revisionTreeIdentity(request.repoRoot, bundle.headSha)
    if (current !is WorkflowGitOperationResult.Ok || revision !is WorkflowGitOperationResult.Ok) return false
    val currentIdentity = current.value.trim()
    val revisionIdentity = revision.value.trim()
    return currentIdentity.isNotEmpty() && currentIdentity == revisionIdentity
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
    val selectedReport = report.copy(verdicts = report.verdicts.filter { it.findingRef in refs })
    val applied = GoalAssessmentVerdicts.apply(round.findings, selectedReport)
    val unsettled = applied.count { it.findingRef in refs && it.verification == GoalAssessmentVerification.UNVERIFIED }
    if (unsettled > 0) warn("$unsettled candidates stay unverified: the verifier gave no single verdict for them.")
    round = round.copy(findings = applied)
    earlier = earlier.map { it.copy(findings = GoalAssessmentVerdicts.applyCarried(it.findings, selectedReport)) }
  }

  private fun acceptReport(
    planned: PlannedRun,
    report: GoalAssessorReport,
  ): Boolean {
    if (roundNumber > 1) {
      val settled =
        report.scenarios.count { it.scenarioRef == previousTarget } +
          report.regressions.count { it.regressionRef == previousTarget }
      if (settled != 1) {
        verificationReady = false
        return false
      }
    }
    val coverageMissing =
      if (roundNumber == 1 && planned.role == GoalAssessmentRole.CROSS_SUBTASK) {
        missingDeclaredCoverage(report.scenarios, report.regressions)
      } else {
        emptyList()
      }
    if (roundNumber == 1 && planned.role == GoalAssessmentRole.CROSS_SUBTASK) {
      missingCoverage = coverageMissing
    }
    val accepted = absorb(planned, report)
    if (coverageMissing.isNotEmpty()) {
      verificationReady = false
      warn("the cross-subtask assessor omitted declared records: ${coverageMissing.joinToString(", ")}.")
      return false
    }
    if (!accepted) verificationReady = false
    return accepted
  }

  private fun absorb(
    planned: PlannedRun,
    report: GoalAssessorReport,
  ): Boolean {
    if (report.rejectedLines.isNotEmpty()) {
      warn("${planned.label()} left ${report.rejectedLines.size} lines outside the grammar.")
    }
    val used = nextRefNumber()
    val carried = GoalAssessmentScope.carriedOf(prior, roundNumber)
    val fresh =
      report.findings.filter { roundNumber == 1 }.filterNot { candidate ->
        GoalAssessmentScope.restates(carried, candidate.path, candidate.failureScenario) ||
          round.findings.any { finding ->
            finding.kind == candidate.kind && finding.path == candidate.path && finding.line == candidate.line &&
              finding.failureScenario == candidate.failureScenario && finding.guardedRef == candidate.guardedRef &&
              finding.subtaskId == planned.subtaskId
          }
      }
    val accepted = fresh.take(minOf(MAX_FINDINGS_PER_RUN, MAX_REF_NUMBER - used + 1).coerceAtLeast(0))
    if (accepted.size < fresh.size) {
      verificationReady = false
      warn("${planned.label()} exceeded the finding capacity; assessment is unavailable instead of dropping findings.")
      return false
    }
    val added =
      accepted.mapIndexed { index, candidate ->
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
          verification = GoalAssessmentVerification.UNVERIFIED,
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
    return true
  }

  private fun mergeScenarios(
    kept: List<GoalAssessmentScenarioResult>,
    reported: List<GoalAssessmentScenarioResult>,
  ): List<GoalAssessmentScenarioResult> {
    val declared = bundle.declared?.scenarios?.map { it.scenarioRef }.orEmpty().toSet()
    val known = reported.filter { it.scenarioRef in declared && (roundNumber == 1 || it.scenarioRef == previousTarget) }
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
    val known =
      reported.filter {
        it.regressionRef in declared && (roundNumber == 1 || it.regressionRef == previousTarget)
      }
    if (known.size < reported.size) warn("an assessor settled regressions the parent spec does not declare; ignored.")
    return (kept + known).groupBy { it.regressionRef }.values.map { results ->
      results.firstOrNull { it.state == GoalAssessmentRegressionState.NOT_RESTORED } ?: results.first()
    }
  }

  private fun nextRefNumber(): Int {
    val refs = (earlier.flatMap { it.findings } + round.findings).map { it.findingRef }
    return (refs.maxOfOrNull { it.removePrefix("GA-").toIntOrNull() ?: 0 } ?: 0) + 1
  }

  private fun record(run: GoalAssessmentRun) {
    round = round.copy(assessorRuns = round.assessorRuns + run)
    save()
  }

  private fun save() {
    round = round.copy(findings = GoalAssessmentVerdicts.withDispositions(round.findings))
    earlier = earlier.map { it.copy(findings = GoalAssessmentVerdicts.withDispositions(it.findings)) }
    persisted = services.persist(state, recordOf(null)) && persisted
  }

  private fun unavailable(reason: String): GoalAssessmentRoundResult.Unavailable {
    val record = recordOf(GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE)
    services.persist(state, record)
    warn("assessment unavailable: $reason.")
    return GoalAssessmentRoundResult.Unavailable(record, reason)
  }

  private fun recordOf(exitReason: GoalAssessmentExitReason?): GoalAssessmentRecord {
    val rounds = earlier + round
    val open = rounds.flatMap { it.findings }.filter { it.state == GoalAssessmentFindingState.OPEN }
    return prior.copy(rounds = rounds, exitReason = exitReason, openFindingRefs = open.map { it.findingRef })
  }

  private fun launch(
    focus: GoalAssessmentFocus,
    label: String,
  ): Launch {
    val phaseRequest =
      services.phaseRequest(
        state,
        request,
        SkeletonDefinition.GOAL_ASSESSMENT,
        PhaseInstructions(
          GoalAssessmentPromptRenderer.render(bundle, focus),
          setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT),
        ),
      )
    val branch = services.branchOf(state)
    val result =
      catchingNonCancellation { services.phaseRunEntry.runForGoal(phaseRequest, state.parentWorkflowId, branch) }
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
  ) = services.warn(message, cause)

  private sealed interface Launch {
    data class Value(val text: String) : Launch

    data object Failed : Launch
  }

  private data class PlannedRun(val role: GoalAssessmentRole, val subtaskId: Int?) {
    fun label(): String =
      when (role) {
        GoalAssessmentRole.TEST_VALUE_REVIEW -> "the unit-test value reviewer"
        GoalAssessmentRole.VERIFIER -> "the verifier"
        GoalAssessmentRole.SUBTASK -> "the subtask $subtaskId assessor"
        GoalAssessmentRole.CROSS_SUBTASK -> "the cross-subtask assessor"
      }
  }

  private companion object {
    val COMPLETED = GoalAssessmentRunStatus.COMPLETED
    const val MAX_FINDINGS_PER_RUN = 50
    const val MAX_REF_NUMBER = 999
  }
}
