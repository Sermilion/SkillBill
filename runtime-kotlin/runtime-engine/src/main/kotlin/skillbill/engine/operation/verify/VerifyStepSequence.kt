package skillbill.engine.operation.verify

import skillbill.application.telemetry.model.FeatureVerifyFinishedRequest
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.slot.codereview.InlineReviewEnvelope
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationStepResult
import skillbill.engine.operation.unittestvalue.UnitTestPathClassifier
import skillbill.engine.operation.unittestvalue.UnitTestValueCheckPromptRules
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal data class VerifyTarget(
  val label: String,
  val baseRevision: String,
  val headRevision: String,
) {
  val comparisonScope: String get() = "$baseRevision..$headRevision"
}

internal data class VerifyRun(
  val context: OperationContext,
  val workflowId: String,
  val sessionId: String,
  val intake: String,
  val target: VerifyTarget,
  val mode: VerifyReviewMode,
  val criteria: VerifyCriteria,
  val startedAt: Instant,
)

internal class VerifyStepSequence(
  private val store: VerifyWorkflowStore,
  private val gitOperations: WorkflowGitOperations,
  private val telemetry: VerifyTelemetry,
  private val codeReview: VerifyCodeReviewStep,
  private val budget: VerifyBudget,
  private val clock: Clock,
) {
  fun run(
    run: VerifyRun,
    start: String,
    attempts: Map<String, Int>,
    settledBefore: List<Map<String, Any?>> = emptyList(),
  ): OperationOutcome {
    var stepId = start
    var attempt = (attempts[start] ?: 0) + 1
    val begun =
      store.begin(
        run.workflowId,
        start,
        settledBefore + stepEntry(start, WorkflowStepStatus.RUNNING, attempt),
        run.sessionId,
      )
    var prior =
      when (begun) {
        is VerifyWrite.Ok -> begun.priorValues
        is VerifyWrite.Rejected -> return fail(run, start, attempt, begun.error)
      }
    var report: String? = null
    while (stepId != VerifyWorkflow.FINISH) {
      val done =
        when (val result = execute(run, stepId, prior)) {
          is VerifyStepDone.Stopped -> return stopped(run, stepId, attempt, result)
          is VerifyStepDone.Settled -> result
        }
      done.report?.let { report = it }
      val next = VerifyWorkflow.CONFIRMED_STEPS[VerifyWorkflow.CONFIRMED_STEPS.indexOf(stepId) + 1]
      val nextAttempt = (attempts[next] ?: 0) + 1
      val written =
        store.write(
          run.workflowId,
          WorkflowStatus.RUNNING,
          next,
          listOf(stepEntry(stepId, done.status, attempt), stepEntry(next, WorkflowStepStatus.RUNNING, nextAttempt)),
          done.artifacts,
        )
      prior =
        when (written) {
          is VerifyWrite.Ok -> written.priorValues
          is VerifyWrite.Rejected -> return fail(run, stepId, attempt, written.error)
        }
      stepId = next
      attempt = nextAttempt
    }
    return finish(run, attempt, report)
  }

  private fun stopped(
    run: VerifyRun,
    stepId: String,
    attempt: Int,
    result: VerifyStepDone.Stopped,
  ): OperationOutcome =
    when (result) {
      is VerifyStepDone.Failed -> fail(run, stepId, attempt, result.reason)
      is VerifyStepDone.Refused -> result.refusal
    }

  fun diffProjection(
    context: OperationContext,
    target: VerifyTarget,
    workflowId: String,
  ): VerifyDiffProjection {
    val fingerprint =
      when (val result = gitOperations.repositoryFingerprint(context.repoRoot)) {
        is WorkflowGitOperationResult.Ok -> result.value.orEmpty()
        else -> return VerifyDiffProjection.Unavailable(result.error)
      }
    val changed =
      when (val listing = changedFiles(context, target)) {
        is WorkflowGitNameListResult.Listed -> listing.names
        is WorkflowGitNameListResult.Failed -> return VerifyDiffProjection.Unavailable(listing.error)
      }
    return VerifyDiffProjection.Ready(
      mapOf(
        VerifyWorkflow.CHECKPOINT to fingerprint,
        VerifyWorkflow.COMPARISON_SCOPE to target.comparisonScope,
        VerifyWorkflow.CHANGED_FILES to budget.paths(changed, workflowId),
      ),
    )
  }

  private fun execute(
    run: VerifyRun,
    stepId: String,
    prior: Map<String, String>,
  ): VerifyStepDone =
    when (stepId) {
      VerifyWorkflow.GATHER_DIFF -> gatherDiff(run)
      VerifyWorkflow.FEATURE_FLAG_AUDIT -> featureFlagAudit(run, prior)
      VerifyWorkflow.CODE_REVIEW -> codeReview(run, prior)
      VerifyWorkflow.UNIT_TEST_VALUE_CHECK -> unitTestValueCheck(run, prior)
      VerifyWorkflow.COMPLETENESS_AUDIT -> completenessAudit(run, prior)
      VerifyWorkflow.VERDICT -> verdict(run, prior)
      else -> VerifyStepDone.Failed("Verify has no step '$stepId'.")
    }

  private fun gatherDiff(run: VerifyRun): VerifyStepDone =
    when (val projection = diffProjection(run.context, run.target, run.workflowId)) {
      is VerifyDiffProjection.Unavailable -> VerifyStepDone.Failed(projection.reason)
      is VerifyDiffProjection.Ready ->
        VerifyStepDone.Settled(
          WorkflowStepStatus.COMPLETED,
          mapOf(
            VerifyWorkflow.DIFF_PROJECTION to projection.artifact,
            VerifyWorkflow.FEATURE_FLAG_POLICY to VerifyWorkflow.policy(VerifyPromptSections.FEATURE_FLAG_AUDIT),
            VerifyWorkflow.REVIEW_RUBRIC to VerifyWorkflow.policy(VerifyPromptSections.REVIEW_RUBRIC),
            VerifyWorkflow.UNIT_TEST_VALUE_RUBRIC to VerifyWorkflow.policy(UnitTestValueCheckPromptRules.RULES),
            VerifyWorkflow.COMPLETENESS_RUBRIC to VerifyWorkflow.policy(VerifyPromptSections.COMPLETENESS_AUDIT),
          ),
        )
    }

  private fun featureFlagAudit(
    run: VerifyRun,
    prior: Map<String, String>,
  ): VerifyStepDone {
    val directive = VerifyPromptSections.featureFlagAuditDirective(run.target.comparisonScope)
    val value = readOnly(run, VerifyPromptSections.FEATURE_FLAG_AUDIT_STEP, directive, prior) { return it }
    val first = value.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
    val receiptKey = VerifyWorkflow.FEATURE_FLAG_AUDIT_RECEIPT
    if (first.startsWith(VerifyPromptSections.SKIPPED_PREFIX)) {
      val reason = first.removePrefix(VerifyPromptSections.SKIPPED_PREFIX).trim()
      return settled(receiptKey, VerifyWorkflow.SKIPPED, listOf(reason), run, WorkflowStepStatus.SKIPPED)
    }
    val verdict = if (FLAG_CHECK_FAILED.containsMatchIn(value)) "fail" else "pass"
    return settled(receiptKey, verdict, value.lines(), run)
  }

  private fun codeReview(
    run: VerifyRun,
    prior: Map<String, String>,
  ): VerifyStepDone {
    val target = run.target
    return when (
      val outcome = codeReview.run(run.context, run.mode, target.baseRevision, target.headRevision, prior)
    ) {
      is VerifyCodeReviewOutcome.Failed -> VerifyStepDone.Failed(outcome.reason)
      is VerifyCodeReviewOutcome.Refused -> VerifyStepDone.Refused(outcome.refusal)
      is VerifyCodeReviewOutcome.Reviewed -> {
        val register = outcome.review.findings.map(::registerLine)
        telemetry.reviewImported(importText(run, register))
        settled(VerifyWorkflow.CODE_REVIEW_RECEIPT, outcome.review.verdict.wireValue, register, run)
      }
    }
  }

  private fun unitTestValueCheck(
    run: VerifyRun,
    prior: Map<String, String>,
  ): VerifyStepDone {
    val tests = unitTestsInScope(run) { return it }
    val receiptKey = VerifyWorkflow.UNIT_TEST_VALUE_RECEIPT
    if (tests.isEmpty()) {
      val reason = "No unit tests changed in ${run.target.comparisonScope}."
      return settled(receiptKey, VerifyWorkflow.SKIPPED, listOf(reason), run, WorkflowStepStatus.SKIPPED)
    }
    val directive = UnitTestValueCheckPromptRules.reviewDirective(run.target.comparisonScope, tests)
    val value = readOnly(run, UnitTestValueCheckPromptRules.REVIEW_STEP, directive, prior) { return it }
    return settled(receiptKey, REPORTED, value.lines(), run)
  }

  private inline fun unitTestsInScope(
    run: VerifyRun,
    onFailure: (VerifyStepDone) -> Nothing,
  ): List<String> {
    val changed =
      when (val listing = changedFiles(run.context, run.target)) {
        is WorkflowGitNameListResult.Listed -> listing.names
        is WorkflowGitNameListResult.Failed -> onFailure(VerifyStepDone.Failed(listing.error))
      }
    val candidates = changed.filter(UnitTestPathClassifier::isUnitTest).distinct().sorted()
    return when (val present = gitOperations.pathContentIdentities(run.context.repoRoot, candidates)) {
      is WorkflowPathContentIdentitiesResult.Resolved -> candidates.filter(present.identities::containsKey)
      is WorkflowPathContentIdentitiesResult.Failed -> onFailure(VerifyStepDone.Failed(present.error))
    }
  }

  private fun completenessAudit(
    run: VerifyRun,
    prior: Map<String, String>,
  ): VerifyStepDone {
    val directive = VerifyPromptSections.completenessAuditDirective(run.target.comparisonScope)
    val value = readOnly(run, VerifyPromptSections.COMPLETENESS_AUDIT_STEP, directive, prior) { return it }
    val verdict = if (value.lines().any(CRITERION_GAP::containsMatchIn)) HAD_GAPS else ALL_PASS
    return settled(VerifyWorkflow.COMPLETENESS_AUDIT_RECEIPT, verdict, value.lines(), run)
  }

  private fun verdict(
    run: VerifyRun,
    prior: Map<String, String>,
  ): VerifyStepDone {
    val report =
      readOnly(run, VerifyPromptSections.VERDICT_STEP, VerifyPromptSections.verdictDirective(), prior) {
        return it
      }
    val section = report.substringAfter(VERDICT_HEADING, missingDelimiterValue = report)
    val verdict = VERDICTS.firstOrNull { candidate -> section.contains(candidate) } ?: UNKNOWN_VERDICT
    val findings = budget.lines(reportLines(section.lines()), VerifyWorkflow.VERDICT_RESULT, run.workflowId)
    return VerifyStepDone.Settled(
      WorkflowStepStatus.COMPLETED,
      mapOf(VerifyWorkflow.VERDICT_RESULT to VerifyWorkflow.receipt(verdict, findings)),
      report,
    )
  }

  private fun finish(
    run: VerifyRun,
    attempt: Int,
    report: String?,
  ): OperationOutcome {
    val closed =
      store.write(
        run.workflowId,
        WorkflowStatus.COMPLETED,
        VerifyWorkflow.FINISH,
        listOf(stepEntry(VerifyWorkflow.FINISH, WorkflowStepStatus.COMPLETED, attempt)),
      )
    if (closed is VerifyWrite.Rejected) return fail(run, VerifyWorkflow.FINISH, attempt, closed.error)
    val artifacts = store.artifacts(run.workflowId)
    finished(run, COMPLETED_STATUS, artifacts)
    val text = report ?: storedVerdict(artifacts)
    return OperationOutcome.Completed("${text.trimEnd()}\n\nVerify workflow: ${run.workflowId}\n")
  }

  private fun fail(
    run: VerifyRun,
    stepId: String,
    attempt: Int,
    reason: String,
  ): OperationOutcome {
    val steps = listOf(stepEntry(stepId, WorkflowStepStatus.FAILED, attempt))
    val failed = store.write(run.workflowId, WorkflowStatus.FAILED, stepId, steps)
    if (failed is VerifyWrite.Rejected) {
      (diffProjection(run.context, run.target, run.workflowId) as? VerifyDiffProjection.Ready)?.let { refreshed ->
        store.write(
          run.workflowId,
          WorkflowStatus.FAILED,
          stepId,
          steps,
          mapOf(VerifyWorkflow.DIFF_PROJECTION to refreshed.artifact),
        )
      }
    }
    val status =
      when (stepId) {
        in REVIEW_STAGE -> ABANDONED_AT_REVIEW
        VerifyWorkflow.COMPLETENESS_AUDIT -> ABANDONED_AT_AUDIT
        else -> ERROR_STATUS
      }
    finished(run, status, store.artifacts(run.workflowId))
    return OperationOutcome.Failed("Verify step '$stepId' failed: $reason\nVerify workflow: ${run.workflowId}")
  }

  private fun finished(
    run: VerifyRun,
    completionStatus: String,
    artifacts: Map<String, Any?>,
  ) {
    if (run.sessionId.isBlank()) return
    val flagAudit =
      VerifyWorkflow.string(artifacts[VerifyWorkflow.FEATURE_FLAG_AUDIT_RECEIPT], VerifyWorkflow.VERDICT_FIELD)
    val completeness = artifacts[VerifyWorkflow.COMPLETENESS_AUDIT_RECEIPT]
    val auditResult = VerifyWorkflow.string(completeness, VerifyWorkflow.VERDICT_FIELD)
    val gaps = VerifyWorkflow.strings(completeness, VerifyWorkflow.FINDINGS).filter(CRITERION_GAP::containsMatchIn)
    telemetry.finished(
      FeatureVerifyFinishedRequest(
        featureFlagAuditPerformed = flagAudit != null && flagAudit != VerifyWorkflow.SKIPPED,
        reviewIterations = if (artifacts.containsKey(VerifyWorkflow.CODE_REVIEW_RECEIPT)) 1 else 0,
        auditResult = auditResult?.takeIf { it == ALL_PASS || it == HAD_GAPS } ?: VerifyWorkflow.SKIPPED,
        completionStatus = completionStatus,
        historyRelevance = NO_HISTORY,
        historyHelpfulness = NO_HISTORY,
        sessionId = run.sessionId,
        gapsFound = gaps,
        orchestrated = false,
        acceptanceCriteriaCount = run.criteria.acceptanceCriteriaCount,
        rolloutRelevant = run.criteria.rolloutRelevant,
        specSummary = run.intake,
        durationSeconds = Duration.between(run.startedAt, clock.instant()).seconds.toInt(),
      ),
    )
  }

  private inline fun readOnly(
    run: VerifyRun,
    stepName: String,
    directive: String,
    prior: Map<String, String>,
    onFailure: (VerifyStepDone.Stopped) -> Nothing,
  ): String =
    when (val step = run.context.steps.runReadOnly(run.context, stepName, directive, prior)) {
      is OperationStepResult.Failed -> onFailure(VerifyStepDone.Failed(step.reason))
      is OperationStepResult.Refused -> onFailure(VerifyStepDone.Refused(step.refusal))
      is OperationStepResult.Settled -> step.value
    }

  private fun settled(
    receiptKey: String,
    verdict: String,
    lines: List<String>,
    run: VerifyRun,
    status: WorkflowStepStatus = WorkflowStepStatus.COMPLETED,
  ): VerifyStepDone.Settled {
    val findings = budget.lines(reportLines(lines), receiptKey, run.workflowId)
    return VerifyStepDone.Settled(status, mapOf(receiptKey to VerifyWorkflow.receipt(verdict, findings)))
  }

  private fun changedFiles(
    context: OperationContext,
    target: VerifyTarget,
  ): WorkflowGitNameListResult =
    gitOperations.runtimePhaseChangedPathsBetweenCommits(context.repoRoot, target.baseRevision, target.headRevision)

  private fun importText(
    run: VerifyRun,
    register: List<String>,
  ): String =
    buildString {
      appendLine("Review run ID: ${InlineReviewEnvelope.mintReviewRunId(clock)}")
      appendLine("Review session ID: ${run.sessionId.ifBlank { run.workflowId }}")
      appendLine("Execution mode: ${run.mode.wireValue}")
      appendLine()
      register.forEach(::appendLine)
    }

  private fun registerLine(finding: ParallelReviewMergedFinding): String {
    val severity =
      if (finding.severity == ParallelReviewSeverity.NIT) ParallelReviewSeverity.MINOR else finding.severity
    return "- [${finding.fNumber}] ${severity.displayName} | ${finding.confidence} | ${finding.location} | " +
      finding.description
  }

  private fun storedVerdict(artifacts: Map<String, Any?>): String {
    val result = artifacts[VerifyWorkflow.VERDICT_RESULT]
    val verdict = VerifyWorkflow.string(result, VerifyWorkflow.VERDICT_FIELD) ?: UNKNOWN_VERDICT
    return (listOf("VERDICT: $verdict") + VerifyWorkflow.strings(result, VerifyWorkflow.FINDINGS)).joinToString("\n")
  }

  private companion object {
    const val REPORTED = "reported"
    const val ALL_PASS = "all_pass"
    const val HAD_GAPS = "had_gaps"
    const val UNKNOWN_VERDICT = "unknown"
    const val VERDICT_HEADING = "--- VERDICT ---"
    const val NO_HISTORY = "none"
    const val COMPLETED_STATUS = "completed"
    const val ABANDONED_AT_REVIEW = "abandoned_at_review"
    const val ABANDONED_AT_AUDIT = "abandoned_at_audit"
    const val ERROR_STATUS = "error"

    val VERDICTS = listOf("REQUEST CHANGES", "APPROVE WITH FIXES", "APPROVE")
    val REVIEW_STAGE =
      setOf(
        VerifyWorkflow.GATHER_DIFF,
        VerifyWorkflow.FEATURE_FLAG_AUDIT,
        VerifyWorkflow.CODE_REVIEW,
        VerifyWorkflow.UNIT_TEST_VALUE_CHECK,
      )
    val FLAG_CHECK_FAILED = Regex("""\[\s*FAIL\s*]""")
    val CRITERION_GAP = Regex("""^\s*\[(FAIL|PARTIAL)]""")

    fun reportLines(lines: List<String>): List<String> = lines.filterNot { line -> line.trim().startsWith("```") }
  }
}

internal fun stepEntry(
  stepId: String,
  status: WorkflowStepStatus,
  attempt: Int,
): Map<String, Any?> = mapOf("step_id" to stepId, "status" to status.wireValue, "attempt_count" to attempt)

internal fun priorValuesOf(artifacts: Map<String, Any?>?): Map<String, String> =
  artifacts.orEmpty().mapValues { (_, value) -> JsonCodec.valueToJsonString(value) }

internal sealed interface VerifyStepDone {
  data class Settled(
    val status: WorkflowStepStatus,
    val artifacts: Map<String, Any?>,
    val report: String? = null,
  ) : VerifyStepDone

  sealed interface Stopped : VerifyStepDone

  data class Failed(val reason: String) : Stopped

  /** An operation anchor was unreadable; the run reports the refusal without recording a failed workflow step. */
  data class Refused(val refusal: OperationOutcome.Blocked) : Stopped
}

internal sealed interface VerifyDiffProjection {
  data class Ready(val artifact: Map<String, Any?>) : VerifyDiffProjection

  data class Unavailable(val reason: String) : VerifyDiffProjection
}
