package skillbill.engine.featuretask.runner

import skillbill.agent.model.PhaseOutput
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementCompleteRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProducerOutputRead
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeCommitPushReceipt
import skillbill.engine.featuretask.phase.core.MonitorRepairAccounting
import skillbill.engine.featuretask.phase.core.MonitorRepairProducerEvidence
import skillbill.engine.featuretask.phase.core.MonitorRepairStepRoles
import skillbill.engine.featuretask.phase.core.RepairPublicationAssociation
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.runloop.finalization.FeatureTaskRuntimeRunLoopCommitCycle
import skillbill.engine.featuretask.runloop.finalization.FeatureTaskRuntimeRunLoopMonitorCycle
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.text.sha256HexUtf8
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MonitorRepairResumeAccountingTest {
  private val root = Files.createTempDirectory("monitor-repair-resume")
  private val repo = root.resolve("repo")
  private val origin = root.resolve("origin.git")
  private val gitOperations = GitWorkflowGitOperations()
  private val harness =
    runnerHarness(
      RuntimeHarnessConfig(repoRoot = repo, gitOperationsOverride = gitOperations, seedDurableWorkflow = false),
    )
  private val recorder = harness.recorder
  private val execution =
    ExecutionPlanAdmissionFixture(
      definition = SkeletonDefinition.MONITOR,
      selectedStrategies = harness.strategies,
      specPath = harness.request().runInvariants.specReference,
    )
  private val commits = mutableListOf<String>()
  private val warnings = mutableListOf<String>()
  private val diagnostics =
    object : RuntimeDiagnostics by NoopRuntimeDiagnostics {
      override fun warning(
        message: String,
        error: Throwable?,
      ) {
        warnings += message
      }
    }

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun `restart retains three failed published repairs after their phase records have been replaced`() {
    seedThreeFailures()
    complete("monitor", 5, failedMonitor(commits.last()))
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(WORKFLOW_ID, "monitor_fix", "running", 4, "claude", false),
    )

    repeat(2) {
      val state = restoredState()
      assertEquals(commits.drop(1).toSet(), state.monitorFailedRepairCommitShas, warnings.joinToString("\n"))
      val reason = FeatureTaskRuntimeRunLoopMonitorCycle.monitorCapExhaustionReason("monitor_fix", state)
      commits.drop(1).forEach { assertTrue(it in reason, reason) }
      assertTrue("after 3 CI-confirmed" in reason, reason)
      assertTrue("https://ci.example/failed" in reason, reason)
    }
    assertTrue(harness.launcher.requests.isEmpty())
  }

  @Test
  fun `historical prose naming a different commit cannot alter proven publication accounting`() {
    seedThreeFailures()
    complete("monitor", 5, failedMonitor("a".repeat(40)))

    val state = restoredState()

    assertEquals(commits.drop(1).toSet(), state.monitorFailedRepairCommitShas, warnings.joinToString("\n"))
    val reason = FeatureTaskRuntimeRunLoopMonitorCycle.monitorCapExhaustionReason("monitor_fix", state)
    commits.drop(1).forEach { assertTrue(it in reason, reason) }
  }

  @Test
  fun `live and historical accounting reject a producer from another workflow attempt agent or generation`() {
    seedThreeFailures()
    val ledger = assertNotNull(recorder.loadPhaseLedger(WORKFLOW_ID))
    val records = assertNotNull(recorder.loadPhaseRecords(WORKFLOW_ID))
    val retained = restoredState().outputs()
    val transforms: List<(ProducerOutputEvidence) -> ProducerOutputEvidence> =
      listOf(
        { it.copy(workflowId = "another-workflow") },
        { it.copy(phaseId = "implement") },
        { it.copy(attempt = it.attempt + 1) },
        { it.copy(agentId = "another-agent") },
        { it.copy(generation = 1) },
      )
    for (phase in listOf("monitor_fix", "commit_push")) {
      for (transform in transforms) {
        val accounting =
          MonitorRepairAccounting(
            MonitorRepairStepRoles("monitor", "monitor_fix", "commit_push"),
            gitOperations,
            { query ->
              val read = recorder.producerOutput(query)
              if (query.phaseId == phase && read is FeatureTaskRuntimeProducerOutputRead.Found) {
                FeatureTaskRuntimeProducerOutputRead.Found(transform(read.evidence))
              } else {
                read
              }
            },
            assertNotNull(harness.launcher.settlement)::findEnvelope,
            NoopRuntimeDiagnostics,
          )
        assertIs<RepairPublicationAssociation.Unavailable>(
          accounting.publishedRepairCommitSha(harness.request(), 4, records, ledger, retained),
        )
        val completion =
          ledger.last {
            it.phaseId == phase && it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE
          }
        val evidence =
          MonitorRepairProducerEvidence(
            MonitorRepairStepRoles("monitor", "monitor_fix", "commit_push"),
            { query ->
              val read = recorder.producerOutput(query)
              if (query.phaseId == phase && read is FeatureTaskRuntimeProducerOutputRead.Found) {
                FeatureTaskRuntimeProducerOutputRead.Found(transform(read.evidence))
              } else {
                read
              }
            },
            assertNotNull(harness.launcher.settlement)::findEnvelope,
            NoopRuntimeDiagnostics,
          )
        assertEquals(null, evidence.completedOutput(WORKFLOW_ID, completion, ledger, records[phase]))
      }
    }
  }

  private fun restoredState(): FeatureTaskRuntimeRunState {
    val supported =
      execution.compatibility.requireSupportedExecutionWithRecordedSemantics(
        execution.encoded,
        execution.inputs,
      )
    val request = harness.request().copy(skeletonDefinition = SkeletonDefinition.MONITOR)
    val records = assertNotNull(recorder.loadPhaseRecords(WORKFLOW_ID))
    val ledger = assertNotNull(recorder.loadPhaseLedger(WORKFLOW_ID))
    val history =
      MonitorRepairAccounting(
        MonitorRepairStepRoles("monitor", "monitor_fix", "commit_push"),
        gitOperations,
        recorder::producerOutput,
        assertNotNull(harness.launcher.settlement)::findEnvelope,
        diagnostics,
      ).restore(request, records, ledger, supported.recordedMonitorSemantics, supported.plan)
    return FeatureTaskRuntimeRunState(
      initialRecords = records,
      transitions = supported.plan.traversal,
      durableInitialLedger = ledger,
      initialRetainedOutputs = history.outputs,
      initialMonitorRepairFailures = history.failedRepairs,
      resumeRulesFn = harness.strategies.resumeRules(supported.plan),
    )
  }

  private fun seedThreeFailures() {
    git(root, "init", "--bare", "--initial-branch=main", origin.toString())
    git(root, "init", "--initial-branch=main", repo.toString())
    git(repo, "config", "user.email", "monitor@example.com")
    git(repo, "config", "user.name", "Monitor accounting test")
    git(repo, "config", "commit.gpgsign", "false")
    git(repo, "remote", "add", "origin", origin.toString())
    recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    execution.seed(harness.repository, WORKFLOW_ID, harness.request().issueKey)
    publish(1)
    complete(
      "monitor",
      1,
      NormalizedFeatureTaskRuntimePhaseOutput(
        "monitor",
        "completed",
        "Initial CI failure",
        PhaseOutput(value = "Initial CI failure"),
        verdict = FeatureTaskRuntimeVerdict.PROGRESS.wireValue,
      ),
    )
    for (attempt in 1..3) {
      val settlement =
        assertNotNull(harness.launcher.settlement).complete(
          FeatureTaskPhaseSettlementCompleteRequest(WORKFLOW_ID, "monitor_fix", attempt, "Fixed the failing check."),
        )
      val output =
        NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(
          JsonCodec.mapToJsonString(settlement.envelope),
          "monitor_fix",
        )
      complete("monitor_fix", attempt, output)
      publish(attempt + 1)
      complete("monitor", attempt + 1, failedMonitor(commits.last()))
    }
    assertEquals(WorkflowStepStatus.COMPLETED, recorder.loadPhaseRecords(WORKFLOW_ID)?.get("monitor_fix")?.status)
  }

  private fun publish(attempt: Int) {
    Files.writeString(repo.resolve("source.txt"), "repair $attempt\n")
    git(repo, "add", "source.txt")
    git(repo, "commit", "-m", "Repair $attempt")
    git(repo, "push", "origin", "HEAD:main")
    val sha = git(repo, "rev-parse", "HEAD")
    commits += sha
    val payload =
      FeatureTaskRuntimeRunLoopCommitCycle.runtimeOwnedCommitPushOutput(
        "commit_push",
        FeatureTaskRuntimeCommitPushReceipt(sha, "main", "main", true),
      )
    complete("commit_push", attempt, NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(payload, "commit_push"))
  }

  private fun complete(
    phase: String,
    attempt: Int,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ) {
    assertTrue(
      recorder.appendLedgerEntry(
        FeatureTaskRuntimePhaseLedgerRequest(
          WORKFLOW_ID,
          FeatureTaskRuntimePhaseLedgerAction.START,
          phase,
          attempt,
          "claude",
        ),
      ),
    )
    val payload = output.canonicalJson
    recorder.retainProducerOutput(
      ProducerOutputEvidence(
        WORKFLOW_ID, phase, attempt, "claude", "test-model", Instant.parse("2026-10-16T00:00:00Z"),
        payload.encodeToByteArray().size.toLong(), sha256HexUtf8(payload), payload.encodeToByteArray(),
      ),
    )
    assertTrue(
      recorder.recordCompletedPhase(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = WORKFLOW_ID, phaseId = phase, status = "completed", attemptCount = attempt,
          resolvedAgentId = "claude", finished = true, outputArtifact = payload, normalizedOutput = output,
          fileManifestIntroduced = if (phase == "monitor_fix") listOf("source.txt") else emptyList(),
        ),
      ),
    )
  }

  private fun failedMonitor(sha: String) =
    NormalizedFeatureTaskRuntimePhaseOutput(
      "monitor",
      "completed",
      "Published repair failed CI",
      PhaseOutput(
        value =
          "CI confirmed a failed published repair commit '$sha' on branch 'main'. " +
            "Failing checks: validate (https://ci.example/failed); integration (https://ci.example/integration).",
      ),
      verdict = FeatureTaskRuntimeVerdict.CI_FAILED.wireValue,
    )

  private fun git(
    directory: Path,
    vararg args: String,
  ): String {
    val process =
      ProcessBuilder(listOf("git", "-C", directory.toString()) + args)
        .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    assertEquals(0, process.waitFor(), output)
    return output
  }
}
