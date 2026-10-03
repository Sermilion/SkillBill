package skillbill.engine.goalrunner.planning.recovery

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_SCHEMA_ID
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.DeadProcessSupervisor
import skillbill.engine.goalrunner.persist.LiveProcessSupervisor
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.infrastructure.contracts.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.text.sha256HexUtf8
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GoalRunnerSpecDriftRecoveryTest {
  @Test
  fun `edited unfinished spec refreshes planning and preserves completed siblings`() {
    val fixture = SpecDriftFixture()
    val completed = fixture.store.manifest.subtasks.first()
    val refreshed = fixture.refresh()

    assertEquals(1, fixture.store.scopedReplanCount)
    assertEquals(true, fixture.store.lastIncludeSharedPreplan)
    assertEquals(setOf(1), fixture.store.plannedSubtaskIds)
    assertFalse(fixture.store.sharedPreplanPrepared)
    assertEquals(completed, refreshed.manifest.subtasks.first())
    assertEquals(2, refreshed.manifest.currentSubtaskIntent.subtaskId)
    assertTrue(fixture.messages.single().contains("value_used=scoped_replan"))
    assertSame(refreshed, fixture.refresh(refreshed))
    assertEquals(1, fixture.store.scopedReplanCount)
  }

  @Test
  fun `unchanged unfinished spec and edited completed spec retain planning`() {
    val fixture = SpecDriftFixture(currentSpec = ORIGINAL_SPEC)

    assertSame(fixture.state, fixture.refresh())
    assertEquals(0, fixture.store.scopedReplanCount)
    assertEquals(setOf(1, 2), fixture.store.plannedSubtaskIds)
    assertTrue(fixture.store.sharedPreplanPrepared)
    assertTrue(fixture.messages.isEmpty())
  }

  @Test
  fun `live parent refuses spec drift recovery without mutation`() {
    val fixture = SpecDriftFixture(live = true)
    val before = fixture.store.manifest

    assertFailsWith<IllegalArgumentException> { fixture.refresh() }
    assertEquals(before, fixture.store.manifest)
    assertEquals(0, fixture.store.scopedReplanCount)
    assertEquals(setOf(1, 2), fixture.store.plannedSubtaskIds)
    assertTrue(fixture.store.sharedPreplanPrepared)
  }

  @Test
  fun `corrupt payload stays with contract recovery instead of spec drift reset`() {
    val fixture = SpecDriftFixture(corrupt = true)

    assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { fixture.refresh() }
    assertEquals(0, fixture.store.scopedReplanCount)
    assertEquals(setOf(1, 2), fixture.store.plannedSubtaskIds)
    assertTrue(fixture.messages.isEmpty())
  }
}

private const val ORIGINAL_SPEC = "original governed spec"
private const val PLAN_PAYLOAD =
  """{"contract_version":"0.7","phase_id":"plan","status":"completed","summary":"planning",
  "produced_outputs":{"value":"implementation plan"}}"""

private class SpecDriftFixture(
  currentSpec: String = "edited governed spec",
  live: Boolean = false,
  corrupt: Boolean = false,
) {
  private val repoRoot = Files.createTempDirectory("spec-drift-recovery")
  private val clock = Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC)
  private val base = manifest(subtaskCount = 2)
  val store =
    InMemoryGoalManifestStore(
      base.copy(
        subtasks =
          listOf(
            base.subtasks[0].copy(status = "complete", commitSha = "sha-completed"),
            base.subtasks[1].copy(status = "blocked"),
          ),
      ),
    ).apply {
      plannedSubtaskIds = mutableSetOf(1, 2)
      executionLeaseForTest =
        GoalRunnerExecutionLease(
          generation = 1,
          ownerToken = "owner",
          hostIdentity = "host",
          bootIdentity = "boot",
          pid = 42,
          processBirthToken = "birth-42",
          heartbeatAt = "2026-07-27T11:59:50Z",
          expiresAt = if (live) "2026-07-27T12:01:00Z" else "2026-07-27T11:59:59Z",
        )
    }
  val state = requireNotNull(store.loadDurableByIssueKey(base.issueKey)).copy(repoRoot = repoRoot)
  val messages = mutableListOf<String>()
  private val diagnostics =
    object : RuntimeDiagnostics {
      override fun warning(
        message: String,
        error: Throwable?,
      ) {
        messages += message
      }

      override fun error(
        message: String,
        error: Throwable?,
      ) = Unit
    }
  private val plans =
    object : GoalPlanningPreparationRepository by EmptyGoalPlanningPreparationRepository {
      override fun findSubtaskPlan(
        expectedIdentity: GoalPlanningIdentity,
        subtaskId: Int,
        governedSubSpecPath: String,
      ): GoalSubtaskPlanCheckpoint? {
        if (subtaskId !in store.plannedSubtaskIds) return null
        return GoalSubtaskPlanCheckpoint(
          identity = expectedIdentity,
          subtaskId = subtaskId,
          manifestOrder = subtaskId - 1,
          governedSubSpecPath = governedSubSpecPath,
          subSpecHash = sha256HexUtf8(ORIGINAL_SPEC),
          provenance =
            GoalPlanningContractProvenance(
              "a".repeat(64),
              "b".repeat(64),
              GOAL_PLANNING_PREPARATION_SCHEMA_ID,
            ),
          payloadSha256 = if (corrupt) "bad-digest" else sha256HexUtf8(PLAN_PAYLOAD),
          planPayload = PLAN_PAYLOAD,
        )
      }
    }
  private val recovery =
    GoalRunnerSpecDriftRecovery(
      checkpoint =
        GoalPlanningPreparationCheckpoint(
          FakeDatabaseSessionFactory(InMemoryWorkflowStates(), planningPreparations = plans),
          FeatureTaskRuntimeWireArtifactValidator(),
        ),
      manifestStore = store,
      fileStore = TestDecompositionManifestStore,
      repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
      statusService =
        testGoalRunnerStatusService(
          store,
          RecordingOutcomeStore(),
          clock = clock,
          ports =
            GoalRunnerStatusTestPorts(
              workerSupervisor = if (live) LiveProcessSupervisor else DeadProcessSupervisor,
            ),
        ),
      envelopeValidator = FeatureTaskRuntimeWireArtifactValidator(),
      diagnostics = diagnostics,
    )

  init {
    store.manifest.subtasks.forEach { subtask ->
      val path = repoRoot.resolve(subtask.specPath)
      Files.createDirectories(path.parent)
      Files.writeString(path, if (subtask.id == 2) currentSpec else "edited completed spec")
    }
  }

  fun refresh(input: GoalRunnerManifestState = state): GoalRunnerManifestState =
    recovery.refresh(
      input,
      GoalRunnerRunRequest(issueKey = base.issueKey, repoRoot = repoRoot, invokedAgentId = "codex"),
    )
}
