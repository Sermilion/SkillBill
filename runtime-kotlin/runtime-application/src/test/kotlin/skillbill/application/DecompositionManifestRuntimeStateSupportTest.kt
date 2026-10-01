package skillbill.application

import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.model.DecompositionManifestRuntimeUpdate
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.statusFromUpdate
import skillbill.application.decomposition.withRuntimeFields
import skillbill.goalrunner.goalContinuationOutcome
import skillbill.workflow.decomposition.intentFor
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.model.WorkflowStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecompositionManifestRuntimeStateSupportTest {
  @Test
  fun `subtask advances to complete when the commit sha lives only in the recovered goal continuation outcome`() {
    val update =
      commitPushUpdate(
        goalContinuationOutcome =
          mapOf(
            "issue_key" to "SKILL-68",
            "subtask_id" to 5,
            "status" to "complete",
            "commit_sha" to "recovered-sha",
            "last_resumable_step" to "commit_push",
          ),
      )

    assertEquals("complete", statusFromUpdate(update))
    assertEquals(CurrentSubtaskIntent(subtaskId = 0, action = "complete"), intentFor(5, statusFromUpdate(update)))
  }

  @Test
  fun `subtask advances to complete on the unchanged commit push result happy path`() {
    val update = commitPushUpdate(commitPushResult = mapOf("commit_sha" to "commit-sha"))

    assertEquals("complete", statusFromUpdate(update))
    assertEquals(CurrentSubtaskIntent(subtaskId = 0, action = "complete"), intentFor(5, statusFromUpdate(update)))
  }

  @Test
  fun `conflicting commit push result and goal continuation outcome shas fail loudly`() {
    val update =
      commitPushUpdate(
        commitPushResult = mapOf("commit_sha" to "sha-a"),
        goalContinuationOutcome =
          mapOf(
            "issue_key" to "SKILL-68",
            "subtask_id" to 5,
            "status" to "complete",
            "commit_sha" to "sha-b",
          ),
      )

    val error = assertFailsWith<IllegalStateException> { statusFromUpdate(update) }
    assertEquals(true, error.message?.contains("sha-a"))
    assertEquals(true, error.message?.contains("sha-b"))
  }

  @Test
  fun `blocked subtask preserves prefixed artifact reason`() {
    val updated =
      baseSubtask().withRuntimeFields(
        manifest = baseManifest(),
        update =
          DecompositionManifestRuntimeUpdate(
            workflowId = "wfl-subtask-5",
            workflowStatus = WorkflowStatus.BLOCKED.wireValue,
            currentStepId = "validate",
            artifactsPatch = WorkflowArtifactPatch.from(mapOf("blocked_reason" to "validation: schema gate failed")),
          ),
        status = "blocked",
      )

    assertEquals("validation: schema gate failed", updated.blockedReason)
  }

  @Test
  fun `blocked subtask prefixes artifact reason when missing category`() {
    val updated =
      baseSubtask().withRuntimeFields(
        manifest = baseManifest(),
        update =
          DecompositionManifestRuntimeUpdate(
            workflowId = "wfl-subtask-5",
            workflowStatus = WorkflowStatus.BLOCKED.wireValue,
            currentStepId = "review",
            artifactsPatch = WorkflowArtifactPatch.from(mapOf("blocked_reason" to "review failed")),
          ),
        status = "blocked",
      )

    assertEquals("runtime: review failed", updated.blockedReason)
  }

  @Test
  fun `blocked suppress pr commit failure receives git category prefix`() {
    val updated =
      baseSubtask().withRuntimeFields(
        manifest = baseManifest(),
        update = commitPushUpdate(),
        status = "blocked",
      )

    assertEquals(
      "git: Goal-continuation commit_push completed without commit_push_result.commit_sha.",
      updated.blockedReason,
    )
  }

  @Test
  fun `blocked workflow step fallback receives runtime category prefix`() {
    val updated =
      baseSubtask().withRuntimeFields(
        manifest = baseManifest(),
        update =
          DecompositionManifestRuntimeUpdate(
            workflowId = "wfl-subtask-5",
            workflowStatus = WorkflowStatus.BLOCKED.wireValue,
            currentStepId = "audit",
          ),
        status = "blocked",
      )

    assertEquals("runtime: Workflow step 'audit' is blocked.", updated.blockedReason)
  }

  private fun commitPushUpdate(
    commitPushResult: Map<String, Any?>? = null,
    goalContinuationOutcome: Map<String, Any?>? = null,
  ): DecompositionManifestRuntimeUpdate =
    DecompositionManifestRuntimeUpdate(
      workflowId = "wfl-subtask-5",
      workflowStatus = WorkflowStatus.RUNNING.wireValue,
      currentStepId = "commit_push",
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1),
          ),
        ),
      artifactsPatch =
        WorkflowArtifactPatch.from(
          buildMap {
            put(
              "goal_continuation",
              mapOf(
                "issue_key" to "SKILL-68",
                "subtask_id" to 5,
                "suppress_pr" to true,
                "goal_branch" to "feature/SKILL-68",
                "code_review_mode" to "inline",
              ),
            )
            commitPushResult?.let { put("commit_push_result", it) }
            goalContinuationOutcome?.let {
              put(
                "goal_continuation_outcome",
                mapOf(
                  "workflow_id" to "wfl-subtask-5",
                  "last_resumable_step" to "commit_push",
                  "status" to "complete",
                ) + it,
              )
            }
          },
        ),
    )

  private fun baseManifest(): DecompositionManifest =
    DecompositionManifest(
      issueKey = "SKILL-68",
      featureName = "feature",
      parentSpecPath = ".feature-specs/SKILL-68/spec.md",
      baseBranch = "main",
      featureBranch = "feature/SKILL-68",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 5, action = "start"),
      subtasks = listOf(baseSubtask()),
    )

  private fun baseSubtask(): DecompositionSubtask =
    DecompositionSubtask(
      id = 5,
      name = "subtask",
      specPath = ".feature-specs/SKILL-68/subtask.md",
    )
}
