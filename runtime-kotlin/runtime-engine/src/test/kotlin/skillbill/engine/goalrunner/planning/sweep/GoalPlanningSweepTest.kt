package skillbill.engine.goalrunner.planning.sweep

import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.specSource
import skillbill.application.realFeatureTaskRuntimePhaseOutputValidator
import skillbill.application.realPlanningProjectionValidator
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_SCHEMA_ID
import skillbill.engine.PlanningProjectionFixtures
import skillbill.engine.disposition
import skillbill.engine.envelope
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimePhaseOutputTestValidator
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.RecordingPullRequestPort
import skillbill.engine.goalrunner.execution.core.GoalPlanningSweepPortsParams
import skillbill.engine.goalrunner.execution.core.goalRunnerDeps
import skillbill.engine.goalrunner.execution.core.testGoalPlanningSweepPorts
import skillbill.engine.goalrunner.execution.core.testGoalRunner
import skillbill.engine.goalrunner.launchFacts
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.planning.attempt.GoalPlanningAttemptRecorder
import skillbill.engine.goalrunner.planning.attempt.NO_GOAL_PLANNING_ATTEMPT_RECORDER
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedContextPacket
import skillbill.engine.goalrunner.planning.model.GoalPlanningAttemptRecord
import skillbill.engine.goalrunner.planning.model.GoalPlanningBurstSchedule
import skillbill.engine.goalrunner.planning.model.GoalPlanningRejectionRecord
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningProvenanceRecoverability
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningRecoveryKind
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningRefreshLiveness
import skillbill.engine.goalrunner.planning.recovery.IDLE_GOAL_PLANNING_REFRESH_LIVENESS
import skillbill.engine.goalrunner.planning.recovery.classifyGoalPlanningProvenanceRecoverability
import skillbill.engine.goalrunner.planning.remedies.GoalPlanningRejectionRecorder
import skillbill.engine.goalrunner.planning.remedies.NO_GOAL_PLANNING_REJECTION_RECORDER
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.ports.concurrency.SequentialBoundedWorkFanOutPort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationProvenance
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryHeading
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStoreDefaults
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.goalrunner.verification.model.GoalVerificationBoundaryDiscovery
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.time.NoopRuntimeTimingPort
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.time.model.RuntimeWaitResult
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.text.sha256HexUtf8
import skillbill.workflow.NoopGoalPlanningPreparationEnvelopeValidator
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestWireMap
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.model.goalreview.GoalProgressEventKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputSourceLocation
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputValidationResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class GoalPlanningSweepMigrateTest {
  @Test
  fun `migrate projects 0_1 packets with platform_packs to the current version`() {
    val subtasks =
      listOf(
        DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
      )
    val legacy =
      legacyV01Packet(
        subtasks,
        platformPacks =
          mapOf(
            "platform-packs/kotlin/platform.yaml" to
              "routing_signals: [kotlin]\ndeclared_code_review_areas: [architecture]\n",
          ),
      )

    val migrated = GoalPlanningSharedContextPacket.migrate(legacy)

    assertEquals(GoalPlanningSharedContextPacket.VERSION, migrated["packet_version"])
    assertFalse(migrated.containsKey("platform_packs"))
    GoalPlanningSharedContextPacket.validate(
      packet = migrated,
      repositoryIdentity = "repo-root-realpath-v1:/tmp/fixture",
      normalizedIssueKey = "SKILL-172",
      parentSpecPath = ".feature-specs/SKILL-172/spec.md",
      subtasks = subtasks,
    )
    assertEquals("0.4", GoalPlanningSharedContextPacket.VERSION)
    assertEquals(
      GoalPlanningSharedContextPacket.discardedCatalog(),
      migrated["boundary_memory"],
      "the 0.1 chain lands on 0.4 with the legacy byte-prefix payload discarded and marked truncated",
    )
  }

  @Test
  fun `migrate discards legacy prefix boundary memory from recovered 0_2 packets`() {
    val subtasks =
      listOf(
        DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
      )
    val legacy =
      legacyV02Packet(
        subtasks,
        boundaryMemory =
          mapOf(
            "platform-packs/kmp/agent/history.md" to "pack history",
            "platform-packs/kotlin/agent/decisions.md" to "pack decision",
            "runtime-kotlin/runtime-application/agent/history.md" to "module history",
          ),
      )

    val migrated = GoalPlanningSharedContextPacket.migrate(legacy)

    assertEquals(GoalPlanningSharedContextPacket.VERSION, migrated["packet_version"])
    assertEquals(
      GoalPlanningSharedContextPacket.discardedCatalog(),
      migrated["boundary_memory"],
      "a discarded legacy payload must be distinguishable from a repo with no boundary memory",
    )
    GoalPlanningSharedContextPacket.validate(
      packet = migrated,
      repositoryIdentity = "repo-root-realpath-v1:/tmp/fixture",
      normalizedIssueKey = "SKILL-172",
      parentSpecPath = ".feature-specs/SKILL-172/spec.md",
      subtasks = subtasks,
    )
  }

  @Test
  fun `migrate rejects tampered 0_2 integrity and unsupported versions`() {
    val subtasks =
      listOf(
        DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
      )
    val legacy = legacyV02Packet(subtasks, boundaryMemory = emptyMap())

    val tampered =
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        GoalPlanningSharedContextPacket.migrate(legacy + ("integrity_sha256" to "not-a-real-digest"))
      }
    assertContains(tampered.message.orEmpty(), "integrity is invalid")

    val unsupported =
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        GoalPlanningSharedContextPacket.migrate(legacy + ("packet_version" to "0.9"))
      }
    assertContains(unsupported.message.orEmpty(), "unsupported")
  }

  @Test
  fun `migrate projects 0_1 packets with empty platform_packs to the current version`() {
    val subtasks =
      listOf(
        DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
      )
    val migrated =
      GoalPlanningSharedContextPacket.migrate(
        legacyV01Packet(subtasks, platformPacks = emptyMap()),
      )

    assertFalse(migrated.containsKey("platform_packs"))
    GoalPlanningSharedContextPacket.validate(
      packet = migrated,
      repositoryIdentity = "repo-root-realpath-v1:/tmp/fixture",
      normalizedIssueKey = "SKILL-172",
      parentSpecPath = ".feature-specs/SKILL-172/spec.md",
      subtasks = subtasks,
    )
  }

  @Test
  fun `validate rejects raw 0_1 packets without migrate`() {
    val subtasks =
      listOf(
        DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
      )
    val legacy = legacyV01Packet(subtasks, platformPacks = emptyMap())

    val rawFailure =
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        GoalPlanningSharedContextPacket.validate(
          packet = legacy,
          repositoryIdentity = "repo-root-realpath-v1:/tmp/fixture",
          normalizedIssueKey = "SKILL-172",
          parentSpecPath = ".feature-specs/SKILL-172/spec.md",
          subtasks = subtasks,
        )
      }
    assertTrue(
      rawFailure.message.orEmpty().contains("fields are invalid") ||
        rawFailure.message.orEmpty().contains("version is invalid"),
    )
  }

  @Test
  fun `migrate rejects unknown versions and tampered 0_1 integrity`() {
    val subtasks =
      listOf(
        DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
      )
    val legacy = legacyV01Packet(subtasks, platformPacks = emptyMap())

    val unknownFailure =
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        GoalPlanningSharedContextPacket.migrate(legacy + ("packet_version" to "0.0"))
      }
    assertContains(unknownFailure.message.orEmpty(), "unsupported")

    val tamperedFailure =
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        GoalPlanningSharedContextPacket.migrate(legacy + ("integrity_sha256" to "not-a-real-digest"))
      }
    assertContains(tamperedFailure.message.orEmpty(), "integrity is invalid")
  }

  @Test
  fun `resume migrates an on-disk 0_1 shared packet without stopping`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val prepared = requireNotNull(harness.recordFor(1))
    harness.fixtures.database.repository.markPrepared(
      prepared.withSharedPacket { packet ->
        packet +
          ("packet_version" to GoalPlanningSharedContextPacket.LEGACY_VERSION_0_1) +
          ("boundary_memory" to mapOf("runtime-kotlin/agent/history.md" to "legacy prefix excerpt")) +
          (
            "platform_packs" to
              mapOf(
                "platform-packs/kotlin/platform.yaml" to "routing_signals: [kotlin]\n",
              )
          )
      },
    )

    val outcome = harness.sweep.prepare(state, harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
  }

  @Test
  fun `preplan prompt carries the heading catalog and no entry body`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val preplanPrompt = harness.launcher.requests.first().skillRunRequest.promptOverride.orEmpty()
    assertContains(preplanPrompt, FIXTURE_HEADING_ID)
    assertContains(preplanPrompt, "produced_outputs.value")
    assertFalse(preplanPrompt.contains(FIXTURE_BODY), "the catalog never carries entry bodies")
  }

  @Test
  fun `preplan prose reaches the plan prompt`() {
    val prose = "Distinctive preplan prose for plan."
    val harness =
      sweepHarness { phase, _, _ ->
        if (phase == "preplan") {
          launchFacts(stdout = preplanProsePayload(value = prose))
        } else {
          validPhaseOutcome(phase)
        }
      }

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val planPrompt = harness.launcher.requests[1].skillRunRequest.promptOverride.orEmpty()
    assertContains(planPrompt, prose)
  }

  @Test
  fun `a preplan without optional prompt yields a plan prompt without a prompt field`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    val planPrompt = harness.launcher.requests[1].skillRunRequest.promptOverride.orEmpty()
    assertContains(planPrompt, FIXTURE_HEADING_ID)
    assertFalse(planPrompt.contains("## Selected boundary memory"))
    assertFalse(planPrompt.contains(FIXTURE_BODY))
  }

  @Test
  fun `a recovered 0_2 packet resumes without any excluded prefix payload reaching the prompt`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val prepared = requireNotNull(harness.recordFor(1))
    harness.fixtures.database.repository.markPrepared(
      prepared.withSharedPacket { packet ->
        packet +
          ("packet_version" to GoalPlanningSharedContextPacket.LEGACY_VERSION_0_2) +
          (
            "boundary_memory" to
              mapOf(
                "platform-packs/kmp/agent/history.md" to "pack prefix excerpt",
              )
          )
      },
    )

    val outcome = harness.sweep.prepare(state, harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertFalse(
      harness.launcher.requests.any { request ->
        request.skillRunRequest.promptOverride.orEmpty().contains("pack prefix excerpt")
      },
    )
  }

  @Test
  fun `migrate discards legacy prefix boundary memory from recovered 0_3 packets`() {
    val subtasks =
      listOf(
        DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
      )
    val legacy =
      legacyV03Packet(
        subtasks,
        boundaryMemory = mapOf("runtime-kotlin/agent/history.md" to "legacy prefix excerpt"),
      )

    val migrated = GoalPlanningSharedContextPacket.migrate(legacy)

    assertEquals(GoalPlanningSharedContextPacket.VERSION, migrated["packet_version"])
    assertEquals(
      GoalPlanningSharedContextPacket.discardedCatalog(),
      migrated["boundary_memory"],
      "a discarded legacy payload must be distinguishable from a repo with no boundary memory",
    )
    GoalPlanningSharedContextPacket.validate(
      packet = migrated,
      repositoryIdentity = "repo-root-realpath-v1:/tmp/fixture",
      normalizedIssueKey = "SKILL-172",
      parentSpecPath = ".feature-specs/SKILL-172/spec.md",
      subtasks = subtasks,
    )
  }

  @Test
  fun `validate rejects a malformed duplicated or oversized 0_4 catalog`() {
    val subtasks =
      listOf(
        DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
      )
    val base = GoalPlanningSharedContextPacket.migrate(legacyV03Packet(subtasks, boundaryMemory = emptyMap()))

    fun rejects(catalog: Map<String, Any?>): String {
      val body = (base - "integrity_sha256") + ("boundary_memory" to catalog)
      val packet = body + ("integrity_sha256" to GoalPlanningSharedContextPacket.digest(body))
      return assertFailsWith<Exception> {
        GoalPlanningSharedContextPacket.validate(
          packet = packet,
          repositoryIdentity = "repo-root-realpath-v1:/tmp/fixture",
          normalizedIssueKey = "SKILL-172",
          parentSpecPath = ".feature-specs/SKILL-172/spec.md",
          subtasks = subtasks,
        )
      }.message.orEmpty()
    }

    val entry =
      linkedMapOf<String, Any?>(
        "heading_id" to FIXTURE_HEADING_ID,
        "source_path" to "runtime-kotlin/agent/history.md",
        "kind" to "history",
        "heading" to FIXTURE_HEADING,
      )
    assertContains(rejects(linkedMapOf("catalog" to listOf(entry - "kind"), "truncated" to false)), "invalid")
    assertContains(
      rejects(linkedMapOf("catalog" to listOf(entry, entry), "truncated" to false)),
      "unique",
    )
    assertContains(
      rejects(
        linkedMapOf(
          "catalog" to
            (0..GoalPlanningContext.MAX_CATALOG_HEADINGS).map { index ->
              entry + ("heading_id" to "runtime-kotlin/agent/history.md#$index-000000000000")
            },
          "truncated" to false,
        ),
      ),
      "cap",
    )
  }
}

class GoalPlanningSweepPromptTest {
  @Test
  fun `migrate refuses to re-sign a tampered packet while dropping excluded entries`() {
    val subtasks =
      listOf(
        DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
      )
    val base = GoalPlanningSharedContextPacket.migrate(legacyV03Packet(subtasks, boundaryMemory = emptyMap()))
    val excluded =
      linkedMapOf<String, Any?>(
        "heading_id" to "platform-packs/kmp/agent/history.md#0-000000000000",
        "source_path" to "platform-packs/kmp/agent/history.md",
        "kind" to "history",
        "heading" to FIXTURE_HEADING,
      )
    val body =
      (base - "integrity_sha256") +
        ("boundary_memory" to linkedMapOf("catalog" to listOf(excluded), "truncated" to false))
    val signed = body + ("integrity_sha256" to GoalPlanningSharedContextPacket.digest(body))
    val tampered = signed + ("validation_guidance" to "injected guidance the digest never covered")

    val failure =
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        GoalPlanningSharedContextPacket.migrate(tampered)
      }

    assertContains(failure.message.orEmpty(), "integrity is invalid")
  }

  @Test
  fun `migrate drops newly excluded catalog entries instead of blocking the resume`() {
    val subtasks =
      listOf(
        DecompositionSubtask(id = 1, name = "planning-context-discovery", specPath = "spec_subtask_1.md"),
      )
    val base = GoalPlanningSharedContextPacket.migrate(legacyV03Packet(subtasks, boundaryMemory = emptyMap()))
    val kept =
      linkedMapOf<String, Any?>(
        "heading_id" to FIXTURE_HEADING_ID,
        "source_path" to "runtime-kotlin/agent/history.md",
        "kind" to "history",
        "heading" to FIXTURE_HEADING,
      )
    val excluded =
      kept +
        mapOf(
          "heading_id" to "platform-packs/kmp/agent/history.md#0-000000000000",
          "source_path" to "platform-packs/kmp/agent/history.md",
        )
    val body =
      (base - "integrity_sha256") +
        ("boundary_memory" to linkedMapOf("catalog" to listOf(kept, excluded), "truncated" to false))
    val stale = body + ("integrity_sha256" to GoalPlanningSharedContextPacket.digest(body))

    val migrated = GoalPlanningSharedContextPacket.migrate(stale)

    val catalog = (migrated["boundary_memory"] as Map<*, *>)["catalog"] as List<*>
    assertEquals(listOf(kept), catalog, "the excluded entry is dropped, the governed one is kept")
    assertEquals(true, (migrated["boundary_memory"] as Map<*, *>)["truncated"])
    GoalPlanningSharedContextPacket.validate(
      packet = migrated,
      repositoryIdentity = "repo-root-realpath-v1:/tmp/fixture",
      normalizedIssueKey = "SKILL-172",
      parentSpecPath = ".feature-specs/SKILL-172/spec.md",
      subtasks = subtasks,
    )
  }

  @Test
  fun `prepared sweep reports absent hydration context for a sibling added after preparation`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val prepared =
      assertIs<GoalPlanningSweepOutcome.PreparedAll>(
        harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request()),
      )

    assertNotNull(prepared.hydrationFor(1))
    assertEquals(null, prepared.hydrationFor(2))
  }

  @Test
  fun `planning launch authorization closes before the child run is awaited`() {
    val authorization = TrackingPlanningAuthorization()
    val openWhileAwaitingChild = mutableListOf<Boolean>()
    val store = AuthorizingGoalPlanningManifestStore(authorization)
    val harness =
      sweepHarness(SweepHarnessConfig(manifestStore = store)) { phase, _, request ->
        val spawnAuthorization =
          assertNotNull(
            request.skillRunRequest.spawnAuthorization,
            "a planning launch must carry its authorization into the spawn seam, not wrap the blocking launch",
          )
        spawnAuthorization.withAuthorization { }
        openWhileAwaitingChild += authorization.open
        validPhaseOutcome(phase)
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(3, authorization.invocations, "every planning launch is authorized exactly once")
    assertEquals(
      listOf(false, false, false),
      openWhileAwaitingChild,
      "the authorization transaction must be closed while the planning child is awaited",
    )
  }

  @Test
  fun `multi-subtask sweep prepares one shared preplan and every included subtask plan in manifest order`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), harness.request())

    val prepared = assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(harness.stateFor(manifest(subtaskCount = 2)).parentWorkflowId, prepared.identity?.parentGoalWorkflowId)
    assertEquals(listOf(1, 2), prepared.descriptors.map { it.subtaskId })
    assertNotNull(prepared.provenance)
    assertEquals(listOf("preplan", "plan", "plan"), harness.launcher.phases)
    assertEquals(listOf(0, 1, 2), harness.launcher.subtaskIds)
    assertEquals(2, harness.preparedCount())
    val preplanPrompt = harness.launcher.requests.first().skillRunRequest.promptOverride.orEmpty()
    val planPrompt = harness.launcher.requests[1].skillRunRequest.promptOverride.orEmpty()
    assertFalse(preplanPrompt.contains("Current governed sub-spec:"))
    assertFalse(preplanPrompt.contains("Current subtask dependency context:"))
    assertTrue(
      preplanPrompt.contains(
        "spec_reference: ${harness.fixtures.repoRoot.toRealPath().resolve(".feature-specs/SKILL-56-goal/spec.md")}",
      ),
      "the singleton preplan must be governed by the parent goal spec",
    )
    assertTrue(planPrompt.contains("Current governed sub-spec:"))
    assertTrue(planPrompt.contains("Current subtask dependency context:"))

    val record = harness.recordFor(1)
    assertNotNull(record, "each subtask plan must be durably persisted with its shared provenance")
    assertEquals("SKILL-56", record.normalizedIssueKey)
    assertTrue(record.repositoryIdentity.startsWith("repo-root-realpath-v1:"))
    assertEquals(".feature-specs/SKILL-56-goal/spec_subtask_1.md", record.governedSubSpecPath)
    assertNotNull(JsonCodec.parseObjectOrNull(record.preplanPayload), "preplan payload must be strict JSON")
    assertNotNull(JsonCodec.parseObjectOrNull(record.planPayload), "plan payload must be strict JSON")
  }

  @Test
  fun `shared repository and decomposition discovery happens exactly once across all sub_specs`() {
    val discovery = CountingContextDiscovery()
    val harness =
      sweepHarness(
        SweepHarnessConfig(
          contextDiscovery = discovery,
        ),
      ) { phase, _, _ -> validPhaseOutcome(phase) }

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 3)), harness.request())

    assertEquals(1, harness.manifestFileStore.countContaining("/spec.md"))
    assertEquals(1, harness.manifestFileStore.countContaining("decomposition-manifest.yaml"))
    assertEquals(3, harness.manifestFileStore.countContaining("spec_subtask_"))
    assertEquals(1, discovery.calls)
    assertEquals(4, harness.launcher.requests.size)
  }

  @Test
  fun `resume revalidates saved payload status before accepting a prepared row`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val saved = requireNotNull(harness.recordFor(1))
    harness.fixtures.database.repository.markPrepared(
      saved.copy(planPayload = saved.planPayload.replace("\"completed\"", "\"blocked\"")),
    )
    val launchCount = harness.launcher.requests.size

    val outcome = harness.sweep.prepare(state, harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, stopped.currentSubtaskId)
    assertTrue(stopped.blockedReason.isNotBlank())
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  @Test
  fun `resume accepts a completed Linear plan when its governed scratch spec is deleted`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val initial = manifest(subtaskCount = 2).copy(specSource = SpecSource.LINEAR)
    harness.sweep.prepare(harness.stateFor(initial), harness.request())
    harness.manifestFileStore.remove("spec_subtask_1.md")
    val launchCount = harness.launcher.requests.size
    val resumed =
      initial.copy(
        subtasks =
          initial.subtasks.map { subtask ->
            if (subtask.id == 1) subtask.copy(status = "complete") else subtask
          },
      )

    val outcome = harness.sweep.prepare(harness.stateFor(resumed), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  @Test
  fun `mutable execution fields do not invalidate immutable decomposition provenance`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val initial = manifest(subtaskCount = 1)
    harness.sweep.prepare(harness.stateFor(initial), harness.request())
    val launchCount = harness.launcher.requests.size
    harness.manifestFileStore.replaceDecompositionManifest("runtime projection changed")
    val advanced =
      initial.copy(
        status = "in_progress",
        currentSubtaskIntent = initial.currentSubtaskIntent.copy(action = "resume"),
        subtasks =
          initial.subtasks.map {
            it.copy(status = "skipped", workflowId = "wfl-child", commitSha = "abc123", lastResumableStep = "pr")
          },
      )

    val outcome = harness.sweep.prepare(harness.stateFor(advanced), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  @Test
  fun `resume refreshes stale shared preplan in-run when parent spec body changes with identical prose`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val launchCount = harness.launcher.requests.size
    val sharedBefore = requireNotNull(harness.fixtures.database.repository.findSharedPreplan(harness.identity()))
    val planBefore =
      requireNotNull(
        harness.fixtures.database.repository.findSubtaskPlan(
          harness.identity(),
          1,
          ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
        ),
      )
    harness.manifestFileStore.replaceSpec("spec.md", "# Initial feature contract edited after planning")

    val outcome = harness.sweep.prepare(state, harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(launchCount + 1, harness.launcher.requests.size, "exactly one refresh preplan launch")
    assertEquals(listOf("preplan"), harness.launcher.phases.takeLast(1))
    val sharedAfter = requireNotNull(harness.fixtures.database.repository.findSharedPreplan(harness.identity()))
    assertEquals(sharedBefore.preplanPayload, sharedAfter.preplanPayload)
    assertEquals(sharedBefore.payloadSha256, sharedAfter.payloadSha256)
    assertEquals(outcome.provenance, sharedAfter.provenance)
    assertEquals(
      sha256HexUtf8("# Initial feature contract edited after planning"),
      sharedAfter.provenance.parentSpecHash,
    )
    val planAfter =
      requireNotNull(
        harness.fixtures.database.repository.findSubtaskPlan(
          harness.identity(),
          1,
          ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
        ),
      )
    assertEquals(planBefore.planPayload, planAfter.planPayload)
    assertEquals(sharedAfter.provenance, planAfter.provenance)
    assertEquals(0, harness.fixtures.database.repository.cascadeAfterRefreshCalls)
  }

  @Test
  fun `resume refreshes and cascades via shared helper when prose content changes`() {
    var preplanLaunches = 0
    val harness =
      sweepHarness { phase, _, _ ->
        if (phase == "preplan") {
          preplanLaunches += 1
          val value =
            if (preplanLaunches == 1) {
              "First preplan prose payload."
            } else {
              "Refreshed preplan prose payload."
            }
          launchFacts(stdout = preplanProsePayload(value = value))
        } else {
          validPhaseOutcome(phase)
        }
      }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val launchCount = harness.launcher.requests.size
    val sharedBefore = requireNotNull(harness.fixtures.database.repository.findSharedPreplan(harness.identity()))
    assertNotNull(
      harness.fixtures.database.repository.findSubtaskPlan(
        harness.identity(),
        1,
        ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
      ),
    )
    val refreshedParentSpec = "# Initial feature contract edited for prose drift"
    harness.manifestFileStore.replaceSpec("spec.md", refreshedParentSpec)

    val outcome = harness.sweep.prepare(state, harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    val sharedAfter = requireNotNull(harness.fixtures.database.repository.findSharedPreplan(harness.identity()))
    assertTrue(sharedAfter.preplanPayload != sharedBefore.preplanPayload)
    assertTrue(sharedAfter.payloadSha256 != sharedBefore.payloadSha256)
    assertEquals(1, harness.fixtures.database.repository.cascadeAfterRefreshCalls)
    assertEquals(
      listOf("preplan", "plan"),
      harness.launcher.phases.drop(launchCount),
      "changed-set refresh must cascade then regenerate the discarded plan",
    )
    assertNotNull(
      harness.fixtures.database.repository.findSubtaskPlan(
        harness.identity(),
        1,
        ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
      ),
      "post-cascade plan regeneration must leave a settled plan row",
    )
    val regenPlanPrompt =
      harness.launcher.requests
        .drop(launchCount)
        .single { harness.launcher.phaseOf(it) == "plan" }
        .skillRunRequest.promptOverride.orEmpty()
    assertContains(
      regenPlanPrompt,
      refreshedParentSpec,
      message = "regenerated plan must use the refreshed planning packet, not the pre-refresh recovered packet",
    )
  }

  @Test
  fun `changed prose refresh preserves complete-with-commit sibling plan`() {
    val fixture = changedHeadingSetRefreshFixture()
    val outcome =
      fixture.harness.sweep.prepare(
        fixture.harness.stateFor(fixture.resumeManifest),
        fixture.harness.request(),
      )

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    val plan1After =
      requireNotNull(
        fixture.harness.fixtures.database.repository.findSubtaskPlan(
          fixture.harness.identity(),
          1,
          ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
        ),
      )
    assertEquals(fixture.plan1Before.planPayload, plan1After.planPayload)
    val sharedAfter =
      requireNotNull(
        fixture.harness.fixtures.database.repository.findSharedPreplan(fixture.harness.identity()),
      )
    assertEquals(sharedAfter.provenance, plan1After.provenance)
    assertEquals("complete", fixture.store.manifest.subtasks[0].status)
    assertEquals("sha-1", fixture.store.manifest.subtasks[0].commitSha)
    assertEquals("wfl-1", fixture.store.manifest.subtasks[0].workflowId)
    assertEquals(1, fixture.harness.fixtures.database.repository.cascadeAfterRefreshCalls)
    assertEquals(listOf(2), fixture.harness.fixtures.database.repository.lastCascadePlanSubtaskIds)
    assertEquals(
      listOf("preplan", "plan"),
      fixture.harness.launcher.phases.drop(fixture.launchCount),
      "changed-set refresh must cascade then regenerate the discarded non-terminal plan",
    )
    assertNotNull(
      fixture.harness.fixtures.database.repository.findSubtaskPlan(
        fixture.harness.identity(),
        2,
        ".feature-specs/SKILL-56-goal/spec_subtask_2.md",
      ),
      "post-cascade plan regeneration must leave a settled plan row for the non-terminal sibling",
    )
  }

  private data class ChangedHeadingSetRefreshFixture(
    val harness: SweepHarness,
    val store: InMemoryGoalManifestStore,
    val resumeManifest: DecompositionManifest,
    val plan1Before: GoalSubtaskPlanCheckpoint,
    val launchCount: Int,
  )

  private fun changedHeadingSetRefreshFixture(): ChangedHeadingSetRefreshFixture {
    var preplanLaunches = 0
    val initial = manifest(subtaskCount = 2)
    val resumeManifest =
      initial.copy(
        subtasks =
          initial.subtasks.map { subtask ->
            if (subtask.id == 1) {
              subtask.copy(status = "complete", commitSha = "sha-1", workflowId = "wfl-1")
            } else {
              subtask
            }
          },
      )
    val store = InMemoryGoalManifestStore(resumeManifest)
    val harness =
      sweepHarness(SweepHarnessConfig(manifestStore = store)) { phase, _, _ ->
        if (phase == "preplan") {
          preplanLaunches += 1
          val value =
            if (preplanLaunches == 1) {
              "First preplan prose for cascade test."
            } else {
              "Refreshed preplan prose for cascade test."
            }
          launchFacts(stdout = preplanProsePayload(value = value))
        } else {
          validPhaseOutcome(phase)
        }
      }
    harness.sweep.prepare(harness.stateFor(initial), harness.request())
    val plan1Before =
      requireNotNull(
        harness.fixtures.database.repository.findSubtaskPlan(
          harness.identity(),
          1,
          ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
        ),
      )
    assertNotNull(
      harness.fixtures.database.repository.findSubtaskPlan(
        harness.identity(),
        2,
        ".feature-specs/SKILL-56-goal/spec_subtask_2.md",
      ),
    )
    val launchCount = harness.launcher.requests.size
    harness.manifestFileStore.replaceSpec("spec.md", "# Initial feature contract edited for prose drift")
    return ChangedHeadingSetRefreshFixture(harness, store, resumeManifest, plan1Before, launchCount)
  }

  @Test
  fun `refresh refuses while execution liveness is live`() {
    val harness =
      sweepHarness(
        SweepHarnessConfig(
          refreshLiveness = GoalPlanningRefreshLiveness { _ -> ExecutionLiveness.LIVE },
        ),
      ) { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val launchCount = harness.launcher.requests.size
    val sharedBefore = requireNotNull(harness.fixtures.database.repository.findSharedPreplan(harness.identity()))
    harness.manifestFileStore.replaceSpec("spec.md", "# Initial feature contract edited while live")

    val outcome = harness.sweep.prepare(state, harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, stopped.currentSubtaskId)
    assertEquals("preplan", stopped.lastResumableStep)
    assertTrue(stopped.blockedReason.contains("live"))
    assertTrue(stopped.blockedReason.contains("refuse shared-preplan refresh"))
    assertEquals(launchCount, harness.launcher.requests.size)
    val sharedAfter = requireNotNull(harness.fixtures.database.repository.findSharedPreplan(harness.identity()))
    assertEquals(sharedBefore.payloadSha256, sharedAfter.payloadSha256)
    assertEquals(sharedBefore.provenance, sharedAfter.provenance)
  }

  @Test
  fun `refresh refuses while execution liveness is unknown`() {
    val harness =
      sweepHarness(
        SweepHarnessConfig(
          refreshLiveness = GoalPlanningRefreshLiveness { _ -> ExecutionLiveness.UNKNOWN },
        ),
      ) { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val launchCount = harness.launcher.requests.size
    harness.manifestFileStore.replaceSpec("spec.md", "# Initial feature contract edited with unknown liveness")

    val outcome = harness.sweep.prepare(state, harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertTrue(stopped.blockedReason.contains("unknown execution liveness"))
    assertEquals(launchCount, harness.launcher.requests.size)
  }
}

class GoalPlanningSweepPrepareAndResumeTest {
  @Test
  fun `planning identity names the Git top level when request starts in a child directory`() {
    val fixtures = sharedSweepFixtures()
    Files.createDirectories(fixtures.repoRoot.resolve(".git"))
    val childRoot = Files.createDirectories(fixtures.repoRoot.resolve("nested"))
    val sweep =
      testGoalPlanningSweepPorts(
        GoalPlanningSweepPortsParams(
          checkpoint = fixtures.checkpoint,
          outputValidator = fixtures.outputValidator,
          subtaskLauncher = SweepPlanningLauncher { phase, _, _ -> validPhaseOutcome(phase) },
          invariantsSource = fixtures.invariantsSource,
          manifestFileStore = fixtures.manifestFileStore,
          contextDiscovery = fakeContextDiscovery,
          repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
        ),
      )

    val outcome =
      assertIs<GoalPlanningSweepOutcome.PreparedAll>(
        sweep.prepare(
          fixtures.stateFor(manifest(subtaskCount = 1)),
          fixtures.request().copy(repoRoot = childRoot),
        ),
      )

    assertEquals(
      "repo-root-realpath-v1:${fixtures.repoRoot.toRealPath()}",
      requireNotNull(outcome.identity).repositoryIdentity,
    )
  }

  @Test
  fun `one prepare launches at most one refresh preplan agent even if still stale`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    harness.fixtures.database.repository.skipProvenanceAdvance = true
    harness.manifestFileStore.replaceSpec("spec.md", "# Initial feature contract edited for latch")
    val launchCount = harness.launcher.requests.size

    val outcome = harness.sweep.prepare(state, harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(launchCount + 1, harness.launcher.requests.size, "latch must prevent a second refresh preplan")
  }

  @Test
  fun `resume reuses saved planning when only parent spec status frontmatter is removed`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.manifestFileStore.replaceSpec(
      "spec.md",
      "---\nstatus: Pending\n---\n\n# Initial feature contract",
    )
    val prepared =
      assertIs<GoalPlanningSweepOutcome.PreparedAll>(
        harness.sweep.prepare(state, harness.request()),
      )
    val launchCount = harness.launcher.requests.size
    harness.manifestFileStore.replaceSpec("spec.md", "# Initial feature contract")

    val resumed = harness.sweep.prepare(state, harness.request())

    val outcome = assertIs<GoalPlanningSweepOutcome.PreparedAll>(resumed)
    assertEquals(prepared.provenance, outcome.provenance)
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  @Test
  fun `resume continues with saved planning when a non-status parent spec frontmatter changes`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.manifestFileStore.replaceSpec(
      "spec.md",
      "---\nstatus: Pending\nowner: team-a\n---\n# Initial feature contract",
    )
    harness.sweep.prepare(state, harness.request())
    val launchCount = harness.launcher.requests.size
    val sharedBefore = requireNotNull(harness.fixtures.database.repository.findSharedPreplan(harness.identity()))
    harness.manifestFileStore.replaceSpec(
      "spec.md",
      "---\nowner: team-b\n---\n# Initial feature contract",
    )

    val resumed = harness.sweep.prepare(state, harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(resumed)
    assertEquals(launchCount + 1, harness.launcher.requests.size)
    val sharedAfter = requireNotNull(harness.fixtures.database.repository.findSharedPreplan(harness.identity()))
    assertEquals(sharedBefore.preplanPayload, sharedAfter.preplanPayload)
  }

  @Test
  fun `resume reuses saved planning when the fresh catalog no longer lists a referenced heading`() {
    val discovery = MutableContextDiscovery()
    val harness =
      sweepHarness(SweepHarnessConfig(contextDiscovery = discovery)) { phase, _, _ ->
        if (phase == "preplan") {
          launchFacts(stdout = preplanProsePayload(value = "Preplan prose with catalog context."))
        } else {
          validPhaseOutcome(phase)
        }
      }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val sharedBefore = requireNotNull(harness.fixtures.database.repository.findSharedPreplan(harness.identity()))
    discovery.clearCatalog()

    val outcome = harness.sweep.prepare(state, harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    val sharedAfter = requireNotNull(harness.fixtures.database.repository.findSharedPreplan(harness.identity()))
    assertEquals(
      sharedBefore.preplanPayload,
      sharedAfter.preplanPayload,
      "An absent catalog heading must not discard the accepted preplan.",
    )
  }

  @Test
  fun `recoverability classifier returns Reuse when valid and canonically fresh`() {
    val parentSpec = "# Stable parent contract"
    val checkpoint = recoverabilityCheckpoint(parentSpec = parentSpec)
    val current = checkpoint.provenance.copy(parentSpecHash = sha256HexUtf8(parentSpec))

    val result =
      classifyGoalPlanningProvenanceRecoverability(
        existing = checkpoint,
        current = current,
        savedParentSpec = parentSpec,
        currentParentSpec = parentSpec,
      )

    assertIs<GoalPlanningProvenanceRecoverability.Reuse>(result)
  }

  @Test
  fun `recoverability classifier returns StaleValid when only canonical parent freshness fails`() {
    val savedParent = "# Parent contract v1"
    val currentParent = "# Parent contract v2"
    val checkpoint = recoverabilityCheckpoint(parentSpec = savedParent)
    val current = checkpoint.provenance.copy(parentSpecHash = sha256HexUtf8(currentParent))

    val result =
      classifyGoalPlanningProvenanceRecoverability(
        existing = checkpoint,
        current = current,
        savedParentSpec = savedParent,
        currentParentSpec = currentParent,
      )

    assertIs<GoalPlanningProvenanceRecoverability.StaleValid>(result)
  }

  @Test
  fun `recoverability classifier returns Invalid when decomposition manifest hash drifts`() {
    val parentSpec = "# Parent contract"
    val checkpoint = recoverabilityCheckpoint(parentSpec = parentSpec)
    val current =
      checkpoint.provenance.copy(
        parentSpecHash = sha256HexUtf8(parentSpec),
        decompositionManifestHash = "drifted-manifest-hash",
      )

    val result =
      classifyGoalPlanningProvenanceRecoverability(
        existing = checkpoint,
        current = current,
        savedParentSpec = parentSpec,
        currentParentSpec = parentSpec,
      )

    assertIs<GoalPlanningProvenanceRecoverability.Irrecoverable>(result)
    assertEquals(GoalPlanningRecoveryKind.SCOPED_REPLAN, result.recoveryKind)
  }

  @Test
  fun `recoverability classifier returns Invalid when installed runtime schema ids drift`() {
    val parentSpec = "# Stable parent contract"
    val checkpoint = recoverabilityCheckpoint(parentSpec = parentSpec)
    val current =
      checkpoint.provenance.copy(
        parentSpecHash = sha256HexUtf8(parentSpec),
        planningContractId = "https://installed.example/planning-schema",
        planningContractVersion = "9.9",
        phaseOutputContractId = "https://installed.example/phase-output-schema",
        phaseOutputContractVersion = "9.9",
      )

    val result =
      classifyGoalPlanningProvenanceRecoverability(
        existing = checkpoint,
        current = current,
        savedParentSpec = parentSpec,
        currentParentSpec = parentSpec,
      )

    assertIs<GoalPlanningProvenanceRecoverability.Irrecoverable>(result)
    assertEquals(GoalPlanningRecoveryKind.HARD_RESET, result.recoveryKind)
  }

  @Test
  fun `recoverability classifier returns Invalid when payload sha does not match bytes`() {
    val parentSpec = "# Parent contract"
    val checkpoint = recoverabilityCheckpoint(parentSpec = parentSpec).copy(payloadSha256 = "0".repeat(64))
    val current = checkpoint.provenance.copy(parentSpecHash = sha256HexUtf8(parentSpec))

    val result =
      classifyGoalPlanningProvenanceRecoverability(
        existing = checkpoint,
        current = current,
        savedParentSpec = parentSpec,
        currentParentSpec = parentSpec,
      )

    assertIs<GoalPlanningProvenanceRecoverability.Irrecoverable>(result)
  }

  @Test
  fun `resume rejects saved planning when a governed subtask spec changes`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val launchCount = harness.launcher.requests.size
    harness.manifestFileStore.replaceSpec("spec_subtask_1.md", "# Initial subtask contract edited after planning")

    val outcome = harness.sweep.prepare(state, harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(1, stopped.currentSubtaskId)
    assertEquals("plan", stopped.lastResumableStep)
    assertTrue(
      stopped.blockedReason.contains("skill-bill goal replan SKILL-56 --subtask 1 --include-shared-preplan"),
      stopped.blockedReason,
    )
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  @Test
  fun `resume accepts saved planning when a complete subtask's governed spec changed after completion`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val initial = manifest(subtaskCount = 1)
    harness.sweep.prepare(harness.stateFor(initial), harness.request())
    val launchCount = harness.launcher.requests.size
    harness.manifestFileStore.replaceSpec("spec_subtask_1.md", "# Subtask contract reconciled after completion")
    val resumed =
      initial.copy(
        subtasks =
          initial.subtasks.map { subtask ->
            if (subtask.id == 1) subtask.copy(status = "complete") else subtask
          },
      )

    val outcome = harness.sweep.prepare(harness.stateFor(resumed), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  @Test
  fun `resume accepts a complete local subtask whose governed spec is deleted`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val initial = manifest(subtaskCount = 2)
    harness.sweep.prepare(harness.stateFor(initial), harness.request())
    harness.manifestFileStore.remove("spec_subtask_1.md")
    val launchCount = harness.launcher.requests.size
    val resumed =
      initial.copy(
        subtasks =
          initial.subtasks.map { subtask ->
            if (subtask.id == 1) subtask.copy(status = "complete") else subtask
          },
      )

    val outcome = harness.sweep.prepare(harness.stateFor(resumed), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  @Test
  fun `non-skipped subtask with an allocated workflow remains planning eligible`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val allocated =
      manifest(subtaskCount = 1).let { manifest ->
        manifest.copy(subtasks = manifest.subtasks.map { it.copy(workflowId = "wfl-child") })
      }

    val outcome = harness.sweep.prepare(harness.stateFor(allocated), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(listOf("preplan", "plan"), harness.launcher.phases)
  }

  @Test
  fun `planning emits a progress line per phase in caller order`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val progress = mutableListOf<String>()
    val request =
      harness.request().copy(
        outputSink =
          AgentRunOutputSink { stream, text ->
            if (stream == AgentRunOutputStream.STDERR) progress += text
          },
      )

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), request)

    assertEquals(
      listOf(
        "skill-bill: goal planning - parent goal shared preplan\n",
        "[subtask 1] skill-bill: goal planning - subtask 1 plan\n",
        "[subtask 2] skill-bill: goal planning - subtask 2 plan\n",
      ),
      progress,
    )
  }

  @Test
  fun `resume after a crash between plans continues at the next subtask without rediscovery`() {
    val fixtures = sharedSweepFixtures()
    val discovery = CountingContextDiscovery()
    val runOneLauncher =
      SweepPlanningLauncher { phase, subtaskId, _ ->
        if (subtaskId == 2 && phase == "plan") spawnBlockedOutcome() else validPhaseOutcome(phase)
      }
    val runOne = crashResumeSweepDeps(fixtures, runOneLauncher, discovery)
    val initial = manifest(subtaskCount = 2).copy(specSource = SpecSource.LINEAR)
    val stopped =
      assertIs<GoalPlanningSweepOutcome.Stopped>(
        runOne.prepare(fixtures.stateFor(initial), fixtures.request()),
      )
    assertEquals(2, stopped.currentSubtaskId)
    assertEquals(1, fixtures.preparedCount())
    assertEquals(3, runOneLauncher.requests.size)
    val runTwoLauncher = SweepPlanningLauncher { phase, _, _ -> validPhaseOutcome(phase) }
    val runTwo = crashResumeSweepDeps(fixtures, runTwoLauncher, discovery)
    val resumed =
      initial.copy(
        subtasks =
          initial.subtasks.map { subtask ->
            if (subtask.id == 1) subtask.copy(status = "complete") else subtask
          },
      )
    fixtures.manifestFileStore.remove("spec_subtask_1.md")
    val outcome = runTwo.prepare(fixtures.stateFor(resumed), fixtures.request())
    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(listOf("plan"), runTwoLauncher.phases)
    assertEquals(listOf(2), runTwoLauncher.subtaskIds)
    assertEquals(2, fixtures.preparedCount())
    assertEquals(1, discovery.calls, "resume must recover the durable packet without repeating discovery")
  }

  private fun crashResumeSweepDeps(
    fixtures: SweepFixtures,
    launcher: SweepPlanningLauncher,
    discovery: CountingContextDiscovery,
  ): DefaultGoalPlanningSweep =
    testGoalPlanningSweepPorts(
      GoalPlanningSweepPortsParams(
        checkpoint = fixtures.checkpoint,
        outputValidator = fixtures.outputValidator,
        subtaskLauncher = launcher,
        invariantsSource = fixtures.invariantsSource,
        manifestFileStore = fixtures.manifestFileStore,
        contextDiscovery = discovery,
      ),
    )

  @Test
  fun `unexpected planning launch failure becomes a resumable stopped outcome`() {
    val attempts = mutableListOf<GoalPlanningAttemptRecord>()
    val harness =
      sweepHarness(
        SweepHarnessConfig(
          planningAttemptRecorder = GoalPlanningAttemptRecorder { attempts += it },
        ),
      ) { phase, subtaskId, _ ->
        if (phase == "plan" && subtaskId == 2) {
          error("simulated planning launcher failure")
        }
        validPhaseOutcome(phase)
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(2, stopped.currentSubtaskId)
    assertEquals("plan", stopped.lastResumableStep)
    assertContains(stopped.blockedReason, "simulated planning launcher failure")
    assertEquals(1, harness.preparedCount())
    assertEquals(
      listOf("preplan:SUCCEEDED", "plan:SUCCEEDED", "plan:FAILED"),
      attempts.filter { it.eventKind == GoalProgressEventKind.OPERATION_COMPLETED }.map {
        "${it.phaseId}:${it.outcome}"
      },
    )
    assertEquals(
      3,
      attempts.count { it.eventKind == GoalProgressEventKind.OPERATION_STARTED },
      "every attempt opens in the ledger before it launches, including the one that failed",
    )
  }

  @Test
  fun `blocked planning stops before mutation with the current subtask and resumable state`() {
    val harness = sweepHarness(SweepHarnessConfig(markPreparedThrows = false)) { _, _, _ -> spawnBlockedOutcome() }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, stopped.currentSubtaskId)
    assertEquals("preplan", stopped.lastResumableStep)
    assertEquals(0, harness.preparedCount())
  }

  @Test
  fun `schema valid blocked payload is never checkpointed as prepared`() {
    val harness =
      sweepHarness { phase, _, _ ->
        launchFacts(stdout = phasePayload(phase).replace("\"completed\"", "\"blocked\""))
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals("preplan", stopped.lastResumableStep)
    assertEquals(0, harness.preparedCount())
  }

  @Test
  fun `plan failure resumes at plan while retaining the durable shared preplan`() {
    val discovery = CountingContextDiscovery()
    var failPlan = true
    val harness =
      sweepHarness(SweepHarnessConfig(contextDiscovery = discovery)) { phase, _, _ ->
        val payload =
          if (phase == "plan" && failPlan) {
            phasePayload(phase).replace("\"completed\"", "\"failed\"")
          } else {
            phasePayload(phase)
          }
        launchFacts(stdout = payload)
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(1, stopped.currentSubtaskId)
    assertEquals("plan", stopped.lastResumableStep)
    assertTrue(stopped.blockedReason.contains("'plan' stopped"))
    assertEquals(0, harness.preparedCount())
    assertNotNull(harness.fixtures.database.repository.findSharedPreplan(harness.identity()))

    failPlan = false
    val resumed = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(resumed)
    assertEquals(listOf("preplan", "plan", "plan", "plan", "plan"), harness.launcher.phases)
    assertEquals(1, discovery.calls, "resume after shared-preplan persistence must not repeat discovery")
  }

  @Test
  fun `every planning launch streams for liveness and carries its own budget`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val request =
      harness.request().copy(
        timeout = 5.minutes,
        progressIdleTimeout = 10.minutes,
        planningBudget = 45.minutes,
      )

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), request)

    assertEquals(3, harness.launcher.requests.size)
    harness.launcher.requests.forEach { launch ->
      assertTrue(
        launch.skillRunRequest.streamOutputForLiveness,
        "planning writes no durable progress, so it must prove liveness by streaming output",
      )
      assertEquals(45.minutes, launch.skillRunRequest.timeout, "planning is bounded by its own budget")
      assertEquals(10.minutes, launch.skillRunRequest.progressIdleTimeout, "silence is still bounded")
    }
  }

  @Test
  fun `a disabled planning budget leaves planning bounded only by output silence`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val request = harness.request().copy(progressIdleTimeout = 10.minutes, planningBudget = null)

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), request)

    harness.launcher.requests.forEach { launch ->
      assertNull(launch.skillRunRequest.timeout)
      assertEquals(10.minutes, launch.skillRunRequest.progressIdleTimeout)
      assertTrue(launch.skillRunRequest.streamOutputForLiveness)
    }
  }

  @Test
  fun `an exhausted planning budget names the budget and the flag that raises it`() {
    val harness =
      sweepHarness { _, _, _ ->
        launchFacts(AgentRunTermination.TimedOut, stdout = "")
      }

    val outcome =
      harness.sweep.prepare(
        harness.stateFor(manifest(subtaskCount = 1)),
        harness.request().copy(planningBudget = 45.minutes),
      )

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertTrue(stopped.blockedReason.contains("45m"), stopped.blockedReason)
    assertTrue(stopped.blockedReason.contains("--planning-budget-minutes"), stopped.blockedReason)
    assertEquals(0, harness.preparedCount())
  }

  @Test
  fun `failed launch cannot pass output gate with stale stdout`() {
    val harness =
      sweepHarness { phase, _, _ ->
        launchFacts(AgentRunTermination.Exited(1), stdout = phasePayload(phase))
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, harness.preparedCount())
  }

  @Test
  fun `malformed phase output retries with the schema correction before checkpointing`() {
    var attempts = 0
    val harness =
      sweepHarness { phase, _, _ ->
        if (phase == "preplan" && attempts++ == 0) {
          launchFacts(stdout = "not a json object")
        } else {
          validPhaseOutcome(phase)
        }
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(listOf("preplan", "preplan", "plan"), harness.launcher.phases)
    val retryPrompt = harness.launcher.requests[1].skillRunRequest.promptOverride.orEmpty()
    assertContains(retryPrompt, "Previous attempt was REJECTED by the schema gate")
    assertContains(retryPrompt, "Goal planning phase output was rejected by its schema contract.")
    assertEquals(1, harness.preparedCount())
  }

  @Test
  fun `empty provider turn on a clean exit is retried instead of blocking at once`() {
    var launches = 0
    val harness =
      sweepHarness { phase, _, _ ->
        launches += 1
        if (launches == 1) emptyProviderTurnOutcome() else validPhaseOutcome(phase)
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(listOf("preplan", "preplan", "plan"), harness.launcher.phases)
    assertEquals(1, harness.preparedCount())
  }

  @Test
  fun `empty provider turn retry does not tell the agent its prior output was schema-rejected`() {
    var launches = 0
    val harness =
      sweepHarness { phase, _, _ ->
        launches += 1
        if (launches == 1) emptyProviderTurnOutcome() else validPhaseOutcome(phase)
      }

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val retryPrompt = harness.launcher.requests[1].skillRunRequest.promptOverride.orEmpty()
    assertFalse(
      retryPrompt.contains("Previous attempt was REJECTED by the schema gate"),
      "there is no rejected output to remediate when the provider returned nothing",
    )
  }

  @Test
  fun `an empty provider turn records launch facts rather than a schema verdict`() {
    var launches = 0
    val recorded = mutableListOf<GoalPlanningRejectionRecord>()
    val harness =
      sweepHarness(SweepHarnessConfig(planningRejectionRecorder = { recorded += it })) { phase, _, _ ->
        launches += 1
        if (launches == 1) emptyProviderTurnOutcome() else validPhaseOutcome(phase)
      }

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(
      harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request()),
    )
    val first = recorded.single()
    assertContains(first.reason, "EmptyProviderTurn")
    assertContains(first.reason, "assistantEvents=0")
    assertFalse(first.reason.contains("Tokens"))
    assertFalse(
      first.reason.contains("schema-invalid"),
      "an operator must not be told the schema rejected output that was never produced",
    )
  }
}

class GoalPlanningSweepRejectionTest {
  @Test
  fun `empty provider turns are retained as durable rejection evidence`() {
    var launches = 0
    val recorded = mutableListOf<GoalPlanningRejectionRecord>()
    val harness =
      sweepHarness(SweepHarnessConfig(planningRejectionRecorder = { recorded += it })) { phase, _, _ ->
        launches += 1
        if (launches <= 2) emptyProviderTurnOutcome() else validPhaseOutcome(phase)
      }

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(
      harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request()),
    )

    assertEquals(2, recorded.size)
    assertEquals(listOf(1, 2), recorded.map { it.attempt })
    val first = recorded.first()
    assertEquals("empty-planning-harvest", first.rule)
    assertEquals("preplan", first.phaseId)
    assertEquals("claude", first.agentId)
    assertContains(first.reason, "EmptyProviderTurn")
    assertEquals("{\"type\":\"result\",\"result\":\"\"}", first.rawEvidence)
  }

  @Test
  fun `a deliberate agent block reports the envelope's own account and retains it durably`() {
    val recorded = mutableListOf<GoalPlanningRejectionRecord>()
    val harness =
      sweepHarness(SweepHarnessConfig(planningRejectionRecorder = { recorded += it })) { phase, _, _ ->
        if (phase == "preplan") {
          validPhaseOutcome(phase)
        } else {
          launchFacts(stdout = blockedPhasePayload(phase, "spec names no persistence boundary to plan against"))
        }
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertContains(stopped.blockedReason, "spec names no persistence boundary to plan against")
    assertContains(stopped.blockedReason, "needs_user_action")
    val rejection = recorded.single { it.rule == "planning-unsuccessful-status" }
    assertContains(rejection.rawEvidence, "\"status\":\"blocked\"")
  }

  @Test
  fun `a retryable decline relaunches instead of discarding every settled plan`() {
    val recorded = mutableListOf<GoalPlanningRejectionRecord>()
    var planLaunches = 0
    val harness =
      sweepHarness(SweepHarnessConfig(planningRejectionRecorder = { recorded += it })) { phase, _, _ ->
        if (phase == "preplan") {
          validPhaseOutcome(phase)
        } else {
          planLaunches += 1
          if (planLaunches == 1) {
            launchFacts(stdout = blockedPhasePayload(phase, "transient provider capacity", "retryable"))
          } else {
            validPhaseOutcome(phase)
          }
        }
      }

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(
      harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request()),
    )
    val decline = recorded.single { it.rule == "planning-retryable-decline" }
    assertContains(decline.reason, "transient provider capacity")
  }

  @Test
  fun `a decline that never resolves stops instead of relaunching forever`() {
    var planLaunches = 0
    val harness =
      sweepHarness { phase, _, _ ->
        if (phase == "preplan") {
          validPhaseOutcome(phase)
        } else {
          planLaunches += 1
          launchFacts(stdout = blockedPhasePayload(phase, "transient provider capacity", "retryable"))
        }
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertContains(stopped.blockedReason, "the decline is not transient")
    assertEquals(3, planLaunches, "a retryable disposition is believed transient a bounded number of times")
  }

  @Test
  fun `a retryable decline retry is not told its prior output failed the schema gate`() {
    var planLaunches = 0
    val harness =
      sweepHarness { phase, _, _ ->
        if (phase == "preplan") {
          validPhaseOutcome(phase)
        } else {
          planLaunches += 1
          if (planLaunches == 1) {
            launchFacts(stdout = blockedPhasePayload(phase, "transient provider capacity", "retryable"))
          } else {
            validPhaseOutcome(phase)
          }
        }
      }

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(
      harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request()),
    )
    val retryPrompt = harness.launcher.requests.last().skillRunRequest.promptOverride.orEmpty()
    assertFalse(
      retryPrompt.contains("Previous attempt was REJECTED by the schema gate"),
      "a well-formed declined envelope was never rejected by the schema gate",
    )
  }

  @Test
  fun `each subtask's planning rejections stay independently addressable`() {
    val recorded = mutableListOf<GoalPlanningRejectionRecord>()
    var planLaunches = 0
    val harness =
      sweepHarness(SweepHarnessConfig(planningRejectionRecorder = { recorded += it })) { phase, _, _ ->
        if (phase == "preplan") {
          validPhaseOutcome(phase)
        } else {
          planLaunches += 1
          if (planLaunches % 2 == 1) emptyProviderTurnOutcome() else validPhaseOutcome(phase)
        }
      }

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(
      harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 2)), harness.request()),
    )

    val planRejections = recorded.filter { it.phaseId.startsWith("plan") }
    assertEquals(listOf("plan:1", "plan:2"), planRejections.map { it.phaseId })
    assertEquals(listOf(1, 1), planRejections.map { it.attempt })
  }

  @Test
  fun `a non-zero exit still blocks immediately rather than burning the retry budget`() {
    val harness =
      sweepHarness { _, _, _ ->
        agentRunLaunchFacts(
          agent = SupportedAgent.CLAUDE,
          termination = AgentRunTermination.Exited(2),
          stdout = "",
          stderr = "boom",
        )
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(1, harness.launcher.phases.size)
    assertContains(stopped.blockedReason, "exited with status 2")
  }

  @Test
  fun `persistence failure rolls back leaving no half pair and stops before mutation`() {
    val harness =
      sweepHarness(
        SweepHarnessConfig(
          markPreparedThrows = true,
        ),
      ) { phase, _, _ -> validPhaseOutcome(phase) }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, stopped.currentSubtaskId)
    assertEquals(0, harness.preparedCount())
  }

  @Test
  fun `a settled preplan with blank prose stops the sweep durably at plan launch`() {
    val fixtures = sharedSweepFixtures()
    settleSharedPreplanThenBlankProse(fixtures)
    val launcher = SweepPlanningLauncher { phase, _, _ -> validPhaseOutcome(phase) }
    val stopped =
      assertIs<GoalPlanningSweepOutcome.Stopped>(
        sweepFromFixtures(
          fixtures,
          launcher,
        ).prepare(fixtures.stateFor(manifest(subtaskCount = 1)), fixtures.request()),
      )
    assertBlankProsePlanLaunchStop(stopped, launcher)
  }

  @Test
  fun `plan checkpoint failure resumes at plan after retaining the shared preplan`() {
    val harness =
      sweepHarness(
        SweepHarnessConfig(
          planCheckpointThrows = true,
        ),
      ) { phase, _, _ -> validPhaseOutcome(phase) }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(1, stopped.currentSubtaskId)
    assertEquals("plan", stopped.lastResumableStep)
    assertEquals(0, harness.preparedCount())
  }

  @Test
  fun `all plans gate blocks every child activation while a plan is missing`() {
    val fixtures = sharedSweepFixtures()
    val sharedLauncher =
      SweepPlanningLauncher { phase, subtaskId, _ ->
        if (subtaskId == 2) {
          launchFacts(stdout = phasePayload(phase).replace("\"completed\"", "\"blocked\""))
        } else {
          validPhaseOutcome(phase)
        }
      }
    val sweep =
      testGoalPlanningSweepPorts(
        GoalPlanningSweepPortsParams(
          checkpoint = fixtures.checkpoint,
          outputValidator = fixtures.outputValidator,
          subtaskLauncher = sharedLauncher,
          invariantsSource = fixtures.invariantsSource,
          manifestFileStore = fixtures.manifestFileStore,
          contextDiscovery = fakeContextDiscovery,
        ),
      )
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val runner =
      testGoalRunner(
        goalRunnerDeps(
          manifestStore = store,
          subtaskLauncher = sharedLauncher,
          outcomeStore = RecordingOutcomeStore(),
          pullRequestPort = RecordingPullRequestPort(),
        ).copy(
          goalPlanningSweep = sweep,
        ),
      )

    val report = runner.run(fixtures.request())

    assertIs<GoalRunnerRunReport.Stopped>(report)
    assertTrue(sharedLauncher.requests.isNotEmpty(), "the sweep must have attempted planning before stopping")
    assertTrue(
      sharedLauncher.requests.all { it.skillRunRequest.promptOverride != null },
      "every launch while a plan is missing must be a planning prompt, not a child activation",
    )
    assertTrue(
      sharedLauncher.requests.all { it.skillRunRequest.goalContinuation == null },
      "no child workflow may be activated until every included plan is prepared",
    )
  }

  @Test
  fun `planning payloads are persisted as strict canonical json even when the agent fences output with prose`() {
    val harness =
      sweepHarness(SweepHarnessConfig(outputValidator = FenceAwarePhaseOutputValidator())) { phase, _, _ ->
        launchFacts(stdout = fencedPhasePayload(phase))
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    val record = harness.recordFor(1)
    assertNotNull(record, "the shared preplan and subtask plan must be persisted")
    val preplanMap =
      JsonCodec.parseObjectOrNull(record.preplanPayload)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
    val planMap =
      JsonCodec.parseObjectOrNull(record.planPayload)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
    assertNotNull(preplanMap, "preplan payload must be strict JSON, not fenced prose")
    assertNotNull(planMap, "plan payload must be strict JSON, not fenced prose")
    assertFalse(record.preplanPayload.contains("```") || record.preplanPayload.contains("Here is"))
    assertFalse(record.planPayload.contains("```") || record.planPayload.contains("Here is"))
    assertEquals("preplan", preplanMap["phase_id"])
    assertEquals("plan", planMap["phase_id"])
  }

  @Test
  fun `missing or unreadable shared governed spec stops before mutation with a clear pre sweep state`() {
    val outputValidator = FakePhaseOutputValidator()
    val database = InMemoryPreparationDatabase()
    val checkpoint =
      GoalPlanningPreparationCheckpoint(
        database = database,
        envelopeValidator = NoopGoalPlanningPreparationEnvelopeValidator,
        phaseOutputValidator = outputValidator,
        planningProjectionValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
      )
    val launcher = SweepPlanningLauncher { phase, _, _ -> validPhaseOutcome(phase) }
    val sweep =
      testGoalPlanningSweepPorts(
        GoalPlanningSweepPortsParams(
          checkpoint = checkpoint,
          outputValidator = outputValidator,
          subtaskLauncher = launcher,
          invariantsSource = FakeInvariantsSource(),
          manifestFileStore = ThrowingManifestFileStore(),
          contextDiscovery = fakeContextDiscovery,
        ),
      )
    val state =
      GoalRunnerManifestState(
        parentWorkflowId = "wfl-parent",
        dbPath = "/fake/goal-planning-sweep-preparations.db",
        manifest = manifest(subtaskCount = 2),
      )
    val request =
      GoalRunnerRunRequest(
        issueKey = "SKILL-56",
        repoRoot = Files.createTempDirectory("goal-planning-sweep"),
        invokedAgentId = "claude",
      )

    val outcome = sweep.prepare(state, request)

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, stopped.currentSubtaskId)
    assertEquals("preplan", stopped.lastResumableStep)
    assertTrue(stopped.blockedReason.contains("shared context could not be gathered"))
    assertEquals(0, launcher.requests.size, "no planning agent launches before shared discovery succeeds")
  }
}

class GoalPlanningSweepTimingTest {
  @Test
  fun `incompatible provenance on only a stored plan identifies that plan and stops before another launch`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())
    harness.fixtures.database.repository.corruptPlanProvenance(1)
    val launchCountBeforeResume = harness.launcher.requests.size

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(1, stopped.currentSubtaskId)
    assertEquals("plan", stopped.lastResumableStep)
    assertTrue(
      stopped.blockedReason.contains("skill-bill goal replan SKILL-56 --subtask 1 --include-shared-preplan"),
      stopped.blockedReason,
    )
    assertEquals(
      launchCountBeforeResume,
      harness.launcher.requests.size,
      "no planning launch occurs once incompatible provenance is rejected",
    )
  }

  @Test
  fun `recovered shared packet requires every governed context field even with valid integrity`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val prepared = requireNotNull(harness.recordFor(1))
    harness.fixtures.database.repository.markPrepared(
      prepared.withSharedPacket { packet -> packet - "validation_guidance" },
    )

    val outcome = harness.sweep.prepare(state, harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, stopped.currentSubtaskId)
    assertTrue(stopped.blockedReason.contains("shared context could not be gathered"))
  }

  @Test
  fun `recovered shared packet rejects missing or unknown planning dispositions`() {
    listOf<(Map<String, Any?>) -> Map<String, Any?>>(
      { subtask -> subtask - "planning_disposition" },
      { subtask -> subtask + ("planning_disposition" to "unknown") },
    ).forEach { corruptDisposition ->
      val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
      val state = harness.stateFor(manifest(subtaskCount = 1))
      harness.sweep.prepare(state, harness.request())
      val prepared = requireNotNull(harness.recordFor(1))
      val launchCount = harness.launcher.requests.size
      harness.fixtures.database.repository.markPrepared(
        prepared.withSharedPacket { packet ->
          val ordered = packet["ordered_subtasks"] as List<*>
          val first = requireNotNull(JsonCodec.anyToStringAnyMap(ordered.first()))
          packet + ("ordered_subtasks" to listOf(corruptDisposition(first)))
        },
      )

      val outcome = harness.sweep.prepare(state, harness.request())

      val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
      assertEquals(0, stopped.currentSubtaskId)
      assertTrue(stopped.blockedReason.contains("shared context could not be gathered"))
      assertEquals(launchCount, harness.launcher.requests.size)
    }
  }

  @Test
  fun `normalized recovery reads the singleton shared packet independently from prepared plans`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 2))
    harness.sweep.prepare(state, harness.request())
    val second = requireNotNull(harness.recordFor(2))
    harness.fixtures.database.repository.markPrepared(
      second.withSharedPacket { packet -> packet + ("validation_guidance" to "different valid guidance") },
    )

    val outcome = harness.sweep.prepare(state, harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
  }

  @Test
  fun `prepared row without a shared packet reports its known subtask`() {
    val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
    val state = harness.stateFor(manifest(subtaskCount = 1))
    harness.sweep.prepare(state, harness.request())
    val prepared = requireNotNull(harness.recordFor(1))
    harness.fixtures.database.repository.markPrepared(prepared.withoutSharedPacket())

    val outcome = harness.sweep.prepare(state, harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(0, stopped.currentSubtaskId)
    assertTrue(stopped.blockedReason.contains("does not contain a valid shared context packet"))
    assertTrue(
      stopped.blockedReason.contains("skill-bill goal replan SKILL-56 --subtask 1 --include-shared-preplan"),
      stopped.blockedReason,
    )
  }

  @Test
  fun `missing local or pending Linear sub-spec invalidates prepared provenance`() {
    listOf(
      manifest(subtaskCount = 1),
      manifest(subtaskCount = 1).copy(specSource = SpecSource.LINEAR),
    ).forEach { manifest ->
      val harness = sweepHarness { phase, _, _ -> validPhaseOutcome(phase) }
      harness.sweep.prepare(harness.stateFor(manifest), harness.request())
      harness.manifestFileStore.remove("spec_subtask_1.md")

      val outcome = harness.sweep.prepare(harness.stateFor(manifest), harness.request())

      val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
      assertEquals(0, stopped.currentSubtaskId)
      assertTrue(stopped.blockedReason.contains("provenance"))
    }
  }

  @Test
  fun `a plan child that first omits value relaunches once and checkpoints only the valid plan`() {
    var planAttempts = 0
    val harness =
      sweepHarness(
        SweepHarnessConfig(
          outputValidator = realFeatureTaskRuntimePhaseOutputValidator,
          planningProjectionValidator = realPlanningProjectionValidator,
        ),
      ) { phase, _, _ ->
        if (phase != "plan") {
          validPhaseOutcome(phase)
        } else {
          planAttempts += 1
          if (planAttempts == 1) launchFacts(stdout = missingValuePlanPayload()) else validPhaseOutcome(phase)
        }
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(2, planAttempts, "the invalid plan must relaunch exactly once before the valid one settles")
    val retryPrompt = harness.launcher.requests.last().skillRunRequest.promptOverride.orEmpty()
    assertTrue(
      retryPrompt.contains("value"),
      "the relaunch prompt must carry the phase-output validation detail as priorSchemaFailure",
    )
    val record = assertNotNull(harness.recordFor(1))
    assertFalse(
      record.planPayload.contains(""""prompt":"optional only""""),
      "only the phase-output-valid plan may be checkpointed",
    )
  }

  @Test
  fun `planning projection retries are recorded with durable attempt outcomes`() {
    val attempts = mutableListOf<String>()
    var launchCount = 0
    val harness =
      sweepHarness(
        SweepHarnessConfig(
          outputValidator = realFeatureTaskRuntimePhaseOutputValidator,
          planningProjectionValidator = realPlanningProjectionValidator,
          planningAttemptRecorder =
            GoalPlanningAttemptRecorder { record ->
              if (record.eventKind == GoalProgressEventKind.OPERATION_COMPLETED) {
                attempts += "${record.phaseId}:${record.subtaskId}:${record.attempt}:${record.outcome.wireValue}"
              }
            },
        ),
      ) { phase, _, _ ->
        launchCount += 1
        if (phase == "plan" && launchCount == 2) {
          launchFacts(stdout = missingValuePlanPayload())
        } else {
          validPhaseOutcome(phase)
        }
      }

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(
      harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request()),
    )
    assertEquals(
      listOf(
        "preplan:0:1:succeeded",
        "plan:1:1:failed",
        "plan:1:2:succeeded",
      ),
      attempts,
    )
  }

  @Test
  fun `preplan settles without consulting the planning projection producer gate`() {
    val labels = mutableListOf<String>()
    val validator =
      object : FeatureTaskRuntimeWireArtifactValidator {
        override fun validate(
          kind: FeatureTaskRuntimeWireArtifactKind,
          payload: FeatureTaskRuntimeWorkflowArtifactMap,
          sourceLabel: String,
        ) {
          if (kind == FeatureTaskRuntimeWireArtifactKind.PLANNING_PROJECTION) {
            labels += sourceLabel
          }
        }
      }
    val harness =
      sweepHarness(SweepHarnessConfig(planningProjectionValidator = validator)) { phase, _, _ ->
        if (phase == "plan") {
          launchFacts(stdout = phasePayload(phase).replace("\"completed\"", "\"blocked\""))
        } else {
          validPhaseOutcome(phase)
        }
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(1, harness.launcher.phases.count { it == "preplan" })
    assertTrue(
      labels.none { it.startsWith("preplan#") },
      "producedProjectionKindFor(preplan) is null, so the producer gate must not validate preplan prose",
    )
  }

  @Test
  fun `preplan repair evidence survives enrichment and checkpoint persistence`() {
    val evidence =
      FeatureTaskRuntimePhaseOutputRepairEvidence(
        format = FeatureTaskRuntimePhaseOutputFormat.JSON,
        originalDigest = sha256HexUtf8(RAW_REPAIRED_PREPLAN),
        repairedDigest = sha256HexUtf8(REPAIRED_PREPLAN_PAYLOAD),
        operation = FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
        sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation("preplan", 0, 1, 1),
      )
    val harness =
      sweepHarness(
        SweepHarnessConfig(
          outputValidator = RepairingPreplanOutputValidator(evidence),
        ),
      ) { phase, _, _ ->
        if (phase == "preplan") launchFacts(stdout = RAW_REPAIRED_PREPLAN) else validPhaseOutcome(phase)
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    val shared = requireNotNull(harness.fixtures.database.repository.findSharedPreplan(harness.identity()))
    assertEquals(evidence, shared.repairEvidence)
    assertNotNull(harness.recordFor(1), "the repaired shared preplan must reach the plan checkpoint")
  }

  @Test
  fun `empty provider turn backoff waits grow before attempts two and three`() {
    val timing = RecordingRuntimeTimingPort()
    val schedule =
      GoalPlanningBurstSchedule(
        planFanOutCap = GoalPlanningBurstSchedule.DEFAULT_PLAN_FAN_OUT_CAP,
        emptyTurnBackoffBase = 30.seconds,
        emptyTurnBackoffFactor = 2,
        waitSlice = 60.seconds,
      )
    var launches = 0
    val harness =
      sweepHarness(SweepHarnessConfig(timingPort = timing, burstSchedule = schedule)) { phase, _, _ ->
        launches += 1
        if (launches <= 2) emptyProviderTurnOutcome() else validPhaseOutcome(phase)
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(listOf(30.seconds, 60.seconds), timing.waits)
  }

  @Test
  fun `a pause requested while a wave is in flight returns PAUSED and dispatches no further wave`() {
    val pauseStore = MutablePauseGoalPlanningManifestStore()
    val harness =
      sweepHarness(
        SweepHarnessConfig(
          manifestStore = pauseStore,
          burstSchedule =
            GoalPlanningBurstSchedule(
              planFanOutCap = 2,
              emptyTurnBackoffBase = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_BASE,
              emptyTurnBackoffFactor = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_FACTOR,
              waitSlice = GoalPlanningBurstSchedule.DEFAULT_WAIT_SLICE,
            ),
        ),
      ) { phase, subtaskId, _ ->
        if (phase == "plan" && subtaskId == 1) pauseStore.pauseRequested = true
        validPhaseOutcome(phase)
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 4)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(GoalRunnerStopReason.PAUSED, stopped.reason)
    assertEquals(listOf("preplan", "plan"), harness.launcher.phases)
    assertNotNull(
      harness.recordFor(1),
      "the draining wave's valid plan must be checkpointed before PAUSED is returned",
    )
  }

  @Test
  fun `an interrupt during wait stops with the launch-interrupt terminal shape`() {
    val timing = RecordingRuntimeTimingPort(result = RuntimeWaitResult.INTERRUPTED)
    var launches = 0
    val harness =
      sweepHarness(SweepHarnessConfig(timingPort = timing)) { phase, _, _ ->
        launches += 1
        if (launches == 2) emptyProviderTurnOutcome() else validPhaseOutcome(phase)
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 1)), harness.request())

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(GoalRunnerStopReason.BLOCKED, stopped.reason)
    assertContains(stopped.blockedReason, "interrupted")
    assertFalse(
      stopped.blockedReason.contains("failed before its output could be checkpointed"),
      "a wait interrupt must not be laundered as unexpectedPlanningFailure",
    )
    assertEquals(listOf("preplan", "plan"), harness.launcher.phases)
  }
}

class GoalPlanningSweepFanOutTest {
  @Test
  fun `a five subtask goal plans the whole wave with all five units in flight together`() {
    val fanOut = BarrierFanOutPort()
    val harness = sweepHarness(SweepHarnessConfig(fanOutPort = fanOut)) { phase, _, _ -> validPhaseOutcome(phase) }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 5)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(listOf(5), fanOut.waveSizes)
    assertEquals(5, fanOut.maxObservedConcurrency, "all five plans must be in flight before any of them completes")
    assertEquals(5, harness.preparedCount())
  }

  @Test
  fun `twelve subtasks plan in capped waves and never exceed the fan-out cap`() {
    val fanOut = BarrierFanOutPort()
    val harness = sweepHarness(SweepHarnessConfig(fanOutPort = fanOut)) { phase, _, _ -> validPhaseOutcome(phase) }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 12)), harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(listOf(5, 5, 2), fanOut.waveSizes)
    assertEquals(listOf(5, 5, 5), fanOut.requestedCaps)
    assertEquals(5, fanOut.maxObservedConcurrency)
    assertEquals(12, harness.preparedCount())
  }

  @Test
  fun `a blocked wave member leaves its siblings checkpointed and resume replans only the gap`() {
    val fanOut = BarrierFanOutPort()
    var blockThird = true
    val harness =
      sweepHarness(SweepHarnessConfig(fanOutPort = fanOut)) { phase, subtaskId, _ ->
        if (phase == "plan" && subtaskId == 3 && blockThird) {
          launchFacts(stdout = blockedPhasePayload(phase, "subtask three names no boundary to plan against"))
        } else {
          validPhaseOutcome(phase)
        }
      }
    val state = harness.stateFor(manifest(subtaskCount = 4))

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(harness.sweep.prepare(state, harness.request()))

    assertEquals(3, stopped.currentSubtaskId)
    assertEquals(listOf(1, 2, 4), (1..4).filter { harness.recordFor(it) != null })

    blockThird = false
    harness.launcher.subtaskIds.clear()
    val resumed = harness.sweep.prepare(state, harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(resumed)
    assertEquals(listOf(3), harness.launcher.subtaskIds, "resume must replan only the subtask that never settled")
    assertEquals(4, harness.preparedCount())
  }

  @Test
  fun `two blocked units in one wave stop on the same subtask whichever finishes first`() {
    fun harnessFor(
      order: FanOutBodyOrder,
      rejections: MutableList<GoalPlanningRejectionRecord>,
    ) = sweepHarness(
      SweepHarnessConfig(
        planningRejectionRecorder = { record -> synchronized(rejections) { rejections += record } },
        fanOutPort = BarrierFanOutPort(order),
      ),
    ) { phase, subtaskId, _ ->
      if (phase == "plan" && subtaskId in listOf(2, 3)) {
        launchFacts(stdout = blockedPhasePayload(phase, "subtask $subtaskId names no boundary to plan against"))
      } else {
        validPhaseOutcome(phase)
      }
    }

    val forwardRejections = mutableListOf<GoalPlanningRejectionRecord>()
    val forwardHarness = harnessFor(FanOutBodyOrder.FORWARD, forwardRejections)
    val forward =
      assertIs<GoalPlanningSweepOutcome.Stopped>(
        forwardHarness.sweep.prepare(forwardHarness.stateFor(manifest(subtaskCount = 4)), forwardHarness.request()),
      )
    val reversedHarness = harnessFor(FanOutBodyOrder.REVERSED, mutableListOf())
    val reversed =
      assertIs<GoalPlanningSweepOutcome.Stopped>(
        reversedHarness.sweep.prepare(reversedHarness.stateFor(manifest(subtaskCount = 4)), reversedHarness.request()),
      )

    assertEquals(2, forward.currentSubtaskId, "the stop must come from the lowest failing subtask in input order")
    assertEquals(forward.currentSubtaskId, reversed.currentSubtaskId)
    assertEquals(forward.blockedReason, reversed.blockedReason)
    assertEquals(forward.lastResumableStep, reversed.lastResumableStep)
    assertEquals(
      2,
      forwardRejections.count { it.rule == "planning-unsuccessful-status" },
      "a losing peer must still have its verdict recorded rather than being discarded",
    )
  }

  @Test
  fun `concurrent units forward only whole attributed lines to the shared sink`() {
    val written = mutableListOf<String>()
    val interleave = CountDownLatch(3)
    val harness =
      sweepHarness(SweepHarnessConfig(fanOutPort = BarrierFanOutPort())) { phase, subtaskId, request ->
        if (phase == "plan") {
          val unitSink = request.skillRunRequest.outputSink
          unitSink.write(AgentRunOutputStream.STDOUT, "chunk-a-$subtaskId ")
          interleave.countDown()
          check(interleave.await(FAN_OUT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "units never interleaved" }
          unitSink.write(AgentRunOutputStream.STDOUT, "chunk-b-$subtaskId\n")
        }
        validPhaseOutcome(phase)
      }
    val sink = AgentRunOutputSink { _, text -> synchronized(written) { written += text } }

    harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 3)), harness.request(sink))

    val chunked = written.filter { "chunk-" in it }
    assertEquals(3, chunked.size, "each unit's split chunks must reach the sink as exactly one whole line")
    (1..3).forEach { id -> assertContains(chunked, "[subtask $id] chunk-a-$id chunk-b-$id\n") }
  }

  @Test
  fun `a wave unit that raises outside the attempt gate stops on its own subtask`() {
    val harness =
      sweepHarness(SweepHarnessConfig(fanOutPort = BarrierFanOutPort())) { phase, subtaskId, request ->
        if (phase == "plan" && subtaskId == 2) {
          request.skillRunRequest.outputSink.write(AgentRunOutputStream.STDOUT, "tail chunk with no newline")
        }
        validPhaseOutcome(phase)
      }
    val sink =
      AgentRunOutputSink { _, text ->
        if ("[subtask 2]" in text) error("plan output stream closed")
      }

    val outcome = harness.sweep.prepare(harness.stateFor(manifest(subtaskCount = 3)), harness.request(sink))

    val stopped = assertIs<GoalPlanningSweepOutcome.Stopped>(outcome)
    assertEquals(2, stopped.currentSubtaskId, "the raising unit must name its own subtask, not a sibling")
    assertEquals("plan", stopped.lastResumableStep)
    assertContains(stopped.blockedReason, "failed before its output could be checkpointed")
    assertContains(stopped.blockedReason, "plan output stream closed")
  }

  @Test
  fun `a prose drift refresh settles the shared preplan before dispatching a new plan wave`() {
    val fanOut = BarrierFanOutPort()
    var preplanLaunches = 0
    var wavesAtLastPreplan = -1
    val harness =
      sweepHarness(SweepHarnessConfig(fanOutPort = fanOut)) { phase, _, _ ->
        if (phase == "preplan") {
          preplanLaunches += 1
          wavesAtLastPreplan = fanOut.waveSizes.size
          launchFacts(stdout = preplanProsePayload(value = "Preplan prose revision $preplanLaunches."))
        } else {
          validPhaseOutcome(phase)
        }
      }
    val state = harness.stateFor(manifest(subtaskCount = 2))
    assertIs<GoalPlanningSweepOutcome.PreparedAll>(harness.sweep.prepare(state, harness.request()))
    assertEquals(listOf(2), fanOut.waveSizes)
    harness.manifestFileStore.replaceSpec("spec.md", "# Initial feature contract edited for prose drift")

    val outcome = harness.sweep.prepare(state, harness.request())

    assertIs<GoalPlanningSweepOutcome.PreparedAll>(outcome)
    assertEquals(2, preplanLaunches, "prose drift must re-produce the shared preplan")
    assertEquals(1, wavesAtLastPreplan, "the refresh preplan must launch before any post-cascade plan wave")
    assertEquals(2, fanOut.waveSizes.size, "the post-cascade regeneration must dispatch its own wave")
  }
}

private const val FAN_OUT_TIMEOUT_SECONDS = 30L

private enum class FanOutBodyOrder {
  CONCURRENT,
  FORWARD,
  REVERSED,
}

private class BarrierFanOutPort(
  private val bodyOrder: FanOutBodyOrder = FanOutBodyOrder.CONCURRENT,
) : BoundedWorkFanOutPort {
  val requestedCaps = mutableListOf<Int>()
  val waveSizes = mutableListOf<Int>()
  private val exclusion = Any()
  private val inFlight = AtomicInteger()
  private val peak = AtomicInteger()

  val maxObservedConcurrency: Int get() = peak.get()

  override fun <T> runBounded(
    maxInFlight: Int,
    units: List<() -> T>,
  ): List<Result<T>> {
    runExclusively {
      requestedCaps += maxInFlight
      waveSizes += units.size
    }
    val entered = CountDownLatch(units.size)
    val order =
      when (bodyOrder) {
        FanOutBodyOrder.CONCURRENT -> null
        FanOutBodyOrder.FORWARD -> units.indices.toList()
        FanOutBodyOrder.REVERSED -> units.indices.reversed().toList()
      }
    val turnstiles = order?.associateWith { CountDownLatch(1) }
    val successor = order?.zipWithNext()?.toMap()
    val results = MutableList<Result<T>?>(units.size) { null }
    val threads =
      units.mapIndexed { index, unit ->
        Thread {
          peak.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
          entered.countDown()
          awaitFanOut(entered, "wave never fully entered")
          turnstiles?.getValue(index)?.let { awaitFanOut(it, "unit $index was never released") }
          results[index] = runCatching { unit() }
          successor?.get(index)?.let { turnstiles?.getValue(it)?.countDown() }
          inFlight.decrementAndGet()
        }
      }
    threads.forEach(Thread::start)
    order?.firstOrNull()?.let { turnstiles?.getValue(it)?.countDown() }
    threads.forEach { it.join(FAN_OUT_TIMEOUT_SECONDS * MILLIS_PER_SECOND) }
    return results.mapIndexed { index, result ->
      result ?: Result.failure<T>(IllegalStateException("fan-out unit $index never produced a result"))
    }
  }

  override fun <T> runExclusively(action: () -> T): T = synchronized(exclusion) { action() }

  private fun awaitFanOut(
    latch: CountDownLatch,
    failure: String,
  ) {
    check(latch.await(FAN_OUT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { failure }
  }

  private companion object {
    const val MILLIS_PER_SECOND = 1_000L
  }
}

private fun missingValuePlanPayload(): String =
  """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"plan",""" +
    """"status":"completed","summary":"s","produced_outputs":""" +
    """{"prompt":"optional only"}}"""

private const val RAW_REPAIRED_PREPLAN = "raw preplan repaired before enrichment"

private const val REPAIRED_PREPLAN_PAYLOAD =
  """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"preplan",""" +
    """"status":"completed","summary":"s","produced_outputs":{"value":"fixture preplan prose"}}"""

private class RepairingPreplanOutputValidator(
  private val evidence: FeatureTaskRuntimePhaseOutputRepairEvidence,
) : FeatureTaskRuntimePhaseOutputTestValidator() {
  override fun validatePhaseOutput(
    phaseOutputText: String,
    sourceLabel: String,
  ): FeatureTaskRuntimePhaseOutputValidationResult {
    if (sourceLabel == "preplan" && phaseOutputText == RAW_REPAIRED_PREPLAN) {
      return FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair(
        normalizedOutput = normalizedPlanningOutput(REPAIRED_PREPLAN_PAYLOAD),
        evidence = evidence,
      )
    }
    return FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged(
      normalizedPlanningOutput(phaseOutputText),
    )
  }

  override fun validatePhaseOutputText(
    phaseOutputText: String,
    sourceLabel: String,
  ) {
    validatePhaseOutput(phaseOutputText, sourceLabel)
  }
}

private fun legacyV01Packet(
  subtasks: List<DecompositionSubtask>,
  platformPacks: Map<String, String>,
): Map<String, Any?> {
  val body =
    linkedMapOf<String, Any?>(
      "packet_version" to GoalPlanningSharedContextPacket.LEGACY_VERSION_0_1,
      "repository_identity" to "repo-root-realpath-v1:/tmp/fixture",
      "normalized_issue_key" to "SKILL-172",
      "parent_spec_path" to ".feature-specs/SKILL-172/spec.md",
      "parent_spec" to "parent body",
      "decomposition_manifest" to "contract_version: \"0.1\"\nissue_key: SKILL-172\n",
      "platform_packs" to platformPacks,
      "boundary_memory" to
        mapOf(
          "platform-packs/kotlin/agent/history.md" to "prior decision",
        ),
      "validation_guidance" to "repo conventions",
      "ordered_subtasks" to GoalPlanningSharedContextPacket.orderedSubtasks(subtasks),
    )
  return body + ("integrity_sha256" to GoalPlanningSharedContextPacket.digest(body))
}

private fun legacyV02Packet(
  subtasks: List<DecompositionSubtask>,
  boundaryMemory: Map<String, String>,
): Map<String, Any?> {
  val body =
    linkedMapOf<String, Any?>(
      "packet_version" to GoalPlanningSharedContextPacket.LEGACY_VERSION_0_2,
      "repository_identity" to "repo-root-realpath-v1:/tmp/fixture",
      "normalized_issue_key" to "SKILL-172",
      "parent_spec_path" to ".feature-specs/SKILL-172/spec.md",
      "parent_spec" to "parent body",
      "decomposition_manifest" to "contract_version: \"0.1\"\nissue_key: SKILL-172\n",
      "boundary_memory" to boundaryMemory,
      "validation_guidance" to "repo conventions",
      "ordered_subtasks" to GoalPlanningSharedContextPacket.orderedSubtasks(subtasks),
    )
  return body + ("integrity_sha256" to GoalPlanningSharedContextPacket.digest(body))
}

private fun legacyV03Packet(
  subtasks: List<DecompositionSubtask>,
  boundaryMemory: Map<String, String>,
): Map<String, Any?> {
  val body =
    linkedMapOf<String, Any?>(
      "packet_version" to GoalPlanningSharedContextPacket.LEGACY_VERSION_0_3,
      "repository_identity" to "repo-root-realpath-v1:/tmp/fixture",
      "normalized_issue_key" to "SKILL-172",
      "parent_spec_path" to ".feature-specs/SKILL-172/spec.md",
      "parent_spec" to "parent body",
      "decomposition_manifest" to "contract_version: \"0.1\"\nissue_key: SKILL-172\n",
      "boundary_memory" to boundaryMemory,
      "validation_guidance" to "repo conventions",
      "ordered_subtasks" to GoalPlanningSharedContextPacket.orderedSubtasks(subtasks),
    )
  return body + ("integrity_sha256" to GoalPlanningSharedContextPacket.digest(body))
}

private fun normalizedPlanningOutput(payload: String): NormalizedFeatureTaskRuntimePhaseOutput {
  val envelope =
    JsonCodec.parseObjectOrNull(payload)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?: error("fixture phase output is not an object")
  return NormalizedFeatureTaskRuntimePhaseOutput(
    canonicalJson = JsonCodec.mapToJsonString(envelope),
    envelope = envelope,
  )
}

private fun GoalPlanningPreparationRecord.withSharedPacket(
  transform: (Map<String, Any?>) -> Map<String, Any?>,
): GoalPlanningPreparationRecord {
  val root = preplanRoot()
  val produced = requireNotNull(JsonCodec.anyToStringAnyMap(root["produced_outputs"]))
  val packet = requireNotNull(JsonCodec.anyToStringAnyMap(produced["_goal_planning_shared_context"]))
  val transformed = transform(packet - "integrity_sha256")
  val packetWithIntegrity =
    transformed + (
      "integrity_sha256" to sha256HexUtf8(JsonCodec.mapToJsonString(transformed))
    )
  return copy(
    preplanPayload =
      JsonCodec.mapToJsonString(
        root + ("produced_outputs" to (produced + ("_goal_planning_shared_context" to packetWithIntegrity))),
      ),
  )
}

private fun GoalPlanningPreparationRecord.withoutSharedPacket(): GoalPlanningPreparationRecord {
  val root = preplanRoot()
  val produced = requireNotNull(JsonCodec.anyToStringAnyMap(root["produced_outputs"]))
  val withoutPacket = root + ("produced_outputs" to (produced - "_goal_planning_shared_context"))
  return copy(preplanPayload = JsonCodec.mapToJsonString(withoutPacket))
}

private fun GoalPlanningPreparationRecord.preplanRoot(): Map<String, Any?> =
  requireNotNull(JsonCodec.parseObjectOrNull(preplanPayload))
    .let(JsonCodec::jsonElementToValue)
    .let { requireNotNull(JsonCodec.anyToStringAnyMap(it)) }

private fun emptyProviderTurnOutcome(): AgentRunLaunchOutcome =
  agentRunLaunchFacts(
    agent = SupportedAgent.CLAUDE,
    stdout = "",
    stderr = "",
    assistantEventCount = 0,
    rawOutputPreview = "{\"type\":\"result\",\"result\":\"\"}",
  )

private fun spawnBlockedOutcome(): AgentRunLaunchOutcome =
  agentRunLaunchFacts(
    agent = SupportedAgent.CLAUDE,
    termination = AgentRunTermination.SpawnFailed,
    stdout = "",
    stderr = "planning agent could not start",
  )

internal fun phasePayload(phaseId: String): String =
  """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"$phaseId",""" +
    """"status":"completed","summary":"s","produced_outputs":""" +
    (PlanningProjectionFixtures.producedOutputsOrNull(phaseId) ?: """{"result":"$phaseId"}""") + "}"

private fun blockedPhasePayload(
  phaseId: String,
  summary: String,
  disposition: String = "needs_user_action",
): String =
  """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"$phaseId",""" +
    """"status":"blocked","failure_disposition":"$disposition","summary":"$summary",""" +
    """"produced_outputs":{"result":"$phaseId"}}"""

private fun preplanProsePayload(
  value: String = "Fixture preplan prose.",
  prompt: String? = null,
): String {
  val promptPart = prompt?.let { ""","prompt":"$it"""" } ?: ""
  val produced = """{"value":"$value"$promptPart}"""
  return """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"preplan",""" +
    """"status":"completed","summary":"s","produced_outputs":$produced}"""
}

private fun fencedPhasePayload(phaseId: String): String =
  "Here is the $phaseId output.\n```json\n" + phasePayload(phaseId) + "\n```\nLet me know if you need more."

private class ThrowingManifestFileStore : DecompositionManifestStore {
  override fun readText(path: Path): String = error("simulated unreadable governed spec at ${path.fileName}")

  override fun readTextWithoutRecovery(path: Path): String = readText(path)

  override fun isRegularFile(path: Path): Boolean = true

  override fun isRegularFileWithoutRecovery(path: Path): Boolean = isRegularFile(path)

  override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> = emptyList()

  override fun findDecompositionManifestFilesWithoutRecovery(repoRoot: Path): List<Path> =
    findDecompositionManifestFiles(repoRoot)

  override fun listDirectChildDirectories(directory: Path): List<Path> = emptyList()

  override fun deleteIfExists(target: Path): Unit =
    error("ThrowingManifestFileStore is read-only in goal planning sweep tests.")

  override fun writeTextAtomically(
    target: Path,
    content: String,
  ): Unit = error("ThrowingManifestFileStore is read-only in goal planning sweep tests.")

  override fun <T> writeBundleAtomically(
    writes: List<Pair<Path, String>>,
    verify: () -> T,
  ): T = error("ThrowingManifestFileStore is read-only in goal planning sweep tests.")

  override fun encodeManifestYaml(wireMap: DecompositionManifestWireMap): String =
    error("ThrowingManifestFileStore is read-only in goal planning sweep tests.")
}

private class FakePhaseOutputValidator : FeatureTaskRuntimePhaseOutputTestValidator() {
  override fun validatePhaseOutputText(
    phaseOutputText: String,
    sourceLabel: String,
  ) {
    val output =
      JsonCodec.parseObjectOrNull(phaseOutputText)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?: throw malformed(sourceLabel, "Phase output root must be a single JSON object.")
    val contractVersion = output["contract_version"]?.toString()
    val phaseId = output["phase_id"]?.toString()
    val status = output["status"]?.toString()
    val produced = output["produced_outputs"]
    if (contractVersion != FEATURE_TASK_RUNTIME_CONTRACT_VERSION) {
      throw malformed(sourceLabel, "contract_version must be '$FEATURE_TASK_RUNTIME_CONTRACT_VERSION'.")
    }
    if (phaseId != sourceLabel) {
      throw malformed(sourceLabel, "phase_id must be '$sourceLabel'.")
    }
    if (status !in setOf("completed", "blocked", "failed")) {
      throw malformed(sourceLabel, "status must be completed, blocked, or failed.")
    }
    if (produced !is Map<*, *> || produced.isEmpty()) {
      throw malformed(sourceLabel, "produced_outputs must be a non-empty object.")
    }
  }

  override fun normalizePhaseOutput(
    phaseOutputText: String,
    sourceLabel: String,
  ): NormalizedFeatureTaskRuntimePhaseOutput = normalizedPlanningOutput(phaseOutputText)

  private fun malformed(
    sourceLabel: String,
    reason: String,
  ): InvalidFeatureTaskRuntimePhaseOutputSchemaError =
    InvalidFeatureTaskRuntimePhaseOutputSchemaError(sourceLabel = sourceLabel, reason = reason)
}

private class FenceAwarePhaseOutputValidator : FeatureTaskRuntimePhaseOutputTestValidator() {
  override fun validatePhaseOutputText(
    phaseOutputText: String,
    sourceLabel: String,
  ) {
    parseAndValidate(phaseOutputText, sourceLabel)
  }

  override fun normalizePhaseOutput(
    phaseOutputText: String,
    sourceLabel: String,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val output = parseAndValidate(phaseOutputText, sourceLabel)
    return NormalizedFeatureTaskRuntimePhaseOutput(
      canonicalJson = JsonCodec.mapToJsonString(output),
      envelope = output,
    )
  }

  private fun parseAndValidate(
    phaseOutputText: String,
    sourceLabel: String,
  ): Map<String, Any?> {
    val candidate =
      firstJsonObject(phaseOutputText)
        ?: throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
          sourceLabel = sourceLabel,
          reason = "Phase output root must contain a single JSON object.",
        )
    val output =
      JsonCodec.parseObjectOrNull(candidate)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?: throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
          sourceLabel = sourceLabel,
          reason = "Phase output root must be a single JSON object.",
        )
    validatePhaseOutputFields(output, sourceLabel)
    return output
  }

  private fun validatePhaseOutputFields(
    output: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val reason =
      when {
        output["contract_version"]?.toString() != FEATURE_TASK_RUNTIME_CONTRACT_VERSION ->
          "contract_version must be '$FEATURE_TASK_RUNTIME_CONTRACT_VERSION'."
        output["phase_id"]?.toString() != sourceLabel -> "phase_id must be '$sourceLabel'."
        output["status"]?.toString() !in setOf("completed", "blocked", "failed") ->
          "status must be completed, blocked, or failed."
        output["produced_outputs"] !is Map<*, *> || (output["produced_outputs"] as Map<*, *>).isEmpty() ->
          "produced_outputs must be a non-empty object."
        else -> null
      }
    if (reason != null) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = reason,
      )
    }
  }

  private fun firstJsonObject(text: String): String? {
    val fenced =
      Regex("```[A-Za-z]*\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
        .find(text)?.groupValues?.get(1)
    val candidate = (fenced ?: text).trim()
    val open = candidate.indexOf('{')
    val close = candidate.lastIndexOf('}')
    return if (open in 0 until close) candidate.substring(open, close + 1) else null
  }
}

private class InMemoryPreparationRepository(
  private val markPreparedThrows: Boolean = false,
  private val planCheckpointThrows: Boolean = false,
) : GoalPlanningPreparationRepository {
  private val records = linkedMapOf<Int, GoalPlanningPreparationRecord>()
  private var sharedPreplan: SharedGoalPreplanCheckpoint? = null
  private val plans = linkedMapOf<Int, GoalSubtaskPlanCheckpoint>()

  override fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) {
    sharedPreplan = checkpoint
    if (markPreparedThrows) {
      sharedPreplan = null
      error("simulated goal planning persistence failure after mutation")
    }
  }

  override fun replaceSharedPreplan(
    checkpoint: SharedGoalPreplanCheckpoint,
    expectedPayloadSha256: String,
    cascadePlanSubtaskIds: List<Int>,
  ) {
    val shared = sharedPreplan
    if (shared == null || shared.payloadSha256 != expectedPayloadSha256) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        checkpoint.identity.parentGoalWorkflowId,
        0,
        "shared preplan changed after it was validated for regeneration",
      )
    }
    sharedPreplan = checkpoint
    cascadeSiblingPlansAfterSharedPreplanRefresh(
      checkpoint.identity.parentGoalWorkflowId,
      cascadePlanSubtaskIds,
    )
    plans.keys.toList().forEach { id ->
      plans[id] = plans.getValue(id).copy(provenance = checkpoint.provenance)
    }
  }

  override fun advanceSharedPreplanProvenance(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
    provenance: GoalPlanningContractProvenance,
  ) {
    if (skipProvenanceAdvance) return
    val shared = sharedPreplan
    if (shared == null ||
      shared.identity.parentGoalWorkflowId != identity.parentGoalWorkflowId ||
      shared.payloadSha256 != expectedPayloadSha256
    ) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        identity.parentGoalWorkflowId,
        0,
        "shared preplan changed after it was validated for provenance advance",
      )
    }
    sharedPreplan = shared.copy(provenance = provenance)
    plans.keys.toList().forEach { id ->
      plans[id] = plans.getValue(id).copy(provenance = provenance)
    }
  }

  var cascadeAfterRefreshCalls: Int = 0
  var lastCascadePlanSubtaskIds: List<Int> = emptyList()
  var skipProvenanceAdvance: Boolean = false

  override fun cascadeSiblingPlansAfterSharedPreplanRefresh(
    parentGoalWorkflowId: String,
    cascadePlanSubtaskIds: List<Int>,
  ): List<Int> {
    cascadeAfterRefreshCalls += 1
    lastCascadePlanSubtaskIds = cascadePlanSubtaskIds
    cascadePlanSubtaskIds.forEach { id ->
      plans.remove(id)
      records.remove(id)
    }
    return cascadePlanSubtaskIds
  }

  override fun listPreparedPlanSubtaskIds(parentGoalWorkflowId: String): List<Int> = plans.keys.toList()

  override fun hasPreparedSharedPreplan(parentGoalWorkflowId: String): Boolean = sharedPreplan != null

  override fun sharedPreplanPayloadSha256(parentGoalWorkflowId: String): String? = sharedPreplan?.payloadSha256

  override fun replaceSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    plans[checkpoint.subtaskId] = checkpoint
  }

  override fun deleteSubtaskPlan(
    parentGoalWorkflowId: String,
    subtaskId: Int,
  ): Int {
    val removed = plans.remove(subtaskId) != null
    records.remove(subtaskId)
    return if (removed) 1 else 0
  }

  override fun deleteSharedPreplan(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
  ): Int {
    val shared = sharedPreplan
    if (shared == null ||
      shared.identity.parentGoalWorkflowId != identity.parentGoalWorkflowId ||
      shared.payloadSha256 != expectedPayloadSha256
    ) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        identity.parentGoalWorkflowId,
        0,
        "shared preplan changed after it was observed for discard",
      )
    }
    sharedPreplan = null
    plans.clear()
    records.clear()
    return 1
  }

  override fun invalidateSharedPreplan(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
  ): Int {
    val shared = sharedPreplan
    if (shared == null ||
      shared.identity.parentGoalWorkflowId != identity.parentGoalWorkflowId ||
      shared.payloadSha256 != expectedPayloadSha256
    ) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        identity.parentGoalWorkflowId,
        0,
        "shared preplan changed after it was observed for discard",
      )
    }
    sharedPreplan =
      shared.copy(
        payloadSha256 = sha256HexUtf8("shared-preplan-discarded"),
        preplanPayload = "shared-preplan-discarded",
      )
    return 1
  }

  fun corruptPlanProvenance(subtaskId: Int) {
    val plan = requireNotNull(plans[subtaskId])
    plans[subtaskId] = plan.copy(provenance = plan.provenance.copy(parentSpecHash = "stale-parent-spec-hash"))
  }

  fun overwriteSharedPreplanPayload(preplanPayload: String) {
    val shared = requireNotNull(sharedPreplan)
    sharedPreplan =
      shared.copy(
        payloadSha256 = sha256HexUtf8(preplanPayload),
        preplanPayload = preplanPayload,
      )
  }

  fun blankSettledPreplanValue(identity: GoalPlanningIdentity) {
    val settled = requireNotNull(findSharedPreplan(identity))
    val root =
      requireNotNull(
        JsonCodec.parseObjectOrNull(settled.preplanPayload)
          ?.let(JsonCodec::jsonElementToValue)
          ?.let(JsonCodec::anyToStringAnyMap),
      )
    val produced = requireNotNull(JsonCodec.anyToStringAnyMap(root["produced_outputs"]))
    overwriteSharedPreplanPayload(
      JsonCodec.mapToJsonString(root + ("produced_outputs" to (produced + ("value" to "   ")))),
    )
  }

  override fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity) =
    sharedPreplan?.takeIf { it.identity == expectedIdentity }

  override fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    plans[checkpoint.subtaskId] = checkpoint
    val shared = requireNotNull(sharedPreplan)
    records[checkpoint.subtaskId] =
      GoalPlanningPreparationRecord(
        parentGoalWorkflowId = checkpoint.identity.parentGoalWorkflowId,
        normalizedIssueKey = checkpoint.identity.normalizedIssueKey,
        repositoryIdentity = checkpoint.identity.repositoryIdentity,
        subtaskId = checkpoint.subtaskId,
        governedSubSpecPath = checkpoint.governedSubSpecPath,
        preparationStatus = checkpoint.preparationStatus,
        provenance =
          GoalPlanningPreparationProvenance(
            parentSpecHash = checkpoint.provenance.parentSpecHash,
            subSpecHash = checkpoint.subSpecHash,
            decompositionManifestHash = checkpoint.provenance.decompositionManifestHash,
            phaseOutputContractId = checkpoint.provenance.phaseOutputContractId,
            phaseOutputContractVersion = checkpoint.provenance.phaseOutputContractVersion,
          ),
        preplanPayload = shared.preplanPayload,
        planPayload = checkpoint.planPayload,
      )
    if (markPreparedThrows) {
      plans.remove(checkpoint.subtaskId)
      records.remove(checkpoint.subtaskId)
      error("simulated goal planning persistence failure after mutation")
    }
    if (planCheckpointThrows) {
      plans.remove(checkpoint.subtaskId)
      records.remove(checkpoint.subtaskId)
      error("simulated plan checkpoint failure after mutation")
    }
  }

  override fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ) = plans[subtaskId]?.takeIf { it.identity == expectedIdentity && it.governedSubSpecPath == governedSubSpecPath }

  override fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ) = plans.values.filter { it.identity == expectedIdentity }.sortedBy { it.manifestOrder }

  override fun markPrepared(record: GoalPlanningPreparationRecord) {
    records[record.subtaskId] = record
    val identity =
      GoalPlanningIdentity(
        record.parentGoalWorkflowId,
        record.normalizedIssueKey,
        record.repositoryIdentity,
      )
    val provenance =
      GoalPlanningContractProvenance(
        record.provenance.parentSpecHash,
        record.provenance.decompositionManifestHash,
        GOAL_PLANNING_PREPARATION_SCHEMA_ID,
      )
    sharedPreplan =
      SharedGoalPreplanCheckpoint(
        identity = identity,
        provenance = provenance,
        payloadSha256 = sha256HexUtf8(record.preplanPayload),
        preplanPayload = record.preplanPayload,
      )
    plans[record.subtaskId] =
      GoalSubtaskPlanCheckpoint(
        identity = identity,
        subtaskId = record.subtaskId,
        manifestOrder = record.subtaskId - 1,
        governedSubSpecPath = record.governedSubSpecPath,
        subSpecHash = record.provenance.subSpecHash,
        provenance = provenance,
        payloadSha256 = sha256HexUtf8(record.planPayload),
        planPayload = record.planPayload,
      )
    if (markPreparedThrows) {
      records.remove(record.subtaskId)
      plans.remove(record.subtaskId)
      sharedPreplan = null
      error("simulated goal planning persistence failure after mutation")
    }
  }

  fun findBySubtask(subtaskId: Int): GoalPlanningPreparationRecord? = records[subtaskId]

  override fun deleteByGoal(parentGoalWorkflowId: String): Int {
    val matchingIds =
      records.values
        .filter { record -> record.parentGoalWorkflowId == parentGoalWorkflowId }
        .map(GoalPlanningPreparationRecord::subtaskId)
    matchingIds.forEach(records::remove)
    return matchingIds.size
  }

  fun count(): Int = records.size
}

private class InMemoryPreparationDatabase(
  markPreparedThrows: Boolean = false,
  planCheckpointThrows: Boolean = false,
) : DatabaseSessionFactory {
  val repository = InMemoryPreparationRepository(markPreparedThrows, planCheckpointThrows)
  private val dbPath = Path.of("/fake/goal-planning-sweep-preparations.db")

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = true

  @Synchronized
  override fun <T> read(block: (UnitOfWork) -> T): T = block(unitOfWork())

  @Synchronized
  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

  @Synchronized
  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = this@InMemoryPreparationDatabase.dbPath
      override val reviews: ReviewRepository get() = error("unused by goal planning sweep tests")
      override val learnings: LearningRepository get() = error("unused by goal planning sweep tests")
      override val lifecycleTelemetry: LifecycleTelemetryRepository get() = error("unused by goal planning sweep tests")
      override val telemetryReconciliation: TelemetryReconciliationRepository
        get() = error("unused by goal planning sweep tests")
      override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused by goal planning sweep tests")
      override val workflowStates: WorkflowStateRepository get() = error("unused by goal planning sweep tests")
      override val workList = EmptyWorkListRepository
      override val goalPlanningPreparations: GoalPlanningPreparationRepository = repository
      override val goalRunnerControls = EmptyGoalRunnerControlRepository
    }
}

private data class SweepFixtures(
  val database: InMemoryPreparationDatabase,
  val checkpoint: GoalPlanningPreparationCheckpoint,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val manifestFileStore: CountingManifestFileStore,
  val invariantsSource: FakeInvariantsSource,
  val repoRoot: Path,
  val databasePath: String,
) {
  fun stateFor(manifest: DecompositionManifest): GoalRunnerManifestState =
    GoalRunnerManifestState(
      parentWorkflowId = "wfl-parent",
      dbPath = databasePath,
      manifest = manifest,
    )

  fun request(outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = repoRoot,
      invokedAgentId = "claude",
      outputSink = outputSink,
    )

  fun preparedCount(): Int = database.repository.count()
}

private fun sharedSweepFixtures(
  markPreparedThrows: Boolean = false,
  planCheckpointThrows: Boolean = false,
  outputValidator: FeatureTaskRuntimePhaseOutputValidator = FakePhaseOutputValidator(),
): SweepFixtures {
  val database =
    InMemoryPreparationDatabase(
      markPreparedThrows = markPreparedThrows,
      planCheckpointThrows = planCheckpointThrows,
    )
  val checkpoint =
    GoalPlanningPreparationCheckpoint(
      database = database,
      envelopeValidator = NoopGoalPlanningPreparationEnvelopeValidator,
      phaseOutputValidator = outputValidator,
      planningProjectionValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
    )
  return SweepFixtures(
    database = database,
    checkpoint = checkpoint,
    outputValidator = outputValidator,
    manifestFileStore = CountingManifestFileStore(),
    invariantsSource = FakeInvariantsSource(),
    repoRoot = Files.createTempDirectory("goal-planning-sweep"),
    databasePath = database.resolveDbPath().toString(),
  )
}

private class SweepHarness(
  val fixtures: SweepFixtures,
  val launcher: SweepPlanningLauncher,
  val sweep: DefaultGoalPlanningSweep,
) {
  fun stateFor(manifest: DecompositionManifest): GoalRunnerManifestState = fixtures.stateFor(manifest)

  fun request(outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE): GoalRunnerRunRequest =
    fixtures.request(outputSink)

  fun preparedCount(): Int = fixtures.preparedCount()

  fun identity(): GoalPlanningIdentity =
    GoalPlanningIdentity(
      "wfl-parent",
      "SKILL-56",
      "repo-root-realpath-v1:${fixtures.repoRoot.toRealPath()}",
    )

  fun recordFor(subtaskId: Int): GoalPlanningPreparationRecord? = fixtures.database.repository.findBySubtask(subtaskId)

  val manifestFileStore: CountingManifestFileStore get() = fixtures.manifestFileStore
}

private data class SweepHarnessConfig(
  val markPreparedThrows: Boolean = false,
  val planCheckpointThrows: Boolean = false,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator = FakePhaseOutputValidator(),
  val contextDiscovery: GoalPlanningContextDiscovery = fakeContextDiscovery,
  val planningProjectionValidator: FeatureTaskRuntimeWireArtifactValidator =
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
  val planningAttemptRecorder: GoalPlanningAttemptRecorder = NO_GOAL_PLANNING_ATTEMPT_RECORDER,
  val manifestStore: GoalRunnerManifestStore = NoopGoalPlanningManifestStore,
  val planningRejectionRecorder: GoalPlanningRejectionRecorder = NO_GOAL_PLANNING_REJECTION_RECORDER,
  val timingPort: RuntimeTimingPort = NoopRuntimeTimingPort,
  val fanOutPort: BoundedWorkFanOutPort = SequentialBoundedWorkFanOutPort,
  val burstSchedule: GoalPlanningBurstSchedule =
    GoalPlanningBurstSchedule(
      planFanOutCap = GoalPlanningBurstSchedule.DEFAULT_PLAN_FAN_OUT_CAP,
      emptyTurnBackoffBase = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_BASE,
      emptyTurnBackoffFactor = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_FACTOR,
      waitSlice = GoalPlanningBurstSchedule.DEFAULT_WAIT_SLICE,
    ),
  val refreshLiveness: GoalPlanningRefreshLiveness = IDLE_GOAL_PLANNING_REFRESH_LIVENESS,
)

private fun sweepFromFixtures(
  fixtures: SweepFixtures,
  launcher: SweepPlanningLauncher,
): DefaultGoalPlanningSweep =
  testGoalPlanningSweepPorts(
    GoalPlanningSweepPortsParams(
      checkpoint = fixtures.checkpoint,
      outputValidator = fixtures.outputValidator,
      subtaskLauncher = launcher,
      invariantsSource = fixtures.invariantsSource,
      manifestFileStore = fixtures.manifestFileStore,
      contextDiscovery = fakeContextDiscovery,
    ),
  )

private fun settleSharedPreplanThenBlankProse(fixtures: SweepFixtures) {
  val settledPreplan =
    sweepFromFixtures(
      fixtures,
      SweepPlanningLauncher { phase, _, _ ->
        if (phase == "plan") {
          launchFacts(stdout = phasePayload(phase).replace("\"completed\"", "\"blocked\""))
        } else {
          validPhaseOutcome(phase)
        }
      },
    )
  assertIs<GoalPlanningSweepOutcome.Stopped>(
    settledPreplan.prepare(fixtures.stateFor(manifest(subtaskCount = 1)), fixtures.request()),
  )
  assertEquals(0, fixtures.preparedCount(), "run one must settle the shared preplan and no plan")
  fixtures.database.repository.blankSettledPreplanValue(
    GoalPlanningIdentity(
      "wfl-parent",
      "SKILL-56",
      "repo-root-realpath-v1:${fixtures.repoRoot.toRealPath()}",
    ),
  )
}

private fun assertBlankProsePlanLaunchStop(
  stopped: GoalPlanningSweepOutcome.Stopped,
  launcher: SweepPlanningLauncher,
) {
  assertEquals("plan", stopped.lastResumableStep)
  assertEquals(1, stopped.currentSubtaskId)
  assertTrue(stopped.blockedReason.contains("rejected a declared bounded projection at the launch seam"))
  assertTrue(
    stopped.blockedReason.contains("Migrate or delete"),
    "the block must name the operator remedy for a non-conforming durable record",
  )
  assertTrue(stopped.blockedReason.contains("produced_outputs.value must contain non-blank prose"))
  assertEquals(
    0,
    launcher.requests.size,
    "the settled preplan is not re-produced and the plan edge rejects before launching",
  )
}

private fun sweepHarness(
  config: SweepHarnessConfig = SweepHarnessConfig(),
  behavior: (phase: String, subtaskId: Int, request: GoalRunnerSubtaskLaunchRequest) -> AgentRunLaunchOutcome,
): SweepHarness {
  val fixtures =
    sharedSweepFixtures(
      markPreparedThrows = config.markPreparedThrows,
      planCheckpointThrows = config.planCheckpointThrows,
      outputValidator = config.outputValidator,
    )
  val launcher = SweepPlanningLauncher(behavior)
  val sweep =
    testGoalPlanningSweepPorts(
      GoalPlanningSweepPortsParams(
        checkpoint = fixtures.checkpoint,
        outputValidator = fixtures.outputValidator,
        subtaskLauncher = launcher,
        invariantsSource = fixtures.invariantsSource,
        manifestFileStore = fixtures.manifestFileStore,
        contextDiscovery = config.contextDiscovery,
        planningProjectionValidator = config.planningProjectionValidator,
        planningAttemptRecorder = config.planningAttemptRecorder,
        manifestStore = config.manifestStore,
        planningRejectionRecorder = config.planningRejectionRecorder,
        timingPort = config.timingPort,
        fanOutPort = config.fanOutPort,
        burstSchedule = config.burstSchedule,
        refreshLiveness = config.refreshLiveness,
      ),
    )
  return SweepHarness(fixtures, launcher, sweep)
}

private class RecordingRuntimeTimingPort(
  private val result: RuntimeWaitResult = RuntimeWaitResult.COMPLETED,
  private val onWait: (() -> Unit)? = null,
) : RuntimeTimingPort {
  val waits = mutableListOf<Duration>()

  override fun wait(duration: Duration): RuntimeWaitResult {
    waits += duration
    onWait?.invoke()
    return result
  }
}

private class MutablePauseGoalPlanningManifestStore : GoalRunnerManifestStoreDefaults() {
  var pauseRequested: Boolean = false

  override fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = null

  override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState = state

  override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
    GoalRunnerControlState(pauseRequested = pauseRequested)

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
}

internal const val FIXTURE_HEADING_ID = "runtime-kotlin/agent/history.md#0-000000000000"
internal const val FIXTURE_HEADING = "## [2026-08-01] fixture-entry"
internal const val FIXTURE_BODY = "distinctive fixture body sentence"

private object NoopGoalPlanningManifestStore : GoalRunnerManifestStoreDefaults() {
  override fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = null

  override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState = state

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
}

private class TrackingPlanningAuthorization : AgentRunSpawnAuthorization {
  var invocations: Int = 0
    private set
  var open: Boolean = false
    private set

  override fun <T> withAuthorization(spawn: () -> T): T {
    invocations += 1
    open = true
    return try {
      spawn()
    } finally {
      open = false
    }
  }
}

private class AuthorizingGoalPlanningManifestStore(
  private val authorization: AgentRunSpawnAuthorization,
) : GoalRunnerManifestStoreDefaults() {
  override fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = null

  override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState = state

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

  override fun authorizePlanningLaunch(parentWorkflowId: String): AgentRunSpawnAuthorization = authorization
}

private class CountingContextDiscovery : GoalPlanningContextDiscovery {
  var calls: Int = 0
    private set

  override fun loadPlanningContext(repoRoot: Path): GoalPlanningContext {
    calls += 1
    return fakeContextDiscovery.loadPlanningContext(repoRoot)
  }

  override fun discoverForFindingPaths(
    repoRoot: Path,
    findingPaths: List<String>,
    loudFailOnCapExceeded: Boolean,
  ) = fakeContextDiscovery.discoverForFindingPaths(repoRoot, findingPaths, loudFailOnCapExceeded)
}

private class MutableContextDiscovery : GoalPlanningContextDiscovery {
  var calls: Int = 0
    private set
  private var catalog: List<GoalPlanningBoundaryHeading> =
    listOf(
      GoalPlanningBoundaryHeading(
        headingId = FIXTURE_HEADING_ID,
        sourcePath = "runtime-kotlin/agent/history.md",
        kind = GoalPlanningContext.KIND_HISTORY,
        heading = FIXTURE_HEADING,
      ),
    )

  fun clearCatalog() {
    catalog = emptyList()
  }

  override fun loadPlanningContext(repoRoot: Path): GoalPlanningContext {
    calls += 1
    return GoalPlanningContext(
      boundaryCatalog = catalog,
      boundaryCatalogTruncated = false,
      validationGuidance = "Run focused Gradle checks.",
    )
  }

  override fun discoverForFindingPaths(
    repoRoot: Path,
    findingPaths: List<String>,
    loudFailOnCapExceeded: Boolean,
  ) = GoalVerificationBoundaryDiscovery(
    boundaryCatalog = catalog,
    boundaryCatalogTruncated = false,
    boundaryContextUnavailable = findingPaths.isEmpty(),
  )
}

private fun recoverabilityCheckpoint(
  parentSpec: String,
  preplanPayload: String = phasePayload("preplan"),
): SharedGoalPreplanCheckpoint {
  val provenance =
    GoalPlanningContractProvenance(
      parentSpecHash = sha256HexUtf8(parentSpec),
      decompositionManifestHash = "manifest-hash",
      planningContractId = GOAL_PLANNING_PREPARATION_SCHEMA_ID,
      phaseOutputContractId = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID,
    )
  return SharedGoalPreplanCheckpoint(
    identity = GoalPlanningIdentity("wfl-parent", "SKILL-56", "repo-root-realpath-v1:/tmp/fixture"),
    provenance = provenance,
    payloadSha256 = sha256HexUtf8(preplanPayload),
    preplanPayload = preplanPayload,
  )
}
