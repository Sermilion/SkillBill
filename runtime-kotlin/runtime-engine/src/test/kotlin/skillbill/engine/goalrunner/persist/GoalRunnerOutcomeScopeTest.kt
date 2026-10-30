package skillbill.engine.goalrunner.persist

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalRunnerOutcomeScopeTest {
  @Test
  fun `completed child from another goal cannot replace the current validating child in status`() {
    val workflows = InMemoryWorkflowStates()
    seedCurrentParent(workflows, "current-child")
    workflows.saveFeatureTaskWorkflow(child("current-child", "wfl-parent"), RUNTIME)
    workflows.saveFeatureTaskWorkflow(child("old-child", "old-parent", complete = true), RUNTIME)
    val database = FakeDatabaseSessionFactory(workflows)
    val outcomes = testWorkflowGoalRunnerOutcomeStore(database)
    val base = manifest(1)
    val manifests =
      InMemoryGoalManifestStore(
        base.copy(
          issueKey = "SKILL-87.1",
          status = "in_progress",
          currentSubtaskIntent = CurrentSubtaskIntent(1, "resume"),
          subtasks = listOf(base.subtasks.single().copy(status = "in_progress", workflowId = "current-child")),
        ),
      )
    val service = testGoalRunnerStatusService(manifests, outcomes, database = database)
    val request = GoalRunnerStatusRequest("SKILL-87.1", pruneCheckpointRefs = false)

    val status = requireNotNull(service.status(request))

    assertEquals(0, status.completeCount)
    assertEquals(1, status.pendingCount)
    assertEquals(1, status.currentSubtaskId)
    assertEquals("current-child", status.currentChildWorkflowId)
    assertEquals("validate", status.currentStep)
    val page = service.reconcileSubtaskPage("wfl-parent", request, manifests.manifest.subtasks)
    assertEquals("in_progress", page.single().status)
    assertEquals("current-child", page.single().workflowId)
    assertTrue(outcomes.authoritativeOutcomesForSubtasks("SKILL-87.1", "wfl-parent", setOf(1)).isEmpty())
  }

  @Test
  fun `reconciliation neither imports another goal completion nor blocks its running children`() {
    val workflows = InMemoryWorkflowStates()
    seedCurrentParent(workflows, "current-child")
    workflows.saveFeatureTaskWorkflow(child("current-child", "wfl-parent"), RUNTIME)
    workflows.saveFeatureTaskWorkflow(child("old-child", "old-parent", complete = true), RUNTIME)
    workflows.saveFeatureTaskWorkflow(child("other-running-child", "other-parent"), RUNTIME)
    val foreignBefore = requireNotNull(workflows.getFeatureTaskWorkflow("other-running-child"))
    val completedBefore = requireNotNull(workflows.getFeatureTaskWorkflow("old-child"))
    val outcomes = testWorkflowGoalRunnerOutcomeStore(FakeDatabaseSessionFactory(workflows))

    val reconciled =
      outcomes.reconcileAuthoritativeOutcomes(
        issueKey = "SKILL-87.1",
        parentWorkflowId = "wfl-parent",
        activeWorkflowIds = setOf("current-child"),
        gate = GoalRunnerReconcileGate(allowInactiveReconciliation = true),
      )

    assertTrue(reconciled.isEmpty())
    assertEquals("running", workflows.getFeatureTaskWorkflow("current-child")?.workflowStatus)
    assertEquals(foreignBefore, workflows.getFeatureTaskWorkflow("other-running-child"))
    assertEquals(completedBefore, workflows.getFeatureTaskWorkflow("old-child"))
  }

  @Test
  fun `completed sibling within the same goal remains authoritative`() {
    val workflows = InMemoryWorkflowStates()
    seedCurrentParent(workflows, "completed-child")
    workflows.saveFeatureTaskWorkflow(child("current-child", "wfl-parent"), RUNTIME)
    workflows.saveFeatureTaskWorkflow(child("completed-child", "wfl-parent", complete = true), RUNTIME)
    ExecutionPlanAdmissionFixture(SkeletonDefinition.GOAL_CHILD).seed(workflows, "completed-child", "SKILL-87.1")
    val outcomes = testWorkflowGoalRunnerOutcomeStore(FakeDatabaseSessionFactory(workflows))

    val completed = requireNotNull(outcomes.authoritativeOutcomes("SKILL-87.1", "wfl-parent")[1])

    assertEquals(GoalRunnerTerminalStatus.COMPLETE, completed.status)
    assertEquals("completed-child", completed.workflowId)
    assertEquals("completed-sha", completed.commitSha)
  }

  private fun child(
    workflowId: String,
    parentId: String,
    complete: Boolean = false,
  ): WorkflowStateRecord {
    val record = runtimeCandidateRecord(workflowId, Instant.parse("2026-10-20T19:20:19Z"))
    val artifacts = decodeWorkflowArtifactsForTest(record.artifactsJson).toMutableMap()
    val continuation =
      requireNotNull(record.typedArtifacts.goalContinuationArtifact()).copy(parentWorkflowId = parentId)
    val continuationEntry = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.entry(continuation)
    if (complete) {
      artifacts["goal_continuation_outcome"] =
        mapOf(
          "issue_key" to "SKILL-87.1",
          "subtask_id" to 1,
          "status" to "complete",
          "workflow_id" to workflowId,
          "commit_sha" to "completed-sha",
          "last_resumable_step" to "commit_push",
          "suppress_pr" to true,
        )
    }
    return record.copy(
      workflowStatus = if (complete) "completed" else "running",
      currentStepId = if (complete) "commit_push" else "validate",
      artifactsJson = JsonCodec.valueToJsonString(artifacts),
      typedArtifacts = DurableWorkflowArtifacts.fromMap(record.typedArtifacts + continuationEntry),
    ).let { if (complete) it.withSettledLegacyHistorySkip() else it }
  }
}
