package skillbill.engine.featuretask.phaserun

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoop
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindings
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.runner.withRunState
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptRunHost
import skillbill.engine.featuretask.slot.attempt.phaseAttemptLaunchCollaborationScope
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.engine.featuretask.validation.repoLocalConfig
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.scaffold.model.PlatformManifest
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PhaseValidationRunTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-phase-validation-repo")
  private val home: Path = Files.createTempDirectory("skillbill-phase-validation-home")
  private val clock: Clock = Clock.systemUTC()
  private val database = phaseRunDatabase(home, clock)
  private val branchSetup =
    committedRepoBranchSetup().also {
      it.gitOperations.repositoryFingerprintValue = "validation-checkpoint"
      it.gitOperations.trackedPathsValue = listOf("src/Foo.kt")
    }
  private val headBefore = branchSetup.gitOperations.headCommitShaValue

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `standalone validation selects the full branch gate regardless of changed files`() {
    branchSetup.gitOperations.trackedPathsValue = listOf("README.md", "src/Foo.kt")
    val requests = mutableListOf<ValidationGateRunRequest>()
    val launcher = launcher { error("A passing branch gate needs no repair launch") }

    val entry = entry(launcher, requests, emptyList())
    val scopes = listOf(emptyList(), listOf("README.md"), listOf("ios/App.swift"))
    val results =
      scopes.map { changed ->
        branchSetup.gitOperations.ownedPathsResult = WorkflowGitNameListResult.Listed(changed)
        assertIs<PhaseRunResult.Completed>(entry.run(validationRequest()))
      }

    results.forEach { assertEquals(listOf(PHASE_VALIDATE), it.completedStepIds) }
    assertEquals(
      List(scopes.size) { listOf("./tools/gradlew", "-p", "./tools", "validation-discovery") },
      requests.map { it.argv },
    )
    val envelope =
      requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(requireNotNull(results.last().value))))
    val produced = assertIs<Map<*, *>>(envelope["produced_outputs"])
    val evidence = assertIs<Map<*, *>>(produced["validation_result"])
    val runs = assertIs<List<*>>(evidence["gate_runs"])
    assertEquals(0L, (assertIs<Map<*, *>>(runs.single())["exit_code"] as Number).toLong())
    database.assertNoDurableWorkflowState()
    branchSetup.gitOperations.assertNoCommitOrCheckpointRef(headBefore)
  }

  @Test
  fun `repeated standalone validation starts fresh without a workflow row or durable identity`() {
    val gateRequests = mutableListOf<ValidationGateRunRequest>()
    val launcher = launcher { validJsonOutput(PHASE_VALIDATE) }

    val entry = entry(launcher, gateRequests, emptyList())
    val first = assertIs<PhaseRunResult.Completed>(entry.run(validationRequest()))
    database.assertNoDurableWorkflowState()
    val second = assertIs<PhaseRunResult.Completed>(entry.run(validationRequest()))

    assertNotEquals(first.invocationId, second.invocationId)
    assertEquals(listOf(PHASE_VALIDATE), first.completedStepIds)
    assertEquals(listOf(PHASE_VALIDATE), second.completedStepIds)
    assertEquals(emptyList(), launcher.requests)
    assertEquals(
      List(2) {
        listOf("./tools/gradlew", "-p", "./tools", "validation-discovery")
      },
      gateRequests.map { it.argv },
    )
    database.assertNoDurableWorkflowState()
    branchSetup.gitOperations.assertNoCommitOrCheckpointRef(headBefore)
  }

  @Test
  fun `failed discovery is retained and verification uses the cache bypass command`() {
    val gateRequests = mutableListOf<ValidationGateRunRequest>()
    val launcher = launcher { validJsonOutput(PHASE_VALIDATE) }
    val failure =
      ValidationGateRunResult(
        exitCode = 1,
        durationMs = 1,
        outcome = ValidationGateRunOutcome.FAILED,
        cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
        executedWorkUnits = 1,
        executedCheckIdentities = emptyList(),
        findings = listOf(ValidationGateFinding("engine", "compile", "broken", "src/Foo.kt")),
        command = "echo collect-all",
      )
    val verified =
      ValidationGateRunResult(
        exitCode = 0,
        durationMs = 1,
        outcome = ValidationGateRunOutcome.PASSED,
        cacheMode = ValidationGateCacheMode.FORCED_FULL,
        executedWorkUnits = 0,
        executedCheckIdentities = emptyList(),
        findings = emptyList(),
        command = "echo collect-all-full",
      )

    val result = entry(launcher, gateRequests, listOf(failure, verified)).run(validationRequest())

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(
      listOf(
        "./tools/gradlew -p ./tools validation-discovery",
        "./tools/gradlew -p ./tools validation-verification",
      ),
      gateRequests.map {
        it.argv.joinToString(" ")
      },
    )
    assertEquals(
      listOf(ValidationGateCacheMode.CACHE_ELIGIBLE, ValidationGateCacheMode.FORCED_FULL),
      gateRequests.map { it.cacheMode },
    )
    val envelope = requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(requireNotNull(result.value))))
    val produced = assertIs<Map<*, *>>(envelope["produced_outputs"])
    val evidence = assertIs<Map<*, *>>(produced["validation_result"])
    val runs = assertIs<List<*>>(evidence["gate_runs"])
    assertEquals(listOf("failed", "passed"), runs.map { assertIs<Map<*, *>>(it)["outcome"] })
    assertEquals(listOf(1L, 0L), runs.map { (assertIs<Map<*, *>>(it)["exit_code"] as Number).toLong() })
    assertEquals(
      listOf("./tools/gradlew -p ./tools validation-discovery", "./tools/gradlew -p ./tools validation-verification"),
      runs.map { assertIs<Map<*, *>>(it)["command"] },
    )
    val started = database.outboxPayloads("skillbill_quality_check_started").single()
    val finished = database.outboxPayloads("skillbill_quality_check_finished").single()
    assertEquals("bill-code-check", started["routed_skill"])
    assertEquals("pass", finished["result"])
    assertEquals(2L, (finished["iterations"] as Number).toLong())
    assertEquals(
      listOf(PHASE_VALIDATE),
      launcher.requests.mapNotNull {
        it.skillRunRequest.promptOverride?.let(::phaseIdFromPrompt)
      },
    )
    database.assertNoDurableWorkflowState()
    branchSetup.gitOperations.assertNoCommitOrCheckpointRef(headBefore)
  }

  @Test
  fun `a formatting failure is repaired by one launch and validation completes without a second launch`() {
    val gateRequests = mutableListOf<ValidationGateRunRequest>()
    val launcher = launcher { validJsonOutput(PHASE_VALIDATE) }
    val formatting =
      ValidationGateRunResult(
        exitCode = 1,
        durationMs = 1,
        outcome = ValidationGateRunOutcome.FAILED,
        cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
        executedWorkUnits = 1,
        executedCheckIdentities = emptyList(),
        findings = listOf(ValidationGateFinding("engine", "spotlessKotlinCheck", "needs formatting", "src/Foo.kt")),
      )

    val result = entry(launcher, gateRequests, listOf(formatting)).run(validationRequest())

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(1, launcher.requests.size)
    assertEquals(
      listOf(ValidationGateCacheMode.CACHE_ELIGIBLE, ValidationGateCacheMode.FORCED_FULL),
      gateRequests.map { it.cacheMode },
    )
    database.assertNoDurableWorkflowState()
    branchSetup.gitOperations.assertNoCommitOrCheckpointRef(headBefore)
  }

  @Test
  fun `standalone validation blocks when the dominant pack has no required gate`() {
    val gateRequests = mutableListOf<ValidationGateRunRequest>()
    val launcher = launcher { validJsonOutput(PHASE_VALIDATE) }

    val result =
      assertIs<PhaseRunResult.Blocked>(
        entry(launcher, gateRequests, emptyList(), manifests = emptyList())
          .run(validationRequest()),
      )

    assertEquals(PHASE_VALIDATE, result.stepId)
    assertContains(result.reason, "declaration is absent")
    assertEquals(emptyList(), gateRequests)
    assertEquals(emptyList(), launcher.requests)
    database.assertNoDurableWorkflowState()
    branchSetup.gitOperations.assertNoCommitOrCheckpointRef(headBefore)
  }

  @Test
  fun missingRequiredValidationDeclarationBlocksBeforeDiscoveryOrRepair() {
    val pack = validationPack()
    listOf(
      pack.copy(validationGate = null),
    ).forEach { manifest ->
      val requests = mutableListOf<ValidationGateRunRequest>()
      val launcher = launcher { validJsonOutput(PHASE_VALIDATE) }
      val result =
        assertIs<PhaseRunResult.Blocked>(
          entry(launcher, requests, emptyList(), listOf(manifest)).run(validationRequest()),
        )
      assertEquals(PHASE_VALIDATE, result.stepId)
      assertTrue(result.reason.length < 512)
      assertEquals(emptyList(), requests)
      assertEquals(emptyList(), launcher.requests)
      database.assertNoDurableWorkflowState()
      branchSetup.gitOperations.assertNoCommitOrCheckpointRef(headBefore)
    }
  }

  @Test
  fun unreadableCheckpointAfterRepairCannotReuseTheDiscoveryCheckpoint() {
    val requests = mutableListOf<ValidationGateRunRequest>()
    val launcher =
      launcher {
        branchSetup.gitOperations.repositoryFingerprintValue = ""
        validJsonOutput(PHASE_VALIDATE)
      }
    val failure =
      ValidationGateRunResult(
        exitCode = 1,
        durationMs = 1,
        outcome = ValidationGateRunOutcome.FAILED,
        cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
        executedWorkUnits = 0,
        executedCheckIdentities = emptyList(),
        findings = listOf(ValidationGateFinding("engine", "compile", "broken", "Foo.kt")),
      )

    val result =
      assertIs<PhaseRunResult.Blocked>(
        entry(launcher, requests, listOf(failure)).run(validationRequest()),
      )

    assertContains(result.reason, "could not resolve the repository checkpoint before verification")
    assertEquals(1, requests.size)
    assertEquals(1, launcher.requests.size)
    database.assertNoDurableWorkflowState()
    branchSetup.gitOperations.assertNoCommitOrCheckpointRef(headBefore)
  }

  @Test
  fun rejectedRepairBriefingWithUnavailableTerminalStorageStopsBeforeChildAndVerification() {
    val requests = mutableListOf<ValidationGateRunRequest>()
    val launcher = launcher { error("Rejected briefing must prevent repair launch") }
    val failure =
      ValidationGateRunResult(
        exitCode = 1,
        durationMs = 1,
        outcome = ValidationGateRunOutcome.FAILED,
        cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
        executedWorkUnits = 0,
        executedCheckIdentities = emptyList(),
        findings = listOf(ValidationGateFinding("engine", "compile", "broken", "Foo.kt")),
      )
    var original: RequiredPhaseWrite.Rejected? = null
    var terminalWrites = 0
    val loop =
      object : TestFeatureTaskRuntimeRunLoopEntry() {
        override fun run(
          context: FeatureTaskRuntimeRunLoopContext,
          beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit,
        ): FeatureTaskRuntimeRunReport {
          val delegate = context.runState
          val interceptedRecords =
            object : PhaseRunRecords by delegate.records {
              override fun recordPhaseBriefing(
                workflowId: String,
                briefing: FeatureTaskRuntimePhaseLaunchBriefing,
                sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
                attempt: Int,
              ): RequiredPhaseWrite {
                val rejection =
                  RequiredPhaseWrite.Rejected(RequiredPhaseWriteKind.BRIEFING, workflowId, briefing.phaseId, attempt)
                original = rejection
                return rejection
              }

              override fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean {
                terminalWrites++
                error("terminal storage unavailable")
              }
            }
          val state =
            object : PhaseRunState by delegate {
              override val records = interceptedRecords

              override fun step(run: PhaseRun): PhaseAcceptedStepExecution {
                stepBinding.beginStepBinding(run)
                return FeatureTaskRuntimeRunLoopStepBindings.create(
                  phaseAttemptLaunchCollaborationScope(
                    PhaseAttemptRunHost(run, this),
                  ),
                  run,
                )
              }
            }
          return super.run(context.withRunState(state), beforeDrive)
        }
      }

    val result =
      assertIs<PhaseRunResult.Blocked>(
        entry(launcher, requests, listOf(failure), runLoopEntry = loop).run(validationRequest()),
      )

    assertEquals(requireNotNull(original).message, result.reason)
    assertEquals(1, terminalWrites)
    assertEquals(1, requests.size)
    assertEquals(emptyList(), launcher.requests)
    database.assertNoDurableWorkflowState()
    branchSetup.gitOperations.assertNoCommitOrCheckpointRef(headBefore)
  }

  private fun validationPack(): PlatformManifest =
    kotlinPackWithValidationGate().let { pack ->
      pack.copy(
        validationGate =
          requireNotNull(pack.validationGate).copy(
            buildCommand = listOf("./gradlew", "build-discovery"),
            cacheBypassingBuildCommand = listOf("./gradlew", "build-verification"),
            collectAllFullGateCommand = listOf("./gradlew", "validation-discovery"),
            cacheBypassingCollectAllFullGateCommand = listOf("./gradlew", "validation-verification"),
          ),
      )
    }

  private fun validationRequest(): PhaseRunRequest =
    PhaseRunRequest(
      definitionId = SkeletonDefinition.VALIDATION.id,
      repoRoot = repoRoot,
      invokedAgentId = "claude",
    )

  private fun launcher(output: (Int) -> String): RuntimeRecordingLauncher {
    var attempt = 0
    return RuntimeRecordingLauncher { facts(output(++attempt)) }
  }

  private fun entry(
    launcher: RuntimeRecordingLauncher,
    gateRequests: MutableList<ValidationGateRunRequest>,
    results: List<ValidationGateRunResult>,
    manifests: List<PlatformManifest> = listOf(validationPack()),
    runLoopEntry: FeatureTaskRuntimeRunLoopEntry = TestFeatureTaskRuntimeRunLoopEntry(),
  ): PhaseRunEntry {
    var resultIndex = 0
    val config =
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        branchSetup =
          branchSetup.also {
            it.gitOperations.ownedPathsResult =
              WorkflowGitNameListResult.Listed(listOf("src/Foo.kt"))
          },
        repoRoot = repoRoot,
        launcher = launcher,
        validationGatePlatformManifests = manifests,
        gateRepoLocalConfig = repoLocalConfig("./tools/gradlew"),
        validationGateRunner =
          object : ValidationGateRunner {
            override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
              gateRequests += request
              return results.getOrNull(resultIndex++)?.copy(command = request.argv.joinToString(" "))
                ?: ValidationGateRunResult(
                  exitCode = 0,
                  durationMs = 1,
                  outcome = ValidationGateRunOutcome.PASSED,
                  cacheMode = request.cacheMode,
                  executedWorkUnits = 1,
                  executedCheckIdentities = emptyList(),
                  findings = emptyList(),
                  command = request.argv.joinToString(" "),
                )
            }
          },
      )
    val harness =
      telemetryRunnerHarness(
        runtimeConfig = config,
        databaseFactory = { database },
      )
    return phaseRunEntry(
      harness.strategies,
      config.harnessGitOperations,
      database,
      clock,
      harness.runLoopEntry.delegateTo(runLoopEntry),
    )
  }
}
