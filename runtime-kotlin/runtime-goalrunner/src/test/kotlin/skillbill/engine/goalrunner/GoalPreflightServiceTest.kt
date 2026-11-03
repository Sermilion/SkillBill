package skillbill.engine.goalrunner

import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelectionEntry
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.specSource
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testProjectStore
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.engine.decomposition.encodeDecompositionManifestYaml
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationCandidate
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.goal.GoalPreflightRequest
import skillbill.engine.goal.GoalPreflightVerdict
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStoreDefaults
import skillbill.engine.goalrunner.model.OccupyingGoalParent
import skillbill.engine.goalrunner.preflight.GoalPreflightService
import skillbill.engine.goalrunner.preflight.adjustGoalPreflightContinuationLookup
import skillbill.engine.work.goalStartRefusalForTest
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.AgentAddonFailureCode
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.install.model.ExternalAgentAddonSource
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigRequest
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigResult
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.repository.toFileLocation
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionDependency
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalPreflightServiceTest {
  @Test
  fun `missing manifest is new work and does not open workflow state`() {
    val states = InMemoryWorkflowStates()
    val service =
      service(
        database = FakeDatabaseSessionFactory(states),
        manifestState = null,
      )
    val root = Files.createTempDirectory("goal-preflight-missing")

    val result = service.preflight(request(root))

    assertEquals("new_work", result.verdict)
    assertTrue(result.manifestMissing)
    assertEquals(emptyList(), states.listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME))
  }

  @Test
  fun `gate block preserves planner order dependencies and requested selections`() {
    val states = InMemoryWorkflowStates()
    val manifest = manifest()
    val service =
      service(
        database = FakeDatabaseSessionFactory(states),
        manifestState = GoalRunnerManifestState("", "/fake/metrics.db", manifest),
      )
    val root = Files.createTempDirectory("goal-preflight-gate")

    val result =
      service.preflight(
        request(
          root = root,
          reviewMode = CodeReviewExecutionMode.INLINE,
          agentOverride = "claude",
          addons = listOf("first-addon", "second-addon"),
        ),
      )

    val gate = requireNotNull(result.gateBlock)
    assertEquals("inline", gate.reviewMode)
    assertEquals("claude", gate.childAgent)
    assertEquals("claude", gate.childAgentOverride)
    assertEquals(listOf("first-addon", "second-addon"), gate.agentAddons.map { it.slug })
    assertEquals(listOf(1, 2), gate.subtasks.map { it.id })
    assertEquals("requires subtask 1", gate.subtasks[1].dependencies.single().note)
    assertEquals(2, gate.expectedFirstRunnableSubtask)
    assertEquals(emptyList(), states.listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME))
  }

  @Test
  fun `linear rehydration excludes completed subtask scratch and local mode is empty`() {
    val root = Files.createTempDirectory("goal-preflight-rehydrate")
    val linear = manifest(specSource = SpecSource.LINEAR)
    val service =
      service(
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        manifestState = GoalRunnerManifestState("", "/fake/metrics.db", linear),
      )

    val linearResult = service.preflight(request(root))
    assertEquals(
      listOf(linear.parentSpecPath, linear.subtasks[1].specPath),
      linearResult.rehydrateTargets.map { it.targetPath },
    )
    assertEquals(listOf("SKILL-901", "SKILL-901"), linearResult.rehydrateTargets.map { it.issueKey })
    assertEquals(listOf("SKILL-901", "SKILL-901"), linearResult.rehydrateTargets.map { it.linearIssueId })

    val localResult =
      service(
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        manifestState = GoalRunnerManifestState("", "/fake/metrics.db", manifest()),
      ).preflight(request(root))
    assertEquals(emptyList(), localResult.rehydrateTargets)
  }

  @Test
  fun `malformed issue key fails before new work classification`() {
    val service =
      service(
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        manifestState = null,
      )

    assertFailsWith<SkillBillRuntimeException> {
      service.preflight(
        request(Files.createTempDirectory("goal-preflight-invalid"), issueKey = "SKILL-901\nspoofed"),
      )
    }
  }

  @Test
  fun `malformed requested manifest fails even without a declared issue key`() {
    val root = Files.createTempDirectory("goal-preflight-malformed-manifest")
    val manifestPath = root.resolve(".feature-specs/SKILL-901-goal/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    Files.writeString(manifestPath, "feature_name: malformed\n")

    assertFailsWith<SkillBillRuntimeException> {
      service(
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        manifestState = null,
      ).preflight(request(root))
    }
  }

  @Test
  fun `path-matching manifest with a different issue key fails loudly`() {
    val root = Files.createTempDirectory("goal-preflight-issue-mismatch")
    val manifestPath = root.resolve(".feature-specs/SKILL-901-goal/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(
        manifest().copy(issueKey = "SKILL-902"),
        testDecompositionManifestValidator,
        TestDecompositionManifestStore,
      ),
    )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        service(
          database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
          manifestState = null,
        ).preflight(request(root))
      }

    assertEquals(WorkflowFailureCode.DECOMPOSITION_MANIFEST_ISSUE_KEY_MISMATCH, error.code)
  }

  @Test
  fun `duplicate active path-matching manifests fail with a typed error`() {
    val root = Files.createTempDirectory("goal-preflight-duplicate-manifest")
    listOf("first", "second").forEach { directory ->
      val manifestPath = root.resolve(".feature-specs/SKILL-901-$directory/decomposition-manifest.yaml")
      Files.createDirectories(manifestPath.parent)
      Files.writeString(
        manifestPath,
        encodeDecompositionManifestYaml(
          manifest(),
          testDecompositionManifestValidator,
          TestDecompositionManifestStore,
        ),
      )
    }

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        service(
          database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
          manifestState = null,
        ).preflight(request(root))
      }

    assertEquals(WorkflowFailureCode.DECOMPOSITION_MANIFEST_DUPLICATE_ACTIVE, error.code)
  }

  @Test
  fun `blank optional agent identities fail before repository lookup`() {
    val states = InMemoryWorkflowStates()
    val service =
      service(
        database = FakeDatabaseSessionFactory(states),
        manifestState = null,
      )

    assertFailsWith<SkillBillRuntimeException> {
      service.preflight(
        request(Files.createTempDirectory("goal-preflight-blank-agent"), agentOverride = " "),
      )
    }

    assertEquals(emptyList(), states.listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME))
  }

  @Test
  fun `raw add-on resolution receives configured external source roots`() {
    val root = Files.createTempDirectory("goal-preflight-external-addon")
    val externalRoot = root.resolve("external-addons")
    val config =
      object : ExternalAgentAddonSourceConfigPort {
        override fun readExternalAgentAddonSources(
          request: ExternalAgentAddonSourceConfigRequest,
        ): ExternalAgentAddonSourceConfigResult =
          ExternalAgentAddonSourceConfigResult(
            listOf(ExternalAgentAddonSource(externalRoot.toFileLocation())),
          )
      }
    val service =
      service(
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        manifestState = GoalRunnerManifestState("", "/fake/metrics.db", manifest()),
        externalAgentAddonSourceConfigPort = config,
      )

    val result = service.preflight(request(root, addons = listOf("external-addon")))

    assertEquals(listOf(externalRoot), TestAgentAddonSelectionPort.receivedExternalSourceRoots)
    assertEquals(listOf("external-addon"), result.gateBlock?.agentAddons?.map { it.slug })
  }

  @Test
  fun `requested add-ons cannot bypass an empty durable selection on goal resume`() {
    val root = Files.createTempDirectory("goal-preflight-addon-mismatch")
    val occupying = GoalRunnerManifestState("parent-1", "/fake/metrics.db", manifest())

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        service(
          database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
          manifestStore =
            TestManifestStore(
              occupying,
              persistedReviewPolicy = GoalRunnerReviewPolicy(CodeReviewExecutionMode.DEFAULT),
              occupyingState = occupying,
              occupyingStatus = WorkflowStatus.PAUSED,
            ),
        ).preflight(request(root, addons = listOf("new-addon")))
      }
    assertEquals(AgentAddonFailureCode.INVALID_SELECTION, error.code)
  }

  @Test
  fun `blocked planner selection has no expected runnable subtask`() {
    val root = Files.createTempDirectory("goal-preflight-blocked")
    val blocked = blockedPlannerManifest()
    val result =
      service(
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        manifestState = GoalRunnerManifestState("", "/fake/metrics.db", blocked),
      ).preflight(request(root))

    assertEquals(null, result.gateBlock?.expectedFirstRunnableSubtask)
    requireNotNull(result.gateBlock)
  }

  @Test
  fun `reusable abandoned leftover with blocked planner omits gate block`() {
    val root = Files.createTempDirectory("goal-preflight-abandoned-leftover")
    val leftover =
      GoalRunnerManifestState("parent-abandoned", "/fake/metrics.db", blockedPlannerManifest())
    val result =
      service(
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        manifestStore = TestManifestStore(leftover, occupyingState = null),
      ).preflight(request(root))

    assertEquals(GoalPreflightVerdict.NEW_WORK, result.verdict)
    assertNull(result.gateBlock)
    assertNull(goalStartRefusalForTest(result))
  }

  @Test
  fun `reusable completed leftover with done planner omits gate block`() {
    val root = Files.createTempDirectory("goal-preflight-completed-leftover")
    val doneLeftover =
      manifest().copy(
        status = "in_progress",
        currentSubtaskIntent = CurrentSubtaskIntent(2, "complete"),
        subtasks =
          manifest().subtasks.map { subtask ->
            subtask.copy(status = "complete")
          },
      )
    val leftover = GoalRunnerManifestState("parent-complete", "/fake/metrics.db", doneLeftover)
    val result =
      service(
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        manifestStore = TestManifestStore(leftover, occupyingState = null),
      ).preflight(request(root))

    assertEquals(GoalPreflightVerdict.NEW_WORK, result.verdict)
    assertNull(result.gateBlock)
    assertNull(goalStartRefusalForTest(result))
  }

  @Test
  fun `failed occupying parent without standalone history is terminal only`() {
    val occupying = GoalRunnerManifestState("parent-failed", "/fake/metrics.db", manifest())
    val result =
      service(
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        manifestStore =
          TestManifestStore(
            occupying,
            occupyingState = occupying,
            occupyingStatus = WorkflowStatus.FAILED,
          ),
      ).preflight(request(Files.createTempDirectory("goal-preflight-failed-occupy")))

    assertEquals(GoalPreflightVerdict.TERMINAL_ONLY, result.verdict)
  }

  @Test
  fun `running occupying parent without standalone history is already running`() {
    val occupying = GoalRunnerManifestState("parent-running", "/fake/metrics.db", manifest())
    val result =
      service(
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        manifestStore =
          TestManifestStore(
            occupying,
            occupyingState = occupying,
            occupyingStatus = WorkflowStatus.RUNNING,
          ),
      ).preflight(request(Files.createTempDirectory("goal-preflight-running-occupy")))

    assertEquals(GoalPreflightVerdict.ALREADY_RUNNING, result.verdict)
  }

  @Test
  fun `paused occupying parent without standalone history is resumable`() {
    val occupying = GoalRunnerManifestState("parent-paused", "/fake/metrics.db", manifest())
    val result =
      service(
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        manifestStore =
          TestManifestStore(
            occupying,
            occupyingState = occupying,
            occupyingStatus = WorkflowStatus.PAUSED,
          ),
      ).preflight(request(Files.createTempDirectory("goal-preflight-paused-occupy")))

    assertEquals(GoalPreflightVerdict.RESUMABLE, result.verdict)
  }

  @Test
  fun `completed standalone terminal history without occupying parent is new work`() {
    val root = Files.createTempDirectory("goal-preflight-complete-reuse")
    val states = InMemoryWorkflowStates()
    seedCompletedStandalone(states, root)
    val leftover = GoalRunnerManifestState("parent-complete", "/fake/metrics.db", manifest())
    val database = FakeDatabaseSessionFactory(states)
    val lookup =
      FeatureTaskContinuationLookupService(
        database,
        testWorkflowSnapshotValidator,
        ExecutionPlanAdmissionFixture().compatibility,
        NoopRuntimeDiagnostics,
      ).lookupIfPresent("SKILL-901", goalRepositoryIdentity(root))
    assertIs<FeatureTaskContinuationLookupResult.TerminalOnly>(lookup)
    val result =
      service(
        database = database,
        manifestStore = TestManifestStore(leftover, occupyingState = null),
      ).preflight(request(root))
    assertEquals(GoalPreflightVerdict.NEW_WORK, result.verdict)
    assertNull(result.gateBlock)
  }

  @Test
  fun `terminal history latest completed is reusable even when an earlier candidate failed`() {
    val lookup =
      FeatureTaskContinuationLookupResult.TerminalOnly(
        listOf(
          terminalCandidate(WorkflowStatus.FAILED, "wfl-failed"),
          terminalCandidate(WorkflowStatus.COMPLETED, "wfl-complete"),
        ),
      )
    val adjusted = adjustGoalPreflightContinuationLookup(lookup, occupying = null, occupyingWorkflowStatus = null)
    assertEquals(FeatureTaskContinuationLookupResult.NoMatch, adjusted)
  }

  @Test
  fun `reusable standalone history does not hide a running occupying parent`() {
    val occupying = GoalRunnerManifestState("parent-running", "/fake/metrics.db", manifest())
    val lookup =
      FeatureTaskContinuationLookupResult.TerminalOnly(listOf(terminalCandidate(WorkflowStatus.COMPLETED)))
    val adjusted =
      adjustGoalPreflightContinuationLookup(lookup, occupying, WorkflowStatus.RUNNING)
    assertIs<FeatureTaskContinuationLookupResult.AlreadyRunning>(adjusted)
  }

  private fun seedCompletedStandalone(
    states: InMemoryWorkflowStates,
    root: Path,
    workflowId: String = "wfl-standalone",
    issueKey: String = "SKILL-901",
  ) {
    states.saveFeatureTaskWorkflow(
      WorkflowStateRecord(
        workflowId = workflowId,
        sessionId = "sess-complete",
        workflowName = "bill-feature-task",
        contractVersion = "0.1",
        workflowStatus = WorkflowStatus.COMPLETED.wireValue,
        currentStepId = "plan",
        stepsJson = "[]",
        artifactsJson = "{}",
        startedAt = null,
        updatedAt = null,
        finishedAt = null,
        mode = FeatureTaskWorkflowMode.RUNTIME,
        issueKey = issueKey,
      ),
      FeatureTaskWorkflowMode.RUNTIME,
    )
    states.saveFeatureTaskExecutionIdentity(
      FeatureTaskExecutionIdentity(
        workflowId = workflowId,
        normalizedIssueKey = issueKey,
        repositoryIdentity = goalRepositoryIdentity(root),
        governedSpecPath = ".feature-specs/SKILL-901-goal/spec.md",
        mode = FeatureTaskWorkflowMode.RUNTIME,
        routeScope = FeatureTaskRouteScope.STANDALONE,
      ),
    )
  }

  private fun terminalCandidate(
    status: WorkflowStatus,
    workflowId: String = "wfl-standalone",
  ): FeatureTaskContinuationCandidate =
    FeatureTaskContinuationCandidate(
      executionIdentity =
        FeatureTaskExecutionIdentity(
          workflowId = workflowId,
          normalizedIssueKey = "SKILL-901",
          repositoryIdentity = "repo",
          governedSpecPath = ".feature-specs/SKILL-901-goal/spec.md",
          mode = FeatureTaskWorkflowMode.RUNTIME,
        ),
      workflowId = workflowId,
      mode = FeatureTaskWorkflowMode.RUNTIME,
      status = status.wireValue,
      currentStep = "plan",
      governedSpecPath = ".feature-specs/SKILL-901-goal/spec.md",
      updatedAt = null,
      liveness = null,
      summary = "terminal",
    )

  private fun service(
    database: FakeDatabaseSessionFactory,
    manifestState: GoalRunnerManifestState?,
    externalAgentAddonSourceConfigPort: ExternalAgentAddonSourceConfigPort =
      EmptyExternalAgentAddonSourceConfigPort,
    persistedReviewPolicy: GoalRunnerReviewPolicy? = null,
  ): GoalPreflightService =
    service(
      database,
      TestManifestStore(manifestState, persistedReviewPolicy),
      externalAgentAddonSourceConfigPort,
    )

  private fun service(
    database: FakeDatabaseSessionFactory,
    manifestStore: TestManifestStore,
    externalAgentAddonSourceConfigPort: ExternalAgentAddonSourceConfigPort =
      EmptyExternalAgentAddonSourceConfigPort,
  ): GoalPreflightService {
    val fileStore: DecompositionManifestStore = TestDecompositionManifestStore
    return GoalPreflightService(
      continuationLookup =
        FeatureTaskContinuationLookupService(
          database,
          testWorkflowSnapshotValidator,
          ExecutionPlanAdmissionFixture().compatibility,
          NoopRuntimeDiagnostics,
        ),
      manifestStore = manifestStore,
      agentAddonSelectionPort = TestAgentAddonSelectionPort,
      externalAgentAddonSourceConfigPort = externalAgentAddonSourceConfigPort,
      manifestFileStore = fileStore,
      manifestValidator = testDecompositionManifestValidator,
      repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
      projectStore = testProjectStore,
    )
  }

  private fun request(
    root: Path,
    issueKey: String = "SKILL-901",
    reviewMode: CodeReviewExecutionMode? = null,
    agentOverride: String? = null,
    addons: List<String> = emptyList(),
  ): GoalPreflightRequest =
    GoalPreflightRequest(
      issueKey = issueKey,
      repoRoot = root,
      invokedAgentId = "codex",
      agentOverrideId = agentOverride,
      requestedReviewMode = reviewMode,
      requestedAgentAddonSlugs = addons,
    )

  private fun blockedPlannerManifest(): DecompositionManifest =
    manifest().copy(
      currentSubtaskIntent = CurrentSubtaskIntent(2, "start"),
      subtasks = manifest().subtasks.map { it.copy(status = "pending") },
    )

  private fun manifest(specSource: SpecSource = SpecSource.LOCAL): DecompositionManifest =
    DecompositionManifest(
      issueKey = "SKILL-901",
      featureName = "preflight-test",
      parentSpecPath = ".feature-specs/SKILL-901-goal/spec.md",
      specSource = specSource,
      status = "in_progress",
      baseBranch = "main",
      featureBranch = "feat/SKILL-901-goal",
      currentSubtaskIntent = CurrentSubtaskIntent(1, "start"),
      subtasks =
        listOf(
          DecompositionSubtask(
            id = 1,
            name = "first",
            specPath = ".feature-specs/SKILL-901-goal/spec_subtask_1.md",
            status = "complete",
            linearIssueId = "SKILL-901",
          ),
          DecompositionSubtask(
            id = 2,
            name = "second",
            specPath = ".feature-specs/SKILL-901-goal/spec_subtask_2.md",
            dependencies = listOf(DecompositionDependency(1)),
            linearIssueId = "SKILL-901",
          ),
        ),
    )
}

internal class TestManifestStore(
  private val state: GoalRunnerManifestState?,
  private val persistedReviewPolicy: GoalRunnerReviewPolicy? = null,
  private val occupyingState: GoalRunnerManifestState? = null,
  private val occupyingStatus: WorkflowStatus? = null,
) : GoalRunnerManifestStoreDefaults() {
  override fun loadOccupyingByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): OccupyingGoalParent? = occupyingState?.let { OccupyingGoalParent(it, occupyingStatus ?: WorkflowStatus.PAUSED) }

  override fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = state

  override fun readByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = state

  override fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? = persistedReviewPolicy

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
  ): Boolean = true

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ): Boolean = true

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean = true

  override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState =
    error("Preflight must not save manifest state.")
}

internal object EmptyExternalAgentAddonSourceConfigPort : ExternalAgentAddonSourceConfigPort {
  override fun readExternalAgentAddonSources(
    request: ExternalAgentAddonSourceConfigRequest,
  ): ExternalAgentAddonSourceConfigResult = ExternalAgentAddonSourceConfigResult()
}

internal object TestAgentAddonSelectionPort : AgentAddonSelectionPort {
  var receivedExternalSourceRoots: List<Path> = emptyList()

  override fun resolveInitial(
    repoRoot: Path,
    requestedSlugs: List<String>,
    consumer: AgentAddonConsumer,
    receivingAgentIds: List<String>,
    externalSourceRoots: List<Path>,
  ): HydratedAgentAddonSelection {
    receivedExternalSourceRoots = externalSourceRoots
    return hydrated(requestedSlugs)
  }

  override fun verifyPersisted(
    selection: AgentAddonSelection,
    consumer: AgentAddonConsumer,
    receivingAgentIds: List<String>,
  ): HydratedAgentAddonSelection = hydrated(selection.entries.map { it.slug })

  private fun hydrated(slugs: List<String>): HydratedAgentAddonSelection =
    HydratedAgentAddonSelection(
      slugs.mapIndexed { index, slug ->
        HydratedAgentAddonSelectionEntry(
          persisted = PersistedAgentAddonSelectionEntry(slug, "source-$index", "a".repeat(64)),
          description = "Description for $slug",
          content = "content-$slug",
        )
      },
    )
}
