package skillbill.goalrunner.assessment

import skillbill.goalrunner.assessment.model.GoalAssessmentDisposition
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRecordIssue
import skillbill.goalrunner.assessment.model.GoalAssessmentRole
import skillbill.goalrunner.assessment.model.GoalAssessmentRound
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity
import skillbill.contracts.workflow.featuretask.GoalAssessmentRecordPayloadKeys as Keys

object GoalAssessmentRecordShape {
  fun issues(record: GoalAssessmentRecord): List<GoalAssessmentRecordIssue> =
    buildList {
      if (record.issueKey.isBlank()) add(GoalAssessmentRecordIssue(Keys.ISSUE_KEY, "must not be blank."))
      if (record.rounds.size > GoalAssessmentRecord.MAX_ROUNDS) {
        add(GoalAssessmentRecordIssue(Keys.ROUNDS, "at most ${GoalAssessmentRecord.MAX_ROUNDS} rounds are allowed."))
      }
      record.rounds.forEachIndexed { index, round -> addAll(roundIssues("${Keys.ROUNDS}[$index]", index + 1, round)) }
      addAll(duplicateFindingRefIssues(record))
      addAll(openRefIssues(record))
    }

  private fun roundIssues(
    path: String,
    expectedRound: Int,
    round: GoalAssessmentRound,
  ): List<GoalAssessmentRecordIssue> =
    buildList {
      if (round.round != expectedRound) {
        add(GoalAssessmentRecordIssue("$path.${Keys.ROUND}", "must be $expectedRound in round order."))
      }
      if (!HEAD_SHA.matches(round.headSha)) {
        add(GoalAssessmentRecordIssue("$path.${Keys.HEAD_SHA}", "must be a hexadecimal commit sha."))
      }
      if (round.scopeFiles.any(String::isBlank)) {
        add(GoalAssessmentRecordIssue("$path.${Keys.SCOPE_FILES}", "must not hold blank paths."))
      }
      addAll(runIssues(path, round))
      round.findings.forEachIndexed { index, finding ->
        addAll(findingIssues("$path.${Keys.FINDINGS}[$index]", expectedRound, finding))
      }
      addAll(resultRefIssues(path, round))
      round.fix?.let { fix ->
        if (fix.commitSha != null && !HEAD_SHA.matches(fix.commitSha)) {
          add(GoalAssessmentRecordIssue("$path.${Keys.FIX}.${Keys.COMMIT_SHA}", "must be a hexadecimal commit sha."))
        }
        if (fix.changedFiles.any(String::isBlank)) {
          add(GoalAssessmentRecordIssue("$path.${Keys.FIX}.${Keys.CHANGED_FILES}", "must not hold blank paths."))
        }
      }
    }

  private fun runIssues(
    path: String,
    round: GoalAssessmentRound,
  ): List<GoalAssessmentRecordIssue> =
    buildList {
      round.assessorRuns.forEachIndexed { index, run ->
        val runPath = "$path.${Keys.ASSESSOR_RUNS}[$index]"
        val subtaskRun = run.role == GoalAssessmentRole.SUBTASK
        if (subtaskRun != (run.subtaskId != null) || (run.subtaskId ?: 1) < 1) {
          add(GoalAssessmentRecordIssue("$runPath.${Keys.SUBTASK_ID}", "must be a positive id for subtask runs only."))
        }
      }
      if (round.assessorRuns.map { it.role to it.subtaskId }.let { it.distinct().size != it.size }) {
        add(GoalAssessmentRecordIssue("$path.${Keys.ASSESSOR_RUNS}", "must not repeat a role and subtask id."))
      }
    }

  private fun findingIssues(
    path: String,
    roundNumber: Int,
    finding: GoalAssessmentFinding,
  ): List<GoalAssessmentRecordIssue> =
    findingIdentityIssues(
      path,
      finding,
    ) + findingLifecycleIssues(path, roundNumber, finding)

  private fun findingIdentityIssues(
    path: String,
    finding: GoalAssessmentFinding,
  ): List<GoalAssessmentRecordIssue> =
    buildList {
      fun issue(
        key: String,
        reason: String,
      ) = add(GoalAssessmentRecordIssue("$path.$key", reason))
      if (!FINDING_REF.matches(finding.findingRef)) issue(Keys.FINDING_REF, "must match GA-NNN.")
      if (finding.severity == FeatureTaskRuntimeReviewSeverity.NIT) {
        issue(Keys.SEVERITY, "must be blocker, major or minor.")
      }
      if (finding.path.isBlank()) issue(Keys.PATH, "must not be blank.")
      if (finding.line < 1) issue(Keys.LINE, "must be a positive line.")
      if (finding.failureScenario.isBlank()) issue(Keys.FAILURE_SCENARIO, "must not be blank.")
      val testValue = finding.kind == GoalAssessmentFindingKind.TEST_VALUE
      if (testValue != (finding.guardedRef != null) || finding.guardedRef?.isBlank() == true) {
        issue(Keys.GUARDED_REF, "must name the guarded path and line for test-value findings only.")
      }
    }

  private fun findingLifecycleIssues(
    path: String,
    roundNumber: Int,
    finding: GoalAssessmentFinding,
  ): List<GoalAssessmentRecordIssue> =
    buildList {
      fun issue(
        key: String,
        reason: String,
      ) = add(GoalAssessmentRecordIssue("$path.$key", reason))
      if ((finding.subtaskId ?: 1) < 1) issue(Keys.SUBTASK_ID, "must be a positive subtask id or null.")
      if (finding.originRound !in 1..roundNumber) issue(Keys.ORIGIN_ROUND, "must be a recorded round up to this one.")
      if (finding.fixRoundsSurvived < 0) issue(Keys.FIX_ROUNDS_SURVIVED, "must not be negative.")
      val needsVerdict = finding.kind == GoalAssessmentFindingKind.DEFECT && finding.severity.blocksAdvance
      if (needsVerdict == (finding.verification == GoalAssessmentVerification.NOT_REQUIRED)) {
        issue(Keys.VERIFICATION, "must be not_required exactly for minor and test-value findings.")
      }
      val refuted = finding.verification == GoalAssessmentVerification.REFUTED
      if (refuted != (finding.state == GoalAssessmentFindingState.REFUTED)) {
        issue(Keys.STATE, "must be refuted exactly when verification is refuted.")
      }
      if (refuted && finding.disposition != GoalAssessmentDisposition.PR_ONLY) {
        issue(Keys.DISPOSITION, "must be pr_only for a refuted finding.")
      }
    }

  private fun resultRefIssues(
    path: String,
    round: GoalAssessmentRound,
  ): List<GoalAssessmentRecordIssue> =
    buildList {
      round.scenarios.map { it.scenarioRef }.let { refs ->
        if (refs.any { !SCENARIO_REF.matches(it) } || refs.distinct().size != refs.size) {
          add(GoalAssessmentRecordIssue("$path.${Keys.SCENARIOS}", "refs must be unique IS-NNN labels."))
        }
      }
      round.regressions.map { it.regressionRef }.let { refs ->
        if (refs.any { !REGRESSION_REF.matches(it) } || refs.distinct().size != refs.size) {
          add(GoalAssessmentRecordIssue("$path.${Keys.REGRESSIONS}", "refs must be unique TR-NNN labels."))
        }
      }
    }

  private fun duplicateFindingRefIssues(record: GoalAssessmentRecord): List<GoalAssessmentRecordIssue> {
    val refs = record.rounds.flatMap { round -> round.findings.map { it.findingRef } }
    val duplicate = refs.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.firstOrNull()
    return listOfNotNull(
      duplicate?.let { GoalAssessmentRecordIssue(Keys.ROUNDS, "finding ref $it repeats across the record.") },
    )
  }

  private fun openRefIssues(record: GoalAssessmentRecord): List<GoalAssessmentRecordIssue> {
    val openRefs =
      record.rounds.flatMap { it.findings }.filter { it.state == GoalAssessmentFindingState.OPEN }
        .map { it.findingRef }.toSet()
    return buildList {
      if (record.openFindingRefs.distinct().size != record.openFindingRefs.size) {
        add(GoalAssessmentRecordIssue(Keys.OPEN_FINDING_REFS, "must not repeat a ref."))
      }
      record.openFindingRefs.filter { it !in openRefs }.forEach {
        add(GoalAssessmentRecordIssue(Keys.OPEN_FINDING_REFS, "$it does not name an open recorded finding."))
      }
    }
  }

  private val HEAD_SHA = Regex("[a-fA-F0-9]{7,64}")
  private val FINDING_REF = Regex("GA-[0-9]{3}")
  private val SCENARIO_REF = Regex("IS-[0-9]{3}")
  private val REGRESSION_REF = Regex("TR-[0-9]{3}")
}
