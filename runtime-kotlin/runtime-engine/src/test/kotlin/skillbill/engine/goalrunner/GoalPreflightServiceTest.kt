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
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.engine.decomposition.encodeDecompositionManifestYaml
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStoreDefaults
import skillbill.engine.goalrunner.model.GoalPreflightRequest
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.preflight.GoalPreflightService
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.AgentAddonFailureCode
import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
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
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionDependency
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
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

    assertFailsWith<InvalidDecompositionManifestSchemaError> {
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
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        service(
          database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
          manifestState = null,
        ).preflight(request(root))
      }

    assertEquals("issue_key_mismatch", error.failureCode)
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
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        service(
          database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
          manifestState = null,
        ).preflight(request(root))
      }

    assertEquals("duplicate_active", error.failureCode)
  }

  @Test
  fun `blank optional agent identities fail before repository lookup`() {
    val states = InMemoryWorkflowStates()
    val service =
      service(
        database = FakeDatabaseSessionFactory(states),
        manifestState = null,
      )

    assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
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

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        service(
          database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
          manifestState = GoalRunnerManifestState("parent-1", "/fake/metrics.db", manifest()),
          persistedReviewPolicy = GoalRunnerReviewPolicy(CodeReviewExecutionMode.DEFAULT),
        ).preflight(request(root, addons = listOf("new-addon")))
      }
    assertEquals(AgentAddonFailureCode.INVALID_SELECTION, error.code)
  }

  @Test
  fun `blocked planner selection has no expected runnable subtask`() {
    val root = Files.createTempDirectory("goal-preflight-blocked")
    val blocked =
      manifest().copy(
        currentSubtaskIntent = CurrentSubtaskIntent(2, "start"),
        subtasks = manifest().subtasks.map { it.copy(status = "pending") },
      )
    val result =
      service(
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        manifestState = GoalRunnerManifestState("", "/fake/metrics.db", blocked),
      ).preflight(request(root))

    assertEquals(null, result.gateBlock?.expectedFirstRunnableSubtask)
  }

  private fun service(
    database: FakeDatabaseSessionFactory,
    manifestState: GoalRunnerManifestState?,
    externalAgentAddonSourceConfigPort: ExternalAgentAddonSourceConfigPort =
      EmptyExternalAgentAddonSourceConfigPort,
    persistedReviewPolicy: GoalRunnerReviewPolicy? = null,
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
      manifestStore = TestManifestStore(manifestState, persistedReviewPolicy),
      agentAddonSelectionPort = TestAgentAddonSelectionPort,
      externalAgentAddonSourceConfigPort = externalAgentAddonSourceConfigPort,
      manifestFileStore = fileStore,
      manifestValidator = testDecompositionManifestValidator,
      repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
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

private class TestManifestStore(
  private val state: GoalRunnerManifestState?,
  private val persistedReviewPolicy: GoalRunnerReviewPolicy? = null,
) : GoalRunnerManifestStoreDefaults() {
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

private object EmptyExternalAgentAddonSourceConfigPort : ExternalAgentAddonSourceConfigPort {
  override fun readExternalAgentAddonSources(
    request: ExternalAgentAddonSourceConfigRequest,
  ): ExternalAgentAddonSourceConfigResult = ExternalAgentAddonSourceConfigResult()
}

private object TestAgentAddonSelectionPort : AgentAddonSelectionPort {
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
