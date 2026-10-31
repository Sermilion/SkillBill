package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.workflow.featuretask.GOAL_ASSESSMENT_RECORD_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidGoalAssessmentRecord
import skillbill.goalrunner.assessment.GoalAssessmentRecordShape
import skillbill.goalrunner.assessment.model.GoalAssessmentDisposition
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentFix
import skillbill.goalrunner.assessment.model.GoalAssessmentFixStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentFixValidation
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
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity
import skillbill.contracts.workflow.featuretask.GoalAssessmentRecordPayloadKeys as Keys

fun GoalAssessmentRecord.asGoalAssessmentRecordWireMap(): FeatureTaskRuntimeWorkflowArtifactMap {
  GoalAssessmentRecordShape.issues(this).firstOrNull()?.let { issue ->
    throw invalidGoalAssessmentRecord(ENCODE_LABEL, "${issue.fieldPath}: ${issue.reason}")
  }
  return FeatureTaskRuntimeWorkflowArtifactMap.from(
    mapOf(
      Keys.CONTRACT_VERSION to GOAL_ASSESSMENT_RECORD_CONTRACT_VERSION,
      Keys.ISSUE_KEY to issueKey,
      Keys.BUDGET_START_ROUND to budgetStartRound,
      Keys.ROUNDS to rounds.map(::roundWire),
      Keys.EXIT_REASON to exitReason?.wireValue,
      Keys.OPEN_FINDING_REFS to openFindingRefs,
    ),
  )
}

fun FeatureTaskRuntimeWorkflowArtifactMap.toGoalAssessmentRecord(sourceLabel: String): GoalAssessmentRecord =
  GoalAssessmentRecordDecoder(sourceLabel).decode(this)

private const val ENCODE_LABEL = "goal-assessment-record"

private fun roundWire(round: GoalAssessmentRound): Map<String, Any?> =
  mapOf(
    Keys.ROUND to round.round,
    Keys.TARGET_REF to round.targetRef,
    Keys.REPAIR_PLAN to round.repairPlan,
    Keys.HEAD_SHA to round.headSha,
    Keys.SCOPE_FILES to round.scopeFiles,
    Keys.ASSESSOR_RUNS to
      round.assessorRuns.map {
        mapOf(Keys.ROLE to it.role.wireValue, Keys.SUBTASK_ID to it.subtaskId, Keys.STATUS to it.status.wireValue)
      },
    Keys.FINDINGS to round.findings.map(::findingWire),
    Keys.SCENARIOS to
      round.scenarios.map {
        mapOf(Keys.SCENARIO_REF to it.scenarioRef, Keys.VERDICT to it.verdict.wireValue, Keys.EVIDENCE to it.evidence)
      },
    Keys.REGRESSIONS to
      round.regressions.map {
        mapOf(Keys.REGRESSION_REF to it.regressionRef, Keys.STATE to it.state.wireValue, Keys.EVIDENCE to it.evidence)
      },
    Keys.FIX to
      round.fix?.let {
        mapOf(
          Keys.STATUS to it.status.wireValue,
          Keys.CHANGED_FILES to it.changedFiles,
          Keys.COMMIT_SHA to it.commitSha,
          Keys.VALIDATION to it.validation.wireValue,
        )
      },
  )

private fun findingWire(finding: GoalAssessmentFinding): Map<String, Any?> =
  mapOf(
    Keys.FINDING_REF to finding.findingRef,
    Keys.KIND to finding.kind.wireValue,
    Keys.SEVERITY to finding.severity.wireValue,
    Keys.PATH to finding.path,
    Keys.LINE to finding.line,
    Keys.FAILURE_SCENARIO to finding.failureScenario,
    Keys.GUARDED_REF to finding.guardedRef,
    Keys.SUBTASK_ID to finding.subtaskId,
    Keys.ORIGIN_ROUND to finding.originRound,
    Keys.VERIFICATION to finding.verification.wireValue,
    Keys.VERIFICATION_EVIDENCE to finding.verificationEvidence,
    Keys.FIX_ROUNDS_SURVIVED to finding.fixRoundsSurvived,
    Keys.DISPOSITION to finding.disposition.wireValue,
    Keys.STATE to finding.state.wireValue,
  )

private class GoalAssessmentRecordDecoder(private val sourceLabel: String) {
  fun decode(raw: FeatureTaskRuntimeWorkflowArtifactMap): GoalAssessmentRecord {
    if (!raw.isObject) fail(ROOT, "must be an object.")
    val root = objectOf(raw, ROOT, TOP_LEVEL_KEYS)
    if (root[Keys.CONTRACT_VERSION] != GOAL_ASSESSMENT_RECORD_CONTRACT_VERSION) {
      fail(Keys.CONTRACT_VERSION, "must be $GOAL_ASSESSMENT_RECORD_CONTRACT_VERSION.")
    }
    val record =
      GoalAssessmentRecord(
        issueKey = text(root[Keys.ISSUE_KEY], Keys.ISSUE_KEY),
        budgetStartRound = integer(root[Keys.BUDGET_START_ROUND], Keys.BUDGET_START_ROUND),
        rounds = items(root[Keys.ROUNDS], Keys.ROUNDS, ::roundOf),
        exitReason =
          root[Keys.EXIT_REASON]?.let {
            choice(it, Keys.EXIT_REASON, GoalAssessmentExitReason.entries, GoalAssessmentExitReason::wireValue)
          },
        openFindingRefs = strings(root[Keys.OPEN_FINDING_REFS], Keys.OPEN_FINDING_REFS),
      )
    GoalAssessmentRecordShape.issues(record).firstOrNull()?.let { fail(it.fieldPath, it.reason) }
    return record
  }

  private fun roundOf(
    value: Any?,
    path: String,
  ): GoalAssessmentRound {
    val fields = objectOf(value, path, ROUND_KEYS)
    return GoalAssessmentRound(
      round = integer(fields[Keys.ROUND], "$path.${Keys.ROUND}"),
      targetRef = fields[Keys.TARGET_REF]?.let { text(it, "$path.${Keys.TARGET_REF}") },
      repairPlan = fields[Keys.REPAIR_PLAN]?.let { text(it, "$path.${Keys.REPAIR_PLAN}") },
      headSha = text(fields[Keys.HEAD_SHA], "$path.${Keys.HEAD_SHA}"),
      scopeFiles = strings(fields[Keys.SCOPE_FILES], "$path.${Keys.SCOPE_FILES}"),
      assessorRuns = items(fields[Keys.ASSESSOR_RUNS], "$path.${Keys.ASSESSOR_RUNS}", ::assessorRun),
      findings = items(fields[Keys.FINDINGS], "$path.${Keys.FINDINGS}", ::finding),
      scenarios = items(fields[Keys.SCENARIOS], "$path.${Keys.SCENARIOS}", ::scenario),
      regressions = items(fields[Keys.REGRESSIONS], "$path.${Keys.REGRESSIONS}", ::regression),
      fix = fields[Keys.FIX]?.let { fix(it, "$path.${Keys.FIX}") },
    )
  }

  private fun assessorRun(
    value: Any?,
    path: String,
  ): GoalAssessmentRun {
    val fields = objectOf(value, path, RUN_KEYS)
    return GoalAssessmentRun(
      role = choice(fields[Keys.ROLE], "$path.${Keys.ROLE}", GoalAssessmentRole.entries, GoalAssessmentRole::wireValue),
      subtaskId = fields[Keys.SUBTASK_ID]?.let { integer(it, "$path.${Keys.SUBTASK_ID}") },
      status =
        choice(
          fields[Keys.STATUS],
          "$path.${Keys.STATUS}",
          GoalAssessmentRunStatus.entries,
          GoalAssessmentRunStatus::wireValue,
        ),
    )
  }

  private fun finding(
    value: Any?,
    path: String,
  ): GoalAssessmentFinding {
    val fields = objectOf(value, path, FINDING_KEYS)

    fun at(key: String) = "$path.$key"
    return GoalAssessmentFinding(
      findingRef = text(fields[Keys.FINDING_REF], at(Keys.FINDING_REF)),
      kind =
        choice(
          fields[Keys.KIND],
          at(Keys.KIND),
          GoalAssessmentFindingKind.entries,
          GoalAssessmentFindingKind::wireValue,
        ),
      severity =
        choice(
          fields[Keys.SEVERITY],
          at(Keys.SEVERITY),
          FeatureTaskRuntimeReviewSeverity.entries,
          FeatureTaskRuntimeReviewSeverity::wireValue,
        ),
      path = text(fields[Keys.PATH], at(Keys.PATH)),
      line = integer(fields[Keys.LINE], at(Keys.LINE)),
      failureScenario = text(fields[Keys.FAILURE_SCENARIO], at(Keys.FAILURE_SCENARIO)),
      guardedRef = fields[Keys.GUARDED_REF]?.let { text(it, at(Keys.GUARDED_REF)) },
      subtaskId = fields[Keys.SUBTASK_ID]?.let { integer(it, at(Keys.SUBTASK_ID)) },
      originRound = integer(fields[Keys.ORIGIN_ROUND], at(Keys.ORIGIN_ROUND)),
      verification =
        choice(
          fields[Keys.VERIFICATION],
          at(Keys.VERIFICATION),
          GoalAssessmentVerification.entries,
          GoalAssessmentVerification::wireValue,
        ),
      verificationEvidence = text(fields[Keys.VERIFICATION_EVIDENCE], at(Keys.VERIFICATION_EVIDENCE)),
      fixRoundsSurvived = integer(fields[Keys.FIX_ROUNDS_SURVIVED], at(Keys.FIX_ROUNDS_SURVIVED)),
      disposition =
        choice(
          fields[Keys.DISPOSITION],
          at(Keys.DISPOSITION),
          GoalAssessmentDisposition.entries,
          GoalAssessmentDisposition::wireValue,
        ),
      state =
        choice(
          fields[Keys.STATE],
          at(Keys.STATE),
          GoalAssessmentFindingState.entries,
          GoalAssessmentFindingState::wireValue,
        ),
    )
  }

  private fun scenario(
    value: Any?,
    path: String,
  ): GoalAssessmentScenarioResult {
    val fields = objectOf(value, path, SCENARIO_KEYS)
    return GoalAssessmentScenarioResult(
      scenarioRef = text(fields[Keys.SCENARIO_REF], "$path.${Keys.SCENARIO_REF}"),
      verdict =
        choice(
          fields[Keys.VERDICT],
          "$path.${Keys.VERDICT}",
          GoalAssessmentScenarioVerdict.entries,
          GoalAssessmentScenarioVerdict::wireValue,
        ),
      evidence = text(fields[Keys.EVIDENCE], "$path.${Keys.EVIDENCE}"),
    )
  }

  private fun regression(
    value: Any?,
    path: String,
  ): GoalAssessmentRegressionResult {
    val fields = objectOf(value, path, REGRESSION_KEYS)
    return GoalAssessmentRegressionResult(
      regressionRef = text(fields[Keys.REGRESSION_REF], "$path.${Keys.REGRESSION_REF}"),
      state =
        choice(
          fields[Keys.STATE],
          "$path.${Keys.STATE}",
          GoalAssessmentRegressionState.entries,
          GoalAssessmentRegressionState::wireValue,
        ),
      evidence = text(fields[Keys.EVIDENCE], "$path.${Keys.EVIDENCE}"),
    )
  }

  private fun fix(
    value: Any?,
    path: String,
  ): GoalAssessmentFix {
    val fields = objectOf(value, path, FIX_KEYS)
    return GoalAssessmentFix(
      status =
        choice(
          fields[Keys.STATUS],
          "$path.${Keys.STATUS}",
          GoalAssessmentFixStatus.entries,
          GoalAssessmentFixStatus::wireValue,
        ),
      changedFiles = strings(fields[Keys.CHANGED_FILES], "$path.${Keys.CHANGED_FILES}"),
      commitSha = fields[Keys.COMMIT_SHA]?.let { text(it, "$path.${Keys.COMMIT_SHA}") },
      validation =
        choice(
          fields[Keys.VALIDATION],
          "$path.${Keys.VALIDATION}",
          GoalAssessmentFixValidation.entries,
          GoalAssessmentFixValidation::wireValue,
        ),
    )
  }

  private fun objectOf(
    value: Any?,
    path: String,
    keys: Set<String>,
  ): Map<*, *> {
    val map = value as? Map<*, *> ?: fail(path, "must be an object.")
    val unknown = map.keys.filterNot { it is String && it in keys }
    val missing = keys.filterNot { map.containsKey(it) }
    if (unknown.isNotEmpty() || missing.isNotEmpty()) {
      fail(path, "has unknown fields $unknown or missing fields $missing.")
    }
    return map
  }

  private fun <T> items(
    value: Any?,
    path: String,
    decode: (Any?, String) -> T,
  ): List<T> = list(value, path).mapIndexed { index, item -> decode(item, "$path[$index]") }

  private fun list(
    value: Any?,
    path: String,
  ): List<*> = value as? List<*> ?: fail(path, "must be an array.")

  private fun strings(
    value: Any?,
    path: String,
  ): List<String> = list(value, path).map { text(it, path) }

  private fun text(
    value: Any?,
    path: String,
  ): String = value as? String ?: fail(path, "must be a string.")

  private fun integer(
    value: Any?,
    path: String,
  ): Int = (value as? Number)?.toExactInt() ?: fail(path, "must be an integer.")

  private fun <E> choice(
    value: Any?,
    path: String,
    entries: Collection<E>,
    wire: (E) -> String,
  ): E {
    val token = text(value, path)
    return entries.firstOrNull { wire(it) == token } ?: fail(path, "must be one of ${entries.map(wire)}.")
  }

  private fun fail(
    path: String,
    reason: String,
  ): Nothing = throw invalidGoalAssessmentRecord(sourceLabel, "$path: $reason")

  private companion object {
    const val ROOT = "<root>"
    val TOP_LEVEL_KEYS =
      setOf(
        Keys.CONTRACT_VERSION,
        Keys.ISSUE_KEY,
        Keys.ROUNDS,
        Keys.EXIT_REASON,
        Keys.OPEN_FINDING_REFS,
        Keys.BUDGET_START_ROUND,
      )
    val ROUND_KEYS =
      setOf(
        Keys.ROUND,
        Keys.TARGET_REF,
        Keys.REPAIR_PLAN,
        Keys.HEAD_SHA,
        Keys.SCOPE_FILES,
        Keys.ASSESSOR_RUNS,
        Keys.FINDINGS,
        Keys.SCENARIOS,
        Keys.REGRESSIONS,
        Keys.FIX,
      )
    val RUN_KEYS = setOf(Keys.ROLE, Keys.SUBTASK_ID, Keys.STATUS)
    val FINDING_KEYS =
      setOf(
        Keys.FINDING_REF,
        Keys.KIND,
        Keys.SEVERITY,
        Keys.PATH,
        Keys.LINE,
        Keys.FAILURE_SCENARIO,
        Keys.GUARDED_REF,
        Keys.SUBTASK_ID,
        Keys.ORIGIN_ROUND,
        Keys.VERIFICATION,
        Keys.VERIFICATION_EVIDENCE,
        Keys.FIX_ROUNDS_SURVIVED,
        Keys.DISPOSITION,
        Keys.STATE,
      )
    val SCENARIO_KEYS = setOf(Keys.SCENARIO_REF, Keys.VERDICT, Keys.EVIDENCE)
    val REGRESSION_KEYS = setOf(Keys.REGRESSION_REF, Keys.STATE, Keys.EVIDENCE)
    val FIX_KEYS = setOf(Keys.STATUS, Keys.CHANGED_FILES, Keys.COMMIT_SHA, Keys.VALIDATION)
  }
}
