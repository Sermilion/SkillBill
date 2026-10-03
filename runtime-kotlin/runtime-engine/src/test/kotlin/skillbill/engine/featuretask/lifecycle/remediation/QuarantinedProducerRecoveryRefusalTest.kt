package skillbill.engine.featuretask.lifecycle.remediation

import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.runner.NoopWorkflowSnapshotValidator
import skillbill.engine.featuretask.runner.SlotBaselineSqlite
import skillbill.error.featuretask.FeatureTaskRuntimeRegenerationRefusal
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.UnsafeFeatureTaskRuntimeRegenerationError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.model.toSnapshot
import skillbill.text.sha256HexUtf8
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as PlanKeys

class QuarantinedProducerRecoveryRefusalTest {
  @Test
  fun `admitted gate regeneration retains the completed output attempts attribution and checkpoint evidence`() {
    val root = Files.createTempDirectory("admitted-gate-regeneration")
    try {
      val database = phaseRunDatabase(root, testHarnessClock)
      val execution = ExecutionPlanAdmissionFixture()
      val workflowId = "wftr-admitted-gate"
      database.transaction { execution.seed(it.workflowStates, workflowId) }
      val recorder = recoveryRecorder(database)
      seedCompletedGateEvidence(recorder, workflowId)
      assertTrue(
        recorder.recordPhaseState(
          FeatureTaskRuntimePhaseStateRequest(
            workflowId = workflowId,
            phaseId = "write_history",
            status = "running",
            attemptCount = 1,
            resolvedAgentId = "history-agent",
            finished = false,
          ),
        ),
      )
      val outputBytes = "retained-gate-output".toByteArray()
      recorder.retainProducerOutput(
        ProducerOutputEvidence(
          workflowId, "validate", 4, "original-validator", "runtime", testHarnessClock.instant(),
          outputBytes.size.toLong(), sha256HexUtf8("retained-gate-output"), outputBytes,
        ),
      )
      val retainedEvidence =
        database.read {
          it.rejectedOutputDiagnostics.readProducerOutput(workflowId, "validate", 4, "original-validator")
        }
      val before = assertNotNull(recorder.loadPhaseRecords(workflowId)?.get("validate"))
      val ledger = recorder.loadPhaseLedger(workflowId)
      val checkpoints = recorder.loadCheckpointIdentities(workflowId)
      val admitted = database.transaction { execution.admission.admit(it.workflowStates, workflowId, execution.inputs) }

      assertTrue(
        recorder.invalidateQuarantinedProducerRecord(workflowId, "validate", "regenerate_validate", 1, admitted),
      )

      val after = assertNotNull(recorder.loadPhaseRecords(workflowId)?.get("validate"))
      assertEquals(
        before.copy(
          status = WorkflowStepStatus.RUNNING,
          finishedAt = null,
          outputArtifact = null,
          loopId = "regenerate_validate",
          edgeIteration = 1,
        ),
        after,
      )
      val afterEvidence =
        database.read {
          it.rejectedOutputDiagnostics.readProducerOutput(workflowId, "validate", 4, "original-validator")
        }
      assertEquals(retainedEvidence?.sha256, afterEvidence?.sha256)
      assertContentEquals(outputBytes, afterEvidence?.payload)
      assertEquals(ledger, recorder.loadPhaseLedger(workflowId))
      assertEquals(checkpoints, recorder.loadCheckpointIdentities(workflowId))
      assertEquals(0, execution.launches)
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `durable producer invalidation preserves attempts outputs and checkpoints when recovery is unsafe`() {
    val cases =
      buildList {
        WorkflowStatus.terminalStatuses.forEach { status ->
          add(RecoveryCase(FeatureTaskRuntimeRegenerationRefusal.TERMINAL_WORKFLOW, status = status))
        }
        listOf("build", "validate").forEach { producer ->
          add(RecoveryCase(FeatureTaskRuntimeRegenerationRefusal.UNPROVEN_GATE_SEMANTICS, producer = producer))
        }
        listOf("commit_push", "pr").forEach { finalizationStep ->
          listOf("pending", "running", "completed").forEach { finalizationStatus ->
            add(
              RecoveryCase(
                FeatureTaskRuntimeRegenerationRefusal.IRREVERSIBLE_WORK_RECORDED,
                finalizationStep = finalizationStep,
                finalizationStatus = finalizationStatus,
              ),
            )
          }
          add(
            RecoveryCase(
              FeatureTaskRuntimeRegenerationRefusal.IRREVERSIBLE_WORK_RECORDED,
              finalizationStep = finalizationStep,
              ledgerOnly = true,
            ),
          )
        }
        add(RecoveryCase(FeatureTaskRuntimeRegenerationRefusal.MISSING_PRODUCER_EVIDENCE, seedProducer = false))
      }
    cases.forEach(::assertRecoveryRefused)
  }

  @Test
  fun `admitted build and validate recovery refuses incomplete evidence and semantic changes without mutation`() {
    val cases =
      listOf(
        GateRecoveryCase("build", SkeletonDefinition.GOAL_CHILD, FeatureTaskRuntimeQualityGateSelection.BUILD),
        GateRecoveryCase("validate", SkeletonDefinition.STANDALONE, FeatureTaskRuntimeQualityGateSelection.VALIDATE),
      ).flatMap { gate ->
        listOf(
          gate.copy(allow = true),
          gate.copy(missingPayload = true),
          gate.copy(missingLedger = true),
          gate.copy(mismatchedLedgerAttempt = true),
          gate.copy(missingPriorCheckpoint = true),
          gate.copy(downstreamRecord = true),
          gate.copy(downstreamLedger = true),
          gate.copy(downstreamCheckpoint = true),
          gate.copy(changedDescriptorAfterAdmission = true),
          gate.copy(irreversibleEvidence = true),
        )
      }
    cases.forEach(::assertAdmittedGateRecoveryRefused)
  }

  private fun assertAdmittedGateRecoveryRefused(case: GateRecoveryCase) {
    val root = Files.createTempDirectory("admitted-gate-recovery-refusal")
    try {
      val database = phaseRunDatabase(root, testHarnessClock)
      val execution = ExecutionPlanAdmissionFixture(case.definition, qualityGate = case.qualityGate)
      val recorder = recoveryRecorder(database)
      val workflowId = "wftr-admitted-gate-refusal"
      database.transaction { execution.seed(it.workflowStates, workflowId) }
      assertTrue(recorder.openTestWorkflow(workflowId, "recovery-session", "SKILL-384"))
      val producerAttempt = 4
      seedGateAndHistory(recorder, workflowId, case)
      seedGateLedgerAndCheckpoint(recorder, workflowId, case)
      seedDownstreamEvidence(recorder, workflowId, case)
      val admitted = database.transaction { execution.admission.admit(it.workflowStates, workflowId, execution.inputs) }
      changeDescriptorAfterAdmission(database, root, workflowId, case)
      val before = database.read { requireNotNull(it.workflowStates.getFeatureTaskWorkflow(workflowId)) }
      val records = recorder.loadPhaseRecords(workflowId)
      val ledger = recorder.loadPhaseLedger(workflowId)
      val checkpoints = recorder.loadCheckpointIdentities(workflowId)

      if (case.allow) {
        assertTrue(recorder.invalidateQuarantinedProducerRecord(workflowId, case.producer, "regen_gate", 1, admitted))
        val retained = requireNotNull(recorder.loadPhaseRecords(workflowId)?.get(case.producer))
        assertEquals(producerAttempt, retained.attemptCount)
        assertEquals("original-gate", retained.resolvedAgentId)
        assertEquals(ledger, recorder.loadPhaseLedger(workflowId))
        assertEquals(checkpoints, recorder.loadCheckpointIdentities(workflowId))
        return
      }
      if (case.changedDescriptorAfterAdmission) {
        assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError>(case.toString()) {
          recorder.invalidateQuarantinedProducerRecord(workflowId, case.producer, "regen_gate", 1, admitted)
        }
      } else {
        val error =
          assertFailsWith<UnsafeFeatureTaskRuntimeRegenerationError>(case.toString()) {
            recorder.invalidateQuarantinedProducerRecord(workflowId, case.producer, "regen_gate", 1, admitted)
          }
        val expected =
          when {
            case.irreversibleEvidence -> FeatureTaskRuntimeRegenerationRefusal.IRREVERSIBLE_WORK_RECORDED
            case.missingPayload -> FeatureTaskRuntimeRegenerationRefusal.MISSING_PRODUCER_EVIDENCE
            else -> FeatureTaskRuntimeRegenerationRefusal.UNPROVEN_GATE_SEMANTICS
          }
        assertEquals(expected, error.refusal, case.toString())
      }

      database.read { assertEquals(before, it.workflowStates.getFeatureTaskWorkflow(workflowId), case.toString()) }
      assertEquals(records, recorder.loadPhaseRecords(workflowId), case.toString())
      assertEquals(ledger, recorder.loadPhaseLedger(workflowId), case.toString())
      assertEquals(checkpoints, recorder.loadCheckpointIdentities(workflowId), case.toString())
      assertEquals(0, execution.launches)
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun assertRecoveryRefused(case: RecoveryCase) {
    val root = Files.createTempDirectory("quarantined-producer-refusal")
    try {
      val database = phaseRunDatabase(root, testHarnessClock)
      val recorder = recoveryRecorder(database)
      val workflowId = "wftr-quarantined-producer"
      val execution = ExecutionPlanAdmissionFixture()
      database.transaction { execution.seed(it.workflowStates, workflowId) }
      assertTrue(recorder.openTestWorkflow(workflowId, "recovery-session", "SKILL-384"))
      if (case.seedProducer) {
        assertTrue(
          recorder.recordPhaseState(
            FeatureTaskRuntimePhaseStateRequest(
              workflowId = workflowId,
              phaseId = case.producer,
              status = "completed",
              attemptCount = 4,
              resolvedAgentId = "original-producer",
              finished = true,
              outputArtifact = "retained-producer-output",
            ),
          ),
        )
      }
      seedFinalizationEvidence(recorder, workflowId, case)
      assertTrue(
        recorder.appendCheckpointIdentity(
          AppendCheckpointIdentityArgs(
            workflowId = workflowId,
            issueKey = "SKILL-384",
            subtaskId = "2",
            branch = "feat/SKILL-384",
            phaseId = "review",
            loopId = null,
            generation = 0,
            parentSha = "a".repeat(40),
            ownedPaths = listOf("src/Changed.kt"),
            commitSha = "b".repeat(40),
          ),
        ),
      )
      database.transaction { unit ->
        val row = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(workflowId))
        unit.workflowStates.saveFeatureTaskWorkflow(
          row.copy(workflowStatus = case.status.wireValue),
          FeatureTaskWorkflowMode.RUNTIME,
        )
      }
      val before = database.read { assertNotNull(it.workflowStates.getFeatureTaskWorkflow(workflowId)) }
      val records = recorder.loadPhaseRecords(workflowId)
      val ledger = recorder.loadPhaseLedger(workflowId)
      val checkpoints = recorder.loadCheckpointIdentities(workflowId)

      val error =
        assertFailsWith<UnsafeFeatureTaskRuntimeRegenerationError>(case.toString()) {
          recorder.invalidateQuarantinedProducerRecord(workflowId, case.producer, "regen_implement", 1)
        }

      assertEquals(case.refusal, error.refusal)
      database.read { assertEquals(before, it.workflowStates.getFeatureTaskWorkflow(workflowId)) }
      assertEquals(records, recorder.loadPhaseRecords(workflowId))
      assertEquals(ledger, recorder.loadPhaseLedger(workflowId))
      assertEquals(checkpoints, recorder.loadCheckpointIdentities(workflowId))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private data class RecoveryCase(
    val refusal: FeatureTaskRuntimeRegenerationRefusal,
    val status: WorkflowStatus = WorkflowStatus.RUNNING,
    val producer: String = "implement",
    val finalizationStep: String? = null,
    val finalizationStatus: String = "pending",
    val ledgerOnly: Boolean = false,
    val seedProducer: Boolean = true,
  )

  private data class GateRecoveryCase(
    val producer: String,
    val definition: SkeletonDefinition,
    val qualityGate: FeatureTaskRuntimeQualityGateSelection?,
    val allow: Boolean = false,
    val missingPayload: Boolean = false,
    val missingLedger: Boolean = false,
    val mismatchedLedgerAttempt: Boolean = false,
    val missingPriorCheckpoint: Boolean = false,
    val downstreamRecord: Boolean = false,
    val downstreamLedger: Boolean = false,
    val downstreamCheckpoint: Boolean = false,
    val changedDescriptorAfterAdmission: Boolean = false,
    val irreversibleEvidence: Boolean = false,
  )

  private fun seedCompletedGateEvidence(
    recorder: FeatureTaskRuntimePhaseRecorder,
    workflowId: String,
  ) {
    assertTrue(
      recorder.recordPhaseState(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = workflowId,
          phaseId = "validate",
          status = "completed",
          attemptCount = 4,
          resolvedAgentId = "original-validator",
          finished = true,
          outputArtifact = "retained-gate-output",
        ),
      ),
    )
    assertTrue(
      recorder.appendLedgerEntry(
        FeatureTaskRuntimePhaseLedgerRequest(
          workflowId = workflowId,
          phaseId = "validate",
          action = FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
          attemptCount = 4,
          resolvedAgentId = "original-validator",
        ),
      ),
    )
    assertTrue(
      recorder.appendCheckpointIdentity(
        AppendCheckpointIdentityArgs(
          workflowId = workflowId, issueKey = "SKILL-384", subtaskId = "2", branch = "feat/SKILL-384",
          phaseId = "review", loopId = null, generation = 0, parentSha = "a".repeat(40),
          ownedPaths = listOf("src/Changed.kt"), commitSha = "b".repeat(40),
        ),
      ),
    )
  }

  private fun seedGateAndHistory(
    recorder: FeatureTaskRuntimePhaseRecorder,
    workflowId: String,
    case: GateRecoveryCase,
  ) {
    assertTrue(
      recorder.recordPhaseState(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = workflowId,
          phaseId = case.producer,
          status = "completed",
          attemptCount = 4,
          resolvedAgentId = "original-gate",
          finished = true,
          outputArtifact = "retained-gate-output",
        ),
      ),
    )
    if (!case.missingPayload) {
      val bytes = "retained-gate-output".toByteArray()
      recorder.retainProducerOutput(
        ProducerOutputEvidence(
          workflowId, case.producer, 4, "original-gate", "runtime", testHarnessClock.instant(),
          bytes.size.toLong(), sha256HexUtf8("retained-gate-output"), bytes,
        ),
      )
    }
    assertTrue(
      recorder.recordPhaseState(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = workflowId,
          phaseId = "write_history",
          status = "running",
          attemptCount = 1,
          resolvedAgentId = "history-agent",
          finished = false,
        ),
      ),
    )
  }

  private fun seedGateLedgerAndCheckpoint(
    recorder: FeatureTaskRuntimePhaseRecorder,
    workflowId: String,
    case: GateRecoveryCase,
  ) {
    if (!case.missingLedger) {
      assertTrue(
        recorder.appendLedgerEntry(
          FeatureTaskRuntimePhaseLedgerRequest(
            workflowId = workflowId,
            phaseId = case.producer,
            action = FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
            attemptCount = if (case.mismatchedLedgerAttempt) 4 - 1 else 4,
            resolvedAgentId = "original-gate",
          ),
        ),
      )
    }
    if (!case.missingPriorCheckpoint) {
      assertTrue(
        recorder.appendCheckpointIdentity(
          AppendCheckpointIdentityArgs(
            workflowId = workflowId, issueKey = "SKILL-384", subtaskId = "2", branch = "feat/SKILL-384",
            phaseId = case.producer, loopId = null, generation = 0, parentSha = "a".repeat(40),
            ownedPaths = listOf("src/Changed.kt"), commitSha = "b".repeat(40),
          ),
        ),
      )
    }
  }

  private fun seedDownstreamEvidence(
    recorder: FeatureTaskRuntimePhaseRecorder,
    workflowId: String,
    case: GateRecoveryCase,
  ) {
    when {
      case.downstreamRecord ->
        assertTrue(
          recorder.recordPhaseState(
            FeatureTaskRuntimePhaseStateRequest(
              workflowId = workflowId,
              phaseId = "write_history",
              status = "completed",
              attemptCount = 1,
              resolvedAgentId = "history-agent",
              finished = true,
            ),
          ),
        )
      case.downstreamLedger ->
        assertTrue(
          recorder.appendLedgerEntry(
            FeatureTaskRuntimePhaseLedgerRequest(
              workflowId = workflowId,
              phaseId = "write_history",
              action = FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
              attemptCount = 1,
              resolvedAgentId = "downstream-agent",
            ),
          ),
        )
      case.downstreamCheckpoint ->
        assertTrue(
          recorder.appendCheckpointIdentity(
            AppendCheckpointIdentityArgs(
              workflowId = workflowId, issueKey = "SKILL-384", subtaskId = "2", branch = "feat/SKILL-384",
              phaseId = "write_history", loopId = null, generation = 0, parentSha = "c".repeat(40),
              ownedPaths = listOf("src/Other.kt"), commitSha = "d".repeat(40),
            ),
          ),
        )
      case.irreversibleEvidence ->
        assertTrue(
          recorder.recordPhaseState(
            FeatureTaskRuntimePhaseStateRequest(
              workflowId = workflowId,
              phaseId = "commit_push",
              status = "completed",
              attemptCount = 2,
              resolvedAgentId = "original-finalizer",
              finished = true,
              outputArtifact = "retained-finalization-output",
            ),
          ),
        )
    }
  }

  private fun changeDescriptorAfterAdmission(
    database: DatabaseSessionFactory,
    root: Path,
    workflowId: String,
    case: GateRecoveryCase,
  ) {
    if (case.changedDescriptorAfterAdmission) {
      val row = database.read { requireNotNull(it.workflowStates.getFeatureTaskWorkflow(workflowId)) }
      val artifacts = row.artifactsJson.let { JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(it)) }
      val descriptor =
        requireNotNull(
          JsonCodec.anyToStringAnyMap(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(
              DurableWorkflowArtifacts.fromMap(requireNotNull(artifacts)),
            ),
          ),
        )
      val policies =
        (descriptor[PlanKeys.EFFECTIVE_POLICIES] as List<*>).map {
          requireNotNull(JsonCodec.anyToStringAnyMap(it))
        }
      val changed =
        descriptor + (
          PlanKeys.EFFECTIVE_POLICIES to
            policies.map { policy ->
              if (policy[PlanKeys.ID] == "gate-commands") {
                policy + (
                  PlanKeys.SEMANTIC_DIGEST to
                    "0".repeat(
                      64,
                    )
                )
              } else {
                policy
              }
            }
        )
      SlotBaselineSqlite.updateFeatureTaskArtifacts(
        root.resolve("metrics.db"),
        workflowId,
        JsonCodec.mapToJsonString(
          row.toSnapshot().artifacts +
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(
              changed,
            ),
        ),
      )
    }
  }

  private fun seedFinalizationEvidence(
    recorder: FeatureTaskRuntimePhaseRecorder,
    workflowId: String,
    case: RecoveryCase,
  ) {
    case.finalizationStep?.let { phase ->
      if (case.ledgerOnly) {
        assertTrue(
          recorder.appendLedgerEntry(
            FeatureTaskRuntimePhaseLedgerRequest(
              workflowId = workflowId,
              phaseId = phase,
              action = FeatureTaskRuntimePhaseLedgerAction.START,
              attemptCount = 2,
              resolvedAgentId = "original-finalizer",
            ),
          ),
        )
      } else {
        assertTrue(
          recorder.recordPhaseState(
            FeatureTaskRuntimePhaseStateRequest(
              workflowId = workflowId,
              phaseId = phase,
              status = case.finalizationStatus,
              attemptCount = 2,
              resolvedAgentId = "original-finalizer",
              finished = case.finalizationStatus == "completed",
              outputArtifact = "retained-finalization-output",
            ),
          ),
        )
      }
    }
  }

  private fun recoveryRecorder(database: DatabaseSessionFactory) =
    featureTaskRuntimePhaseRecorder(
      database,
      NoopWorkflowSnapshotValidator,
      AcceptingFeatureTaskRuntimeWireArtifactValidator,
      AcceptingFeatureTaskRuntimeWireArtifactValidator,
      testHarnessClock,
      NoopRuntimeDiagnostics,
    )
}
