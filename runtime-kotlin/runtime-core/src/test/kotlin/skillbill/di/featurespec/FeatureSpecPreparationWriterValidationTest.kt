package skillbill.di.featurespec

import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.loadDecompositionManifest
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.featurespec.model.FeatureSpecSubtaskPreparation
import skillbill.featurespec.model.FeatureSpecWriteRequest
import skillbill.featurespec.model.FeatureSpecWriteResult
import skillbill.infrastructure.contracts.workflow.decomposition.DecompositionManifestSchemaValidator
import skillbill.infrastructure.workflow.decomposition.FileSystemDecompositionManifestFileStore
import skillbill.model.EnvironmentContext
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureSpecPreparationWriterValidationTest {
  private val validator = DecompositionManifestSchemaValidator()
  private val fileStore = FileSystemDecompositionManifestFileStore()
  private val writer = FeatureSpecPreparationWriter(validator, fileStore, DecompositionManifestWriter())

  @Test
  fun `one subtask preparation writes schema valid manifest and is goal readable`() {
    val repoRoot = Files.createTempDirectory("skillbill-feature-spec-goal-readable")
    val component =
      RuntimeComponent::class.create(
        RuntimeContext(
          environment = EnvironmentContext(environment = emptyMap(), userHome = repoRoot),
          transport = TransportContext(),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      )

    val result = writer.write(repoRoot = repoRoot, request = decomposedWriteRequest())

    val manifestPath = repoRoot.resolve(result.decompositionManifestPath)
    assertTrue(Files.isRegularFile(manifestPath))
    assertSubtaskSpecsAreRunnable(repoRoot, result)
    val manifest = loadDecompositionManifest(manifestPath, fileStore, validator)
    assertManifestSupportsGoalRunner(repoRoot, result, manifest)

    val goalStatus =
      component.goalRunnerStatusService.status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-59",
          invokedAgentId = "codex",
          repoRoot = repoRoot,
        ),
      )
    assertNotNull(goalStatus)
    assertEquals("SKILL-59", goalStatus.issueKey)
    assertEquals(0, goalStatus.completeCount)
    assertEquals(1, goalStatus.pendingCount)
    assertEquals(0, goalStatus.blockedCount)
    assertEquals(1, goalStatus.currentSubtaskId)
  }

  private fun decomposedWriteRequest(): FeatureSpecWriteRequest =
    FeatureSpecWriteRequest(
      decision =
        FeatureSpecPreparationDecision(
          issueKey = "SKILL-59",
          intendedOutcome = "decomposed",
          acceptanceCriteria = listOf("Write parent and subtask specs."),
          constraints = listOf("Reuse decomposition writer/validator seams."),
          nonGoals = listOf("Do not run implementation."),
          mode = FeatureSpecPreparationMode.DECOMPOSED,
        ),
      featureName = "feature-spec-horizontal-skill",
      parentSpecOverview = "Prepare runtime-owned decomposition artifacts.",
      validationStrategy = "bill-code-check",
      subtasks =
        listOf(
          FeatureSpecSubtaskPreparation(
            id = 1,
            name = "foundation",
            scope = "Prepare typed write contracts.",
            acceptanceCriteria = listOf("Shared writer models exist."),
            nonGoals = listOf("No skill wiring."),
            dependencyNotes = "No dependencies.",
            validationStrategy = "bill-code-check",
            nextPath = "Run bill-feature-task on spec_subtask_1_foundation.md.",
            dependsOn = emptyList(),
          ),
        ),
    )

  private fun assertSubtaskSpecsAreRunnable(
    repoRoot: Path,
    result: FeatureSpecWriteResult,
  ) {
    assertEquals(1, result.subtaskSpecPaths.size)
    result.subtaskSpecPaths.forEach { subtaskSpecPath ->
      val fullPath = repoRoot.resolve(subtaskSpecPath)
      assertTrue(Files.isRegularFile(fullPath))
      assertContains(Files.readString(fullPath), "Run bill-feature-task on")
    }
  }

  private fun assertManifestSupportsGoalRunner(
    repoRoot: Path,
    result: FeatureSpecWriteResult,
    manifest: DecompositionManifest,
  ) {
    assertEquals("SKILL-59", manifest.issueKey)
    assertEquals(1, manifest.subtasks.size)
    assertEquals(1, manifest.currentSubtaskIntent.subtaskId)
    assertEquals("start", manifest.currentSubtaskIntent.action)
    assertEquals(
      result.subtaskSpecPaths.map { repoRoot.resolve(it).normalize().toString() },
      manifest.subtasks.map { it.specPath },
    )
  }
}
