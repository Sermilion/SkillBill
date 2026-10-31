package skillbill.engine.goalrunner.persist

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.assessment.GoalAssessmentRecordRead
import skillbill.goalrunner.assessment.model.GoalAssessmentDisposition
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRole
import skillbill.goalrunner.assessment.model.GoalAssessmentRound
import skillbill.goalrunner.assessment.model.GoalAssessmentRun
import skillbill.goalrunner.assessment.model.GoalAssessmentRunStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val PARENT_ID = "wftr-parent"
private const val ISSUE_KEY = "SKILL-442"
private const val HEAD_SHA_LENGTH = 40

class GoalAssessmentRecordPersistenceTest {
  private val workflows = InMemoryWorkflowStates()
  private val clock: Clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC)

  @Test
  fun `a partial round survives reopening and leaves unrelated parent artifacts intact`() {
    workflows.saveFeatureTaskWorkflow(parentRecord(), FeatureTaskWorkflowMode.RUNTIME)
    val firstStore = store()
    ledgerRecorder(firstStore).recordLedgerEntry(childActivation())
    val partial = partialRecord()

    assertTrue(firstStore.recordGoalAssessmentRecord(PARENT_ID, partial))

    val reopened = assertIs<GoalAssessmentRecordRead.Recorded>(store().readGoalAssessmentRecord(PARENT_ID))
    assertEquals(partial, reopened.record)
    assertTrue("goal_attempt_ledger" in storedArtifacts())
  }

  @Test
  fun `a corrupt stored record reads as corrupt and is not overwritten by the read`() {
    val corrupt = mapOf("contract_version" to "9.9")
    workflows.saveFeatureTaskWorkflow(parentRecord(corrupt), FeatureTaskWorkflowMode.RUNTIME)

    val read = store().readGoalAssessmentRecord(PARENT_ID)

    assertIs<GoalAssessmentRecordRead.Corrupt>(read)
    assertEquals(corrupt, storedArtifacts()[DurableWorkflowArtifactFamily.GOAL_ASSESSMENT_RECORD.label()])
  }

  private fun store() =
    testWorkflowGoalRunnerOutcomeStore(FakeDatabaseSessionFactory(workflows), testWorkflowSnapshotValidator)

  private fun storedArtifacts(): Map<String, Any?> =
    requireNotNull(
      workflows.getFeatureTaskWorkflowAsMode(PARENT_ID, FeatureTaskWorkflowMode.RUNTIME),
    ).toSnapshot().artifacts

  private fun ledgerRecorder(store: GoalRunnerWorkflowOutcomeStore) =
    GoalRunnerLedgerRecorder(
      store,
      GoalRunnerRunRequest(
        issueKey = ISSUE_KEY,
        repoRoot = Path.of("/tmp/skillbill-assessment-persistence"),
        invokedAgentId = "claude",
      ),
      clock,
      NoopRuntimeDiagnostics,
    )

  private fun childActivation() =
    GoalRunnerLedgerContext.ChildActivation(workflowId = PARENT_ID, issueKey = ISSUE_KEY, subtaskId = 1)

  private fun parentRecord(assessment: Any? = null): WorkflowStateRecord {
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine()
    val opened = engine.openRecord(definition, PARENT_ID, "fis-001", "preplan")
    val artifacts = assessment?.let { mapOf(DurableWorkflowArtifactFamily.GOAL_ASSESSMENT_RECORD.entry(it)) }.orEmpty()
    return engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        terminalInstant = Instant.EPOCH,
        workflowStatus = WorkflowStatus.RUNNING,
        currentStepId = "implement",
        stepUpdates = null,
        artifactsPatch = WorkflowArtifactPatch.from(artifacts),
        sessionId = "ftr-001",
      ),
    ).toRecord()
  }

  private fun partialRecord() =
    GoalAssessmentRecord(
      issueKey = ISSUE_KEY,
      rounds =
        listOf(
          GoalAssessmentRound(
            round = 1,
            headSha = "a".repeat(HEAD_SHA_LENGTH),
            scopeFiles = emptyList(),
            assessorRuns = listOf(GoalAssessmentRun(GoalAssessmentRole.SUBTASK, 1, GoalAssessmentRunStatus.COMPLETED)),
            findings =
              listOf(
                GoalAssessmentFinding(
                  findingRef = "GA-001",
                  kind = GoalAssessmentFindingKind.DEFECT,
                  severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
                  path = "src/Main.kt",
                  line = 12,
                  failureScenario = "an empty list crashes the loader",
                  guardedRef = null,
                  subtaskId = 1,
                  originRound = 1,
                  verification = GoalAssessmentVerification.UNVERIFIED,
                  verificationEvidence = "",
                  fixRoundsSurvived = 0,
                  disposition = GoalAssessmentDisposition.PR_ONLY,
                  state = GoalAssessmentFindingState.OPEN,
                ),
              ),
            scenarios = emptyList(),
            regressions = emptyList(),
            fix = null,
          ),
        ),
      exitReason = null,
      openFindingRefs = listOf("GA-001"),
    )
}
