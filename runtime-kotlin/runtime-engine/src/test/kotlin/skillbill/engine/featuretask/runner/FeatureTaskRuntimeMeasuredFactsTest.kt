package skillbill.engine.featuretask.runner

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.engine.featuretask.validation.passed
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class FeatureTaskRuntimeMeasuredFactsTest {
  @Test
  fun `write_history persists the facts measured from the step's file manifest, not the agent's claims`() {
    val repoRoot = Files.createTempDirectory("skillbill-measured-history")
    try {
      val git =
        RecordingWorkflowGitOperations(currentBranchValue = GOAL_BRANCH).also {
          it.headCommitShaValue = "f".repeat(40)
          it.runtimePhaseHeadCommitSequence.addAll(listOf("validate-sha", "validate-sha", "before-sha", "after-sha"))
          it.changedPathsBetweenCommitsValue = listOf("agent/history.md")
        }
      val harness =
        runnerHarness(
          RuntimeHarnessConfig(
            branchSetup = BranchSetupTestConfig(gitOperations = git),
            repoRoot = repoRoot,
            goalContinuation = goalChild(),
          ),
          core = RunnerHarnessCore(launcher = forgingLauncher(), agentAssignment = phasePerAgentAssignment()),
        )
      harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
      harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
      harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
      harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
      harness.seedPhase("simplify", "completed", 1, phaseAgent("simplify"), SIMPLIFY_OUTPUT)
      harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_AUDIT_OUTPUT)
      harness.seedReviewPhase("completed", 1, VALID_REVIEW_OUTPUT, reviewPassNumber = 1)
      harness.seedPhase("verify_findings", "completed", 1, phaseAgent("verify_findings"), VALID_VERIFY_FINDINGS_OUTPUT)
      harness.seedPhase("validate", "completed", 1, phaseAgent("validate"), validJsonOutput("validate"))

      harness.runner.run(harness.request())

      val launched =
        harness.launcher.requests.map {
          phaseIdFromPrompt(
            requireNotNull(it.skillRunRequest.promptOverride),
          )
        }
      val record = harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("write_history")?.outputArtifact.orEmpty()
      assertEquals(
        mapOf(
          FeatureTaskRuntimeMeasuredFactKeys.CHANGED_PATHS to listOf("agent/history.md"),
          FeatureTaskRuntimeMeasuredFactKeys.HISTORY_WRITTEN to true,
          FeatureTaskRuntimeMeasuredFactKeys.DECISIONS_RECORDED to false,
        ),
        measuredFacts(record),
        "$launched $record",
      )
    } finally {
      repoRoot.toFile().deleteRecursively()
    }
  }

  @Test
  fun `pr persists the pull request looked up on GitHub, not the agent's claims`() {
    val found = PullRequestIdentity.Found("https://github.com/o/r/pull/7", 7)
    val lookups = mutableListOf<String>()
    val lookup =
      PullRequestIdentityLookup { _, branch ->
        lookups += branch
        if (lookups.size == 1) PullRequestIdentity.Absent else found
      }
    val repoRoot = SlotBaselineFullRunCapture.seededRepoRoot()
    val databaseHome = SlotBaselineNormalizer.newTempHome()
    try {
      val git = committedRepoBranchSetup().gitOperations.also { it.currentBranchValue = GOAL_BRANCH }
      val launcher = forgingLauncher()
      val harness =
        telemetryRunnerHarness(
          launcher = launcher,
          runtimeConfig =
            sqliteRunConfig(git, repoRoot, launcher, goalContinuation = null)
              .copy(pullRequestIdentityLookup = lookup),
          databaseFactory = { SlotBaselineFullRunCapture.sqliteDatabase(databaseHome) },
        )

      val report = harness.runner.run(harness.request)

      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
      val record = harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("pr")?.outputArtifact.orEmpty()
      val facts = measuredFacts(record)
      assertEquals(found.url, facts[FeatureTaskRuntimeMeasuredFactKeys.PR_URL], record)
      assertEquals(found.number, (facts[FeatureTaskRuntimeMeasuredFactKeys.PR_NUMBER] as Number).toInt(), record)
      assertEquals(true, facts[FeatureTaskRuntimeMeasuredFactKeys.PR_CREATED], record)
      assertFalse(record.contains(FORGED_PR_URL), record)
    } finally {
      repoRoot.toFile().deleteRecursively()
      databaseHome.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a run resumed on SQLite from a write_history record written before measurement completes`() {
    val repoRoot = SlotBaselineFullRunCapture.seededRepoRoot()
    val databaseHome = SlotBaselineNormalizer.newTempHome()
    try {
      val git =
        RecordingWorkflowGitOperations(currentBranchValue = GOAL_BRANCH)
          .also { it.headCommitShaValue = "f".repeat(40) }
      val launcher = forgingLauncher()
      val harness =
        telemetryRunnerHarness(
          launcher = launcher,
          runtimeConfig = sqliteRunConfig(git, repoRoot, launcher, goalContinuation = goalChild()),
          databaseFactory = { SlotBaselineFullRunCapture.sqliteDatabase(databaseHome) },
        )
      harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
      harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
      harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
      harness.seedPhase("simplify", "completed", 1, phaseAgent("simplify"), SIMPLIFY_OUTPUT)
      harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_AUDIT_OUTPUT)
      harness.recorder.recordPhaseState(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = WORKFLOW_ID,
          phaseId = "review",
          status = "completed",
          attemptCount = 1,
          resolvedAgentId = phaseAgent("review"),
          finished = true,
          outputArtifact = VALID_REVIEW_OUTPUT,
          reviewPassNumber = 1,
        ),
      )
      harness.seedPhase("verify_findings", "completed", 1, phaseAgent("verify_findings"), VALID_VERIFY_FINDINGS_OUTPUT)
      harness.seedPhase("validate", "completed", 1, phaseAgent("validate"), validJsonOutput("validate"))
      harness.seedPhase("write_history", "completed", 1, phaseAgent("write_history"), LEGACY_WRITE_HISTORY_OUTPUT)

      val report = harness.runner.run(harness.request)

      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    } finally {
      repoRoot.toFile().deleteRecursively()
      databaseHome.toFile().deleteRecursively()
    }
  }

  private fun goalChild(): FeatureTaskRuntimeGoalContinuationContext =
    FeatureTaskRuntimeGoalContinuationContext(
      parentIssueKey = RUNNER_TEST_ISSUE_KEY,
      subtaskId = 5,
      subtaskName = "measured write_history and pr facts",
      goalBranch = GOAL_BRANCH,
      suppressPr = true,
      parentWorkflowId = "wfl-parent",
      reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
    )

  private fun sqliteRunConfig(
    git: RecordingWorkflowGitOperations,
    repoRoot: Path,
    launcher: RuntimeRecordingLauncher,
    goalContinuation: FeatureTaskRuntimeGoalContinuationContext?,
  ): RuntimeHarnessConfig =
    RuntimeHarnessConfig(
      branchSetup =
        BranchSetupTestConfig(gitOperations = git, specReference = SlotBaselineFullRunCapture.SPEC_REFERENCE),
      repoRoot = repoRoot,
      goalContinuation = goalContinuation,
      agentAssignment = phasePerAgentAssignment(),
      validationGateRunner =
        object : ValidationGateRunner {
          override fun run(request: ValidationGateRunRequest) = passed()
        },
      launcher = launcher,
    )

  private fun forgingLauncher(): RuntimeRecordingLauncher =
    RuntimeRecordingLauncher { request ->
      when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
        "audit" -> facts(auditSatisfiedOutput())
        "write_history" -> facts(FORGED_WRITE_HISTORY_OUTPUT)
        "pr" -> facts(FORGED_PR_OUTPUT)
        else -> facts(defaultPhaseOutput(request))
      }
    }

  private fun measuredFacts(outputArtifact: String): Map<String, Any?> {
    val envelope = JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(outputArtifact))
    val produced = JsonCodec.anyToStringAnyMap(envelope?.get(SharedPayloadKeys.PRODUCED_OUTPUTS))
    return JsonCodec.anyToStringAnyMap(produced?.get(FeatureTaskRuntimeMeasuredFactKeys.MEASURED_FACTS)).orEmpty()
  }

  private companion object {
    const val GOAL_BRANCH = "feat/existing-runtime-branch"
    const val VERSION = FEATURE_TASK_RUNTIME_CONTRACT_VERSION
    const val FORGED_PR_URL = "https://github.com/o/r/pull/999"

    const val FORGED_WRITE_HISTORY_OUTPUT =
      """{"contract_version":"$VERSION","phase_id":"write_history","status":"completed","summary":"done",""" +
        """"produced_outputs":{"value":"Recorded the boundary history entry.","runtime_measured_facts":""" +
        """{"changed_paths":["src/Forged.kt"],"history_written":false,"decisions_recorded":true}}}"""

    const val FORGED_PR_OUTPUT =
      """{"contract_version":"$VERSION","phase_id":"pr","status":"completed","summary":"done",""" +
        """"produced_outputs":{"value":"Opened the pull request for the branch.","runtime_measured_facts":""" +
        """{"pr_url":"$FORGED_PR_URL","pr_number":999,"pr_created":false}}}"""

    const val LEGACY_WRITE_HISTORY_OUTPUT =
      """{"contract_version":"0.7","phase_id":"write_history","status":"completed","summary":"done",""" +
        """"produced_outputs":{"history_result":{"changed_paths":["agent/history.md"],""" +
        """"decisions_recorded":["recorded"]}}}"""
  }
}
