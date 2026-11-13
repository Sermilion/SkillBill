package skillbill.engine.goalrunner.status

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeCommitPushPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.goalrunner.model.GoalRunnerPhaseCardState
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.idestatus.model.MonitorCiCycleOutcome
import skillbill.ports.idestatus.model.MonitorCiCycleRecord
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImport
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GoalRunnerPhaseCardProjectionTest {
  @Test
  fun `plan provenance remains visible when the older phase envelope was not retained`() {
    val imported =
      FeatureTaskRuntimeGoalPlanningImport(
        "goal-workflow", "SONA-1", "/repo", "parent-hash", "manifest-hash", "planning", "0.3", "phase-output", "0.7",
        1, 0, "spec_subtask_1.md", "spec-hash", "preplan-hash", "plan-hash",
      )
    val artifacts =
      DurableWorkflowArtifacts.fromMap(
        mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.entry(imported)),
      )
    val plan =
      requireNotNull(
        FeatureTaskRuntimePhaseRecord(
          "plan",
          WorkflowStepStatus.COMPLETED,
          attemptCount = 1,
          startedAt = Instant.EPOCH,
          resolvedAgentId = "codex",
        ).toPhaseCardHeader(),
      )
    val degradation = SubtaskReadDegradation(GoalRunnerStatusDurableReadTracker(NoopRuntimeDiagnostics))

    val result = attachPlanningRecord(listOf(plan), artifacts, emptyMap(), degradation).single()

    assertEquals("spec_subtask_1.md", result.output?.planRecord?.specPath)
    assertEquals("spec-hash", result.output?.planRecord?.specHash)
    assertEquals("plan-hash", result.output?.planRecord?.payloadHash)
    assertEquals(0, result.output?.planRecord?.manifestOrder)
    assertNull(result.output?.text)
    assertNull(degradation.source)
  }

  @Test
  fun `preplan projection retains the saved digest and summary for the desktop card`() {
    val digest =
      "## History and decisions\nSelected the migration boundary.\n" +
        "## Validation strategy\nRun the module checks."
    val output =
      phaseCardOutput(
        mapOf(
          SharedPayloadKeys.SUMMARY to "Read the planning context",
          SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to digest),
        ),
        "preplan",
      )

    assertEquals("Read the planning context", output.summary)
    assertEquals(digest, output.text)
  }

  @Test
  fun `output projection reads commit push history and pull request facts from owned keys`() {
    val output =
      phaseCardOutput(
        mapOf(
          SharedPayloadKeys.VERDICT to "passed",
          SharedPayloadKeys.PRODUCED_OUTPUTS to
            mapOf(
              FeatureTaskRuntimeCommitPushPayloadKeys.COMMIT_PUSH_RESULT to
                mapOf(
                  DecompositionManifestPayloadKeys.COMMIT_SHA to "abc123",
                  DecompositionPlanningPayloadKeys.BRANCH to "feature/527",
                  FeatureTaskRuntimeCommitPushPayloadKeys.PUSHED to true,
                ),
              FeatureTaskRuntimeMeasuredFactKeys.MEASURED_FACTS to
                mapOf(
                  FeatureTaskRuntimeMeasuredFactKeys.CHANGED_PATHS to listOf("src/Example.kt"),
                  FeatureTaskRuntimeMeasuredFactKeys.HISTORY_WRITTEN to true,
                  FeatureTaskRuntimeMeasuredFactKeys.DECISIONS_RECORDED to false,
                  FeatureTaskRuntimeMeasuredFactKeys.PR_URL to "https://example.test/pr/7",
                  FeatureTaskRuntimeMeasuredFactKeys.PR_NUMBER to 7L,
                ),
            ),
        ),
        "commit_push",
      )

    assertEquals("passed", output.verdict)
    assertEquals("abc123", output.commitSha)
    assertEquals("feature/527", output.pushedBranch)
    assertEquals(true, output.pushed)
    assertEquals(listOf("src/Example.kt"), output.historyReceipt?.changedPaths)
    assertEquals("https://example.test/pr/7", output.pullRequestUrl)
    assertEquals(7, output.pullRequestNumber)
  }

  @Test
  fun `header uses first start review round and bounded introduced files while pending stays hidden`() {
    val first = Instant.parse("2026-11-08T10:00:00Z")
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "review", status = WorkflowStepStatus.BLOCKED, attemptCount = 4,
        startedAt = first.plusSeconds(20), firstStartedAt = first, resolvedAgentId = "agent",
        reviewPassNumber = 3, launchedModel = "model", launchedEffort = "high",
        fileManifestIntroduced = (1..60).map { "src/$it.kt" },
      )

    val card = requireNotNull(record.toPhaseCardHeader())

    assertEquals(GoalRunnerPhaseCardState.BLOCKED, card.state)
    assertEquals(3, card.round)
    assertEquals(first, card.startedAt)
    assertEquals("agent", card.agentId)
    assertEquals("model", card.model)
    assertEquals(50, card.files.size)
    assertNull(record.copy(status = WorkflowStepStatus.PENDING).toPhaseCardHeader())
  }

  @Test
  fun `string booleans remain unknown in phase output facts`() {
    val output =
      phaseCardOutput(
        mapOf(
          SharedPayloadKeys.PRODUCED_OUTPUTS to
            mapOf(
              FeatureTaskRuntimeCommitPushPayloadKeys.COMMIT_PUSH_RESULT to
                mapOf(
                  FeatureTaskRuntimeCommitPushPayloadKeys.PUSHED to "true",
                ),
              FeatureTaskRuntimeMeasuredFactKeys.MEASURED_FACTS to
                mapOf(
                  FeatureTaskRuntimeMeasuredFactKeys.HISTORY_WRITTEN to "true",
                  FeatureTaskRuntimeMeasuredFactKeys.DECISIONS_RECORDED to "false",
                ),
            ),
        ),
        "write_history",
      )

    assertNull(output.pushed)
    val history = requireNotNull(output.historyReceipt)
    assertNull(history.historyWritten)
    assertNull(history.decisionsRecorded)
  }

  @Test
  fun `non object gate receipt reports the typed validation schema failure`() {
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        phaseCardOutput(
          mapOf(
            SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(ValidationEvidencePayloadKeys.BUILD_RECEIPT to "invalid"),
          ),
          "build",
        )
      }

    assertEquals(FeatureTaskRuntimeFailureCode.INVALID_VALIDATION_EVIDENCE_SCHEMA, failure.code)
  }

  @Test
  fun `child monitor fix does not reactivate the retained goal repair card`() {
    val at = Instant.parse("2026-11-08T10:00:00Z")
    val sources =
      GoalFinishCardSources(
        null,
        null,
        listOf(MonitorCiCycleRecord(1, "monitor", at, at, at, MonitorCiCycleOutcome.FAILED)),
        null,
      )
    val durableRead = GoalRunnerStatusDurableReadTracker(NoopRuntimeDiagnostics)
    val goalCards = goalFinishCards(sources, "monitor_fix", WorkflowStatus.BLOCKED, null, durableRead)
    val childCards = goalFinishCards(sources, "monitor_fix", WorkflowStatus.BLOCKED, "child", durableRead)

    assertEquals(GoalRunnerPhaseCardState.BLOCKED, goalCards.last().state)
    assertEquals("monitor_fix", childCards.last().phaseId)
    assertEquals(GoalRunnerPhaseCardState.COMPLETED, childCards.last().state)
  }
}
