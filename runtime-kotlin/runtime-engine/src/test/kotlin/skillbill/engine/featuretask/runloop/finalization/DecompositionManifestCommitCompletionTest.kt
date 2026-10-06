package skillbill.engine.featuretask.runloop.finalization

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.decomposition.decompositionPlanningResult
import skillbill.application.decomposition.decompositionPlanningSubtask
import skillbill.application.decomposition.model.DecompositionManifestWriteRequest
import skillbill.application.loadDecompositionManifest
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.writeIfDecomposed
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.model.toPath
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DecompositionManifestCommitCompletionTest {
  private val completion =
    DecompositionManifestCommitCompletion(
      testDecompositionManifestWriter,
      testDecompositionManifestValidator,
      TestDecompositionManifestStore,
    )

  @Test
  fun `the last subtask is marked complete before the final commit and the git tracked sha stays null`() {
    val repoRoot = Files.createTempDirectory("skillbill-manifest-complete-before-commit")
    val parentSpec = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpec.parent)
    Files.writeString(parentSpec, "# Parent\n")
    Files.writeString(subtaskSpec, "# Foundation\n")
    val written =
      writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpec,
          planningResult =
            decompositionPlanningResult(
              parentSpecPath = parentSpec.toString(),
              subtasks =
                listOf(
                  decompositionPlanningSubtask(id = 1, name = "foundation", specPath = subtaskSpec.toString()),
                ),
            ),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    assertNotNull(written)

    val failure =
      completion.markCompleteBeforeFinalCommit(
        request(repoRoot, subtaskSpec, subtaskId = 1, suppressPr = true),
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
      )

    assertNull(failure)
    val manifest = loadDecompositionManifest(written.manifestPath.toPath())
    assertEquals(DecompositionStatus.COMPLETE, manifest.status.decompositionStatus())
    val subtask = manifest.subtasks.single()
    assertEquals(DecompositionStatus.COMPLETE, subtask.status.decompositionStatus())
    assertNull(subtask.commitSha)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH, subtask.lastResumableStep)
    assertEquals("wfl-subtask-1", subtask.workflowId)
    assertEquals(0, manifest.currentSubtaskIntent.subtaskId)
    assertEquals("complete", manifest.currentSubtaskIntent.action)
  }

  @Test
  fun `an earlier subtask completion leaves the parent in progress and a non goal run does not rewrite the manifest`() {
    val repoRoot = Files.createTempDirectory("skillbill-manifest-partial-before-commit")
    val parentSpec = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val firstSpec = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    val secondSpec = parentSpec.parent.resolve("spec_subtask_2_follow.md")
    Files.createDirectories(parentSpec.parent)
    Files.writeString(parentSpec, "# Parent\n")
    Files.writeString(firstSpec, "# Foundation\n")
    Files.writeString(secondSpec, "# Follow\n")
    val written =
      writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpec,
          planningResult =
            decompositionPlanningResult(
              parentSpecPath = parentSpec.toString(),
              subtasks =
                listOf(
                  decompositionPlanningSubtask(id = 1, name = "foundation", specPath = firstSpec.toString()),
                  decompositionPlanningSubtask(id = 2, name = "follow", specPath = secondSpec.toString()),
                ),
            ),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    assertNotNull(written)

    assertNull(
      completion.markCompleteBeforeFinalCommit(
        request(repoRoot, firstSpec, subtaskId = 1, suppressPr = true),
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
      ),
    )
    val partial = loadDecompositionManifest(written.manifestPath.toPath())
    assertEquals(DecompositionStatus.IN_PROGRESS, partial.status.decompositionStatus())
    assertEquals(DecompositionStatus.COMPLETE, partial.subtasks.first { it.id == 1 }.status.decompositionStatus())
    assertEquals(DecompositionStatus.PENDING, partial.subtasks.first { it.id == 2 }.status.decompositionStatus())

    val before = Files.readString(written.manifestPath.toPath())
    assertNull(
      completion.markCompleteBeforeFinalCommit(
        request(repoRoot, firstSpec, subtaskId = 1, suppressPr = false),
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
      ),
    )
    assertNull(
      completion.markCompleteBeforeFinalCommit(
        request(repoRoot, firstSpec, subtaskId = 1, suppressPr = true).copy(goalContinuation = null),
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
      ),
    )
    assertEquals(before, Files.readString(written.manifestPath.toPath()))
  }

  private fun request(
    repoRoot: Path,
    spec: Path,
    subtaskId: Int,
    suppressPr: Boolean,
  ): FeatureTaskRuntimeRunRequest =
    FeatureTaskRuntimeRunRequest(
      issueKey = "SKILL-51",
      workflowId = "wfl-subtask-1",
      sessionId = "session-1",
      runInvariants =
        FeatureTaskRuntimeRunInvariants(
          specReference = spec.toString(),
          acceptanceCriteria = listOf("the subtask behavior is present"),
          mandatesAndOverrides = emptyList(),
        ),
      invokedAgentId = "cursor",
      repoRoot = repoRoot,
      goalContinuation =
        FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = "SKILL-51",
          subtaskId = subtaskId,
          goalBranch = "feature/SKILL-51-decomposition",
          suppressPr = suppressPr,
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
    )
}
