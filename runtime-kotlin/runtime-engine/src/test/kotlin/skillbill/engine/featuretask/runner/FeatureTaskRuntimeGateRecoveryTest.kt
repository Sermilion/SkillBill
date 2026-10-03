package skillbill.engine.featuretask.runner

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeValidationGateCoordinator
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeGateRecoveryTest {
  @Test
  fun rejectedGateEvidenceNeverRegeneratesOrReplaysIncompleteCompletedOrUncertainFinalization() {
    listOf("build", "validate").forEach { phase ->
      listOf("empty", "unsupported").forEach { corruption ->
        listOf("pending", "completed", "running").forEach { finalizationStatus ->
          val repo = Files.createTempDirectory("gate-recovery")
          try {
            val branch = committedRepoBranchSetup()
            val launcher = satisfiedAuditLauncher()
            val harness =
              telemetryRunnerHarness(
                RuntimeHarnessConfig(
                  repoRoot = repo,
                  branchSetup = branch,
                  launcher = launcher,
                ),
              )
            val payload = rejectedGate(phase, corruption)
            harness.seedPhase(phase, "completed", 3, "claude", payload)
            harness.seedPhase("commit_push", finalizationStatus, 2, "runtime", validJsonOutput("commit_push"))
            if (finalizationStatus == "completed") {
              harness.seedPhase("pr", "completed", 1, "runtime", validJsonOutput("pr"))
            }
            seedGateCheckpointAndLedger(harness.recorder, phase)
            val beforeWorkflow =
              harness.database.read {
                it.workflowStates.get(
                  WorkflowFamily.TASK_RUNTIME,
                  WORKFLOW_ID,
                )
              }
            val beforeRecords = harness.recorder.loadPhaseRecords(WORKFLOW_ID)
            val beforeLedger = harness.recorder.loadPhaseLedger(WORKFLOW_ID)
            val beforeCheckpoints = harness.recorder.loadCheckpointIdentities(WORKFLOW_ID)
            val head = branch.gitOperations.headCommitShaValue

            assertRecoveryRefused(harness, phase, finalizationStatus)
            val afterWorkflow =
              harness.database.read {
                it.workflowStates.get(
                  WorkflowFamily.TASK_RUNTIME,
                  WORKFLOW_ID,
                )
              }
            assertEquals(beforeWorkflow?.workflowStatus, afterWorkflow?.workflowStatus)
            assertEquals(beforeWorkflow?.finishedAt, afterWorkflow?.finishedAt)
            assertEquals(beforeRecords, harness.recorder.loadPhaseRecords(WORKFLOW_ID))
            assertEquals(beforeLedger, harness.recorder.loadPhaseLedger(WORKFLOW_ID))
            assertEquals(beforeCheckpoints, harness.recorder.loadCheckpointIdentities(WORKFLOW_ID))
            assertEquals(payload, harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get(phase)?.outputArtifact)
            assertEquals(emptyList(), launcher.requests)
            assertEquals(head, branch.gitOperations.headCommitShaValue)
            assertTrue(branch.gitOperations.createCommitMessages.isEmpty())
            assertTrue(branch.gitOperations.amendCommitMessages.isEmpty())
            assertTrue(branch.gitOperations.pushedBranches.isEmpty())
          } finally {
            repo.toFile().deleteRecursively()
          }
        }
      }
    }
  }

  private fun assertRecoveryRefused(
    harness: TelemetryRunnerHarness,
    phase: String,
    finalizationStatus: String,
  ) {
    if (finalizationStatus == "completed") {
      val result = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request))
      assertTrue(result.blockedReason.contains("Terminal workflows"))
    } else {
      val error =
        assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
          harness.runner.run(harness.request)
        }
      assertEquals(phase, error.sourceLabel)
    }
  }

  private fun rejectedGate(
    phase: String,
    corruption: String,
  ): String {
    val measurements =
      listOf(
        FeatureTaskRuntimeValidationGateRunRecord(
          durationMs = 1,
          outcome = ValidationGateRunOutcome.PASSED,
          cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
          executedWorkUnits = 0,
          executedChecks = emptyList(),
          command = "echo build",
          exitCode = 0,
          repositoryCheckpoint = "checkpoint",
        ),
      )
    val output =
      if (phase == "build") {
        FeatureTaskRuntimeBuildGateCoordinator.runtimeOwnedBuildOutput(phase, "checkpoint", measurements)
      } else {
        FeatureTaskRuntimeValidationGateCoordinator.runtimeOwnedValidationOutput(
          phase,
          "checkpoint",
          measurements,
          "echo build",
        )
      }
    val envelope = assertNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(output.payload)))
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(envelope["produced_outputs"]))
    val key = if (phase == "build") "build_receipt" else "validation_result"
    val receipt = assertNotNull(JsonCodec.anyToStringAnyMap(produced[key]))
    val invalid =
      if (corruption == "empty") {
        receipt + mapOf("gate_run_count" to 0, "gate_runs" to emptyList<Any>())
      } else if (phase == "build") {
        receipt + ("contract_version" to "unsupported")
      } else {
        val evidence = assertNotNull(JsonCodec.anyToStringAnyMap(receipt["validation_evidence"]))
        receipt + ("validation_evidence" to (evidence + ("contract_version" to "unsupported")))
      }
    return JsonCodec.mapToJsonString(envelope + ("produced_outputs" to (produced + (key to invalid))))
  }

  private fun seedGateCheckpointAndLedger(
    recorder: FeatureTaskRuntimePhaseRecorder,
    phase: String,
  ) {
    assertTrue(
      recorder.appendLedgerEntry(
        FeatureTaskRuntimePhaseLedgerRequest(
          WORKFLOW_ID,
          FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
          phase,
          3,
          "claude",
        ),
      ),
    )
    assertTrue(
      recorder.appendCheckpointIdentity(
        AppendCheckpointIdentityArgs(
          workflowId = WORKFLOW_ID, issueKey = RUNNER_TEST_ISSUE_KEY, subtaskId = "1",
          branch = "feat/SKILL-384-gates", phaseId = phase, loopId = null, generation = 0,
          parentSha = "b".repeat(40), ownedPaths = listOf("src/Foo.kt"), commitSha = "a".repeat(40),
        ),
      ),
    )
  }
}
