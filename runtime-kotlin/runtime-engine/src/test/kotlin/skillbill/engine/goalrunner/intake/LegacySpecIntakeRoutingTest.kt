package skillbill.engine.goalrunner.intake

import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.error.core.databaseBusy
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.workflow.WorkflowStateRepositoryDefaults
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSpecOrigin
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LegacySpecIntakeRoutingTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-legacy-intake-repo")
  private val store = FixedProjectStorePort(Files.createTempDirectory("skillbill-legacy-intake-store"))
  private val workflowStates = InMemoryWorkflowStates()
  private val diagnostics = RecordingIntakeDiagnostics()
  private val preparation = testGoalIntakePreparation(workflowStates, diagnostics, store)
  private val legacySpec = repoRoot.resolve(LEGACY_SPEC_PATH)

  @Test
  fun `a legacy spec with no active workflow replans into the store and leaves the legacy file alone`() {
    writeLegacySpec()

    val resolved = preparation.resolvePlanSpec(ISSUE_KEY, LEGACY_SPEC_PATH, repoRoot)
    val seeded = preparation.intakePlanSpec(ISSUE_KEY, LEGACY_SPEC_PATH, repoRoot, resolved)

    val specsRoot = store.resolve(repoRoot).specsRoot
    assertTrue(seeded.specPath.startsWith(specsRoot), "expected a store spec but was ${seeded.specPath}")
    assertEquals(FeatureTaskRuntimePlanSpecOrigin.SEEDED, seeded.specOrigin)
    assertTrue(Files.isRegularFile(seeded.specPath))
    assertContains(Files.readString(seeded.specPath), "The desktop app compiles.")
    assertEquals(LEGACY_SPEC, Files.readString(legacySpec))
    assertEquals(listOf("spec.md"), legacyBundleEntries())
  }

  @Test
  fun `planning a legacy spec twice resolves the same store path`() {
    writeLegacySpec()

    val first = preparation.resolvePlanSpec(ISSUE_KEY, LEGACY_SPEC_PATH, repoRoot)
    val second = preparation.resolvePlanSpec(ISSUE_KEY, LEGACY_SPEC_PATH, repoRoot)

    assertEquals(first.specPath, second.specPath)
    assertNotEquals(legacySpec.toAbsolutePath().normalize(), first.specPath)
  }

  @Test
  fun `a blocked workflow that records the legacy spec resumes on it in place`() {
    writeLegacySpec()
    recordWorkflow(status = "blocked", governedSpecPath = LEGACY_SPEC_PATH)

    val resolved = preparation.resolvePlanSpec(ISSUE_KEY, LEGACY_SPEC_PATH, repoRoot)

    assertEquals(legacySpec.toAbsolutePath().normalize(), resolved.specPath)
    assertEquals(FeatureTaskRuntimePlanSpecOrigin.OPERATOR, resolved.specOrigin)
    assertEquals(LEGACY_SPEC, Files.readString(legacySpec))
    assertTrue(Files.notExists(store.resolve(repoRoot).specsRoot))
  }

  @Test
  fun `a completed workflow that records the legacy spec does not keep the launch on it`() {
    writeLegacySpec()
    recordWorkflow(status = "completed", governedSpecPath = LEGACY_SPEC_PATH)

    val resolved = preparation.resolvePlanSpec(ISSUE_KEY, LEGACY_SPEC_PATH, repoRoot)

    assertTrue(resolved.specPath.startsWith(store.resolve(repoRoot).specsRoot))
  }

  @Test
  fun `a failed workflow lookup degrades to a fresh intake and warns with the seam fields`() {
    writeLegacySpec()
    val degraded = testGoalIntakePreparation(FailingLookupWorkflowStates(), diagnostics, store)

    val resolved = degraded.resolvePlanSpec(ISSUE_KEY, LEGACY_SPEC_PATH, repoRoot)

    assertTrue(resolved.specPath.startsWith(store.resolve(repoRoot).specsRoot))
    val warning = diagnostics.warnings.single()
    assertContains(warning, "seam=legacy_spec_intake")
    assertContains(warning, "value_expected=store_bundle")
    assertContains(warning, "value_used=$LEGACY_SPEC_PATH")
    assertContains(warning, "cause=workflow_lookup_failed")
  }

  private fun legacyBundleEntries(): List<String> =
    Files.list(legacySpec.parent).use { paths -> paths.map { it.fileName.toString() }.sorted().toList() }

  private fun writeLegacySpec() {
    Files.createDirectories(legacySpec.parent)
    Files.writeString(legacySpec, LEGACY_SPEC)
  }

  private fun recordWorkflow(
    status: String,
    governedSpecPath: String,
  ) {
    workflowStates.saveFeatureTaskWorkflow(
      WorkflowStateRecord(
        workflowId = WORKFLOW_ID,
        sessionId = "session-1",
        workflowName = "bill-feature-task",
        contractVersion = "1.0",
        workflowStatus = status,
        currentStepId = "plan",
        stepsJson = "[]",
        artifactsJson = "{}",
        startedAt = null,
        updatedAt = null,
        finishedAt = null,
        mode = FeatureTaskWorkflowMode.RUNTIME,
        issueKey = ISSUE_KEY,
      ),
      FeatureTaskWorkflowMode.RUNTIME,
    )
    workflowStates.saveFeatureTaskExecutionIdentity(
      FeatureTaskExecutionIdentity(
        workflowId = WORKFLOW_ID,
        normalizedIssueKey = ISSUE_KEY,
        repositoryIdentity = TestRepositoryEnclosingRoot.repositoryIdentity(repoRoot),
        governedSpecPath = governedSpecPath,
        mode = FeatureTaskWorkflowMode.RUNTIME,
      ),
    )
  }

  private companion object {
    const val ISSUE_KEY = "SKILL-77"
    const val WORKFLOW_ID = "wftr-legacy-intake"
    const val LEGACY_SPEC_PATH = ".feature-specs/SKILL-77-legacy-routing/spec.md"
    const val LEGACY_SPEC =
      "# SKILL-77 Legacy routing\n\n## Acceptance Criteria\n\n1. The desktop app compiles.\n"
  }
}

private class FailingLookupWorkflowStates : WorkflowStateRepositoryDefaults() {
  override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = throw databaseBusy(IOException("workflow store unavailable"))
}

private class RecordingIntakeDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<String>()

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}
