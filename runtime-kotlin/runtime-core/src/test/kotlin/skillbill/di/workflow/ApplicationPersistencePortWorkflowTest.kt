package skillbill.di.workflow

import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowLatestResult
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowResumeResult
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS.label()
private val FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.label()
private val FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.label()

class ApplicationPersistencePortWorkflowTest {
  @Test
  fun `workflow service owns implement rows list resume and continuation through ports`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)

    val opened =
      service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001")
        as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId
    val updated =
      service.update(
        WorkflowFamilyKind.TASK_RUNTIME,
        WorkflowUpdateRequest(
          workflowId = workflowId,
          workflowStatus = WorkflowStatus.BLOCKED.wireValue,
          currentStepId = "implement",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "implement", "status" to "blocked", "attempt_count" to 1),
              ),
            ),
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                "preplan_digest" to mapOf("ok" to true),
                FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
                  mapOf(
                    "plan" to completedPhaseRecord("plan", outputArtifact = """{"task_count":1}"""),
                  ),
              ),
            ),
        ),
      ) as WorkflowUpdateResult.Ok
    val listed = service.list(WorkflowFamilyKind.TASK_RUNTIME)
    val latest = service.latest(WorkflowFamilyKind.TASK_RUNTIME) as WorkflowLatestResult.Ok
    val resumed =
      service.resume(WorkflowFamilyKind.TASK_RUNTIME, workflowId) as WorkflowResumeResult.Ok
    val continued =
      service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, workflowId)
        as WorkflowContinueResult.Standard

    assertEquals(listOf("transaction", "transaction", "read", "read", "read", "transaction"), database.calls)
    assertEquals("blocked", updated.acknowledgement.workflowStatus.wireValue)
    assertEquals(1, listed.workflowCount)
    assertEquals(workflowId, latest.summary.workflowId)
    assertEquals(emptyList<String>(), resumed.resume.missingArtifacts)
    assertEquals("reopened", continued.view.continueStatus.wireValue)
  }

  @Test
  fun `workflow service owns task runtime rows through ports for save load list latest`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)

    val first =
      service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001")
        as WorkflowOpenResult.Ok
    val second =
      service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-002")
        as WorkflowOpenResult.Ok

    val got =
      service.get(WorkflowFamilyKind.TASK_RUNTIME, first.workflowId)
        as WorkflowGetResult.Ok
    val listed = service.list(WorkflowFamilyKind.TASK_RUNTIME)
    val latest = service.latest(WorkflowFamilyKind.TASK_RUNTIME) as WorkflowLatestResult.Ok

    assertEquals("bill-feature-task", got.snapshot.workflowName)
    assertEquals("runtime", got.snapshot.mode)
    assertEquals(2, listed.workflowCount)
    assertEquals(second.workflowId, latest.summary.workflowId)
    assertEquals(0, service.list(WorkflowFamilyKind.VERIFY).workflowCount)
  }

  @Test
  fun `task runtime recorder mints timestamps and persists per-phase record and append-only ledger`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = testPhaseRecorder(database)

    val opened =
      service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001")
        as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    assertTrue(recorder.appendPlanLedger(workflowId, FeatureTaskRuntimePhaseLedgerAction.START))
    assertTrue(recorder.recordPlanPhase(workflowId, status = "running", finished = false))
    assertTrue(
      recorder.recordPlanPhase(
        workflowId,
        status = "completed",
        finished = true,
        outputArtifact = """{"contract_version":"0.2"}""",
      ),
    )
    assertTrue(recorder.appendPlanLedger(workflowId, FeatureTaskRuntimePhaseLedgerAction.COMPLETE))

    val artifacts =
      decodeArtifactsForTest(
        requireNotNull(workflowRepository.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson,
      )
    val phaseRecords =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(artifacts[FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY]),
      )
    val planRecord = requireNotNull(JsonCodec.anyToStringAnyMap(phaseRecords["plan"]))
    assertEquals("completed", planRecord["status"])
    assertEquals("agent-plan-1", planRecord["resolved_agent_id"])

    assertTrue((planRecord["started_at"] as String).isNotBlank())
    assertTrue((planRecord["finished_at"] as String).isNotBlank())
    assertTrue((planRecord["duration_millis"] as Number).toLong() >= 0)
    assertEquals("""{"contract_version":"0.2"}""", planRecord["output_artifact"])
    val ledger =
      requireNotNull(
        JsonCodec.anyToStringAnyMapList(artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY]),
      )
    val sequences = ledger.map { (it["sequence_number"] as Number).toInt() }
    assertEquals(listOf(0, 1), sequences)
    assertEquals(sequences.sorted(), sequences)
    assertEquals(listOf("start", "complete"), ledger.map { it["action"] })
  }

  @Test
  fun `recording a briefing persists the delivered projection under its own artifact key`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val recorder = testPhaseRecorder(database)
    val workflowId = openTaskRuntimeWorkflow(database)

    recorder.recordPhaseBriefing(workflowId, handoffBriefing())

    val artifacts =
      decodeArtifactsForTest(
        requireNotNull(workflowRepository.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson,
      )
    assertTrue(artifacts.containsKey(FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY))
    val delivered = requireNotNull(recorder.loadDeliveredProjections(workflowId))["implement"]
    assertEquals(handoffBriefing().handoffEnvelope, requireNotNull(delivered).envelope)
    assertEquals(1, delivered.iteration)

    recorder.recordPhaseBriefing(workflowId, handoffBriefing())
    assertEquals(2, requireNotNull(recorder.loadDeliveredProjections(workflowId))["implement"]?.iteration)
    val afterSecondDelivery =
      decodeArtifactsForTest(
        requireNotNull(workflowRepository.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson,
      )
    val deliveredHistory =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(afterSecondDelivery[FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY]),
      )
    assertEquals(1, deliveredHistory.size, "only the latest delivered projection per consumer phase is retained")
    assertTrue(
      deliveredHistory.keys.single().let { key -> "|2|" in key && "|plan#1|" in key },
      "retained key must be the bumped iteration with normalized source producer identity",
    )
  }

  @Test
  fun `an unsupported envelope contract version is rejected at the briefing write seam`() {
    val database = FakeDatabaseSessionFactory(workflows = InMemoryWorkflowStateRepository())
    val recorder = testPhaseRecorder(database)
    val workflowId = openTaskRuntimeWorkflow(database)

    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        recorder.recordPhaseBriefing(
          workflowId,
          handoffBriefing(envelope = handoffEnvelope().copy(contractVersion = "9.9")),
        )
      }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.SCHEMA_INVALID, error.failureKind)
    assertEquals("implement", error.consumerPhaseId)
  }

  @Test
  fun `projection rejection emits content free classified telemetry before briefing delivery`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val telemetry = RecordingProjectionLifecycleTelemetryRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository, lifecycleTelemetry = telemetry)
    val recorder = testPhaseRecorder(database)
    val workflowId = openTaskRuntimeWorkflow(database)
    val rejection =
      InvalidFeatureTaskRuntimeHandoffProjectionError(
        InvalidFeatureTaskRuntimeHandoffProjectionContext(
          workflowId = workflowId,
          consumerPhaseId = "implement",
          projectionName = "plan_receipt",
          projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
          projectionContractVersion = "0.2",
          failureKind = FeatureTaskRuntimeHandoffProjectionFailureKind.CHECKPOINT_POLICY_VIOLATION,
          reason = "repository checkpoint differs",
        ),
      )

    assertTrue(recorder.recordProjectionRejection(workflowId, "implement", rejection, "checkpoint-2"))

    val measurement = telemetry.projectionMeasurements.single()
    assertEquals(FeatureTaskRuntimeProjectionFailureClassification.STALE_CHECKPOINT, measurement.failureClassification)
    assertEquals(0, measurement.deliveredProjectionUtf8Bytes)
    assertEquals(0, measurement.privateEvidenceUtf8Bytes)
  }

  @Test
  fun `an undeclared wire key on a durable envelope is rejected at the briefing read seam`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val recorder = testPhaseRecorder(database)
    val workflowId = openTaskRuntimeWorkflow(database)
    recorder.recordPhaseBriefing(workflowId, handoffBriefing())

    corruptDurableEnvelope(workflowRepository, workflowId) { envelope ->
      envelope + ("upstream_outputs_by_phase_id" to mapOf("plan" to "raw payload"))
    }

    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        recorder.loadPhaseBriefings(workflowId)
      }
    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.SCHEMA_INVALID, error.failureKind)
  }

  @Test
  fun `an unsupported contract version on a durable envelope is rejected at the briefing read seam`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val recorder = testPhaseRecorder(database)
    val workflowId = openTaskRuntimeWorkflow(database)
    recorder.recordPhaseBriefing(workflowId, handoffBriefing())

    corruptDurableEnvelope(workflowRepository, workflowId) { it + ("contract_version" to "9.9") }

    assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> { recorder.loadPhaseBriefings(workflowId) }
  }

  @Test
  fun `operator blocked-phase retry stays active through launch and clears on terminal transition`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = testPhaseRecorder(database)
    val workflowId =
      (
        service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001")
          as WorkflowOpenResult.Ok
      ).workflowId
    val reason = "Use the operator-approved fresh-process isolation boundary."
    recorder.recordRuntimePhase(
      workflowId,
      phaseId = "implement",
      status = "blocked",
      finished = false,
      blockedReason = "native adapter unavailable",
    )

    val retried = service.retryBlockedFeatureTaskRuntimePhase(workflowId, "implement", reason)

    assertTrue(retried is WorkflowUpdateResult.Ok)
    assertEquals(reason, recorder.loadOperatorBlockRetry(workflowId)?.reason)
    recorder.appendLedgerEntry(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = workflowId,
        action = FeatureTaskRuntimePhaseLedgerAction.START,
        phaseId = "implement",
        attemptCount = 1,
        resolvedAgentId = "codex",
      ),
    )
    assertEquals(reason, recorder.loadOperatorBlockRetry(workflowId)?.reason)
    recorder.appendLedgerEntry(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = workflowId,
        action = FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
        phaseId = "implement",
        attemptCount = 1,
        resolvedAgentId = "codex",
      ),
    )
    assertEquals(null, recorder.loadOperatorBlockRetry(workflowId))
  }

  @Test
  fun `operator blocked-phase retry reopens the authoritative goal manifest`() {
    val fixture = blockedGoalChildRetryFixture()

    val retried =
      fixture.service.retryBlockedFeatureTaskRuntimePhase(
        fixture.childWorkflowId,
        "implement",
        "Use the operator-approved fresh-process isolation boundary.",
      )

    assertTrue(retried is WorkflowUpdateResult.Ok)
    val manifest = loadTestDecompositionManifest(fixture.manifestPath)
    assertEquals("in_progress", manifest.status)
    assertEquals("resume", manifest.currentSubtaskIntent.action)
    assertEquals(1, manifest.currentSubtaskIntent.subtaskId)
    assertEquals("in_progress", manifest.subtasks.single().status)
    assertEquals(fixture.childWorkflowId, manifest.subtasks.single().workflowId)
    assertEquals(null, manifest.subtasks.single().blockedReason)
    assertEquals("implement", manifest.subtasks.single().lastResumableStep)
    val parent =
      fixture.service.get(
        WorkflowFamilyKind.TASK_RUNTIME,
        fixture.parentWorkflowId,
      ) as WorkflowGetResult.Ok
    val parentRuntime = parent.snapshot.artifacts["decomposition_runtime"] as Map<*, *>
    assertEquals("in_progress", parentRuntime["status"])
  }

  @Test
  fun `task runtime recorder advances shared steps in lockstep with per-phase records`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = testPhaseRecorder(database)

    val opened =
      service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001")
        as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    assertTrue(recorder.recordRuntimePhase(workflowId, "preplan", status = "running", finished = false))
    assertEquals("running", stepStatusFor(workflowRepository, workflowId, "preplan"))
    assertRuntimeWorkflowRow(
      workflowRepository,
      workflowId,
      currentStepId = "preplan",
      workflowStatus = WorkflowStatus.RUNNING.wireValue,
    )

    assertTrue(recorder.recordRuntimePhase(workflowId, "preplan", status = "completed", finished = true))
    assertEquals("completed", stepStatusFor(workflowRepository, workflowId, "preplan"))

    assertTrue(recorder.recordRuntimePhase(workflowId, "plan", status = "running", finished = false))
    assertEquals("running", stepStatusFor(workflowRepository, workflowId, "plan"))

    assertEquals("completed", stepStatusFor(workflowRepository, workflowId, "preplan"))
    assertRuntimeWorkflowRow(
      workflowRepository,
      workflowId,
      currentStepId = "plan",
      workflowStatus = WorkflowStatus.RUNNING.wireValue,
    )

    assertTrue(recorder.recordRuntimePhase(workflowId, "plan", status = "completed", finished = true))
    assertEquals("completed", stepStatusFor(workflowRepository, workflowId, "plan"))

    assertTrue(
      recorder.recordRuntimePhase(
        workflowId,
        "implement",
        status = "blocked",
        finished = false,
        blockedReason = "needs human",
      ),
    )
    assertEquals("blocked", stepStatusFor(workflowRepository, workflowId, "implement"))
    assertRuntimeWorkflowRow(
      workflowRepository,
      workflowId,
      currentStepId = "implement",
      workflowStatus = WorkflowStatus.BLOCKED.wireValue,
    )

    assertEquals("pending", stepStatusFor(workflowRepository, workflowId, "review"))
  }

  @Test
  fun `task runtime shared steps agree with the runner record-derived status map across mixed statuses`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = testPhaseRecorder(database)

    val opened =
      service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001")
        as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    recorder.recordRuntimePhase(workflowId, "preplan", status = "running", finished = false)
    recorder.recordRuntimePhase(workflowId, "preplan", status = "completed", finished = true)
    recorder.recordRuntimePhase(workflowId, "plan", status = "running", finished = false)
    recorder.recordRuntimePhase(workflowId, "plan", status = "completed", finished = true)
    recorder.recordRuntimePhase(workflowId, "implement", status = "running", finished = false)
    recorder.recordRuntimePhase(
      workflowId,
      "review",
      status = "blocked",
      finished = false,
      blockedReason = "needs human",
    )

    val records = requireNotNull(recorder.loadPhaseRecords(workflowId))
    val recordDerivedStatuses = records.mapValues { (_, record) -> expectedStepStatusForRecord(record) }
    val stepStatusByPhaseId =
      decodeStepsForTest(workflowRepository, workflowId)
        .filter { (phaseId, _) -> phaseId in records.keys }
        .toMap()

    assertEquals(recordDerivedStatuses, stepStatusByPhaseId)
    assertEquals(
      mapOf(
        "preplan" to "completed",
        "plan" to "completed",
        "implement" to "running",
        "review" to "blocked",
      ),
      stepStatusByPhaseId,
    )
  }

  @Test
  fun `task runtime shared step keeps blocked status even when the blocked record carries a finished timestamp`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = testPhaseRecorder(database)

    val opened =
      service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001")
        as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    recorder.recordRuntimePhase(
      workflowId,
      "implement",
      status = "blocked",
      finished = true,
      blockedReason = "needs human",
    )

    val record = requireNotNull(recorder.loadPhaseRecords(workflowId))["implement"]
    assertNotNull(requireNotNull(record).finishedAt)
    assertEquals("blocked", stepStatusFor(workflowRepository, workflowId, "implement"))
  }

  @Test
  fun `task runtime shared step maps a running record with a finished timestamp to completed`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = testPhaseRecorder(database)

    val opened =
      service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001")
        as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    recorder.recordRuntimePhase(workflowId, "preplan", status = "running", finished = true)

    val record = requireNotNull(recorder.loadPhaseRecords(workflowId))["preplan"]
    assertEquals(WorkflowStepStatus.RUNNING, requireNotNull(record).status)
    assertNotNull(record.finishedAt)
    assertEquals("completed", stepStatusFor(workflowRepository, workflowId, "preplan"))
  }

  @Test
  fun `task runtime read loud-fails on malformed persisted phase record`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = testPhaseRecorder(database)

    val opened =
      service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001")
        as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    val malformedArtifactsJson =
      """
      {
        "feature_task_runtime_phase_records": {
          "plan": {
            "phase_id": "plan",
            "status": "running",
            "attempt_count": 1,
            "started_at": "2026-06-02T10:00:00Z"
          }
        }
      }
      """.trimIndent()
    val record = requireNotNull(workflowRepository.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME))
    workflowRepository.saveFeatureTaskWorkflow(record.copy(artifactsJson = malformedArtifactsJson), RUNTIME)

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      recorder.recordPhaseState(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = workflowId,
          phaseId = "implement",
          status = "running",
          attemptCount = 1,
          resolvedAgentId = "agent-implement-1",
          finished = false,
        ),
      )
    }
  }

  @Test
  fun `task runtime ledger append loud-fails on malformed persisted ledger entry`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = testPhaseRecorder(database)

    val opened =
      service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001")
        as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    val malformedArtifactsJson =
      """
      {
        "feature_task_runtime_phase_ledger": [
          {
            "sequence_number": 0,
            "timestamp": "2026-06-02T10:00:00Z",
            "phase_id": "plan",
            "attempt_count": 1
          }
        ]
      }
      """.trimIndent()
    val record = requireNotNull(workflowRepository.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME))
    workflowRepository.saveFeatureTaskWorkflow(record.copy(artifactsJson = malformedArtifactsJson), RUNTIME)

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      recorder.appendPlanLedger(workflowId, FeatureTaskRuntimePhaseLedgerAction.RESUME)
    }
  }

  @Test
  fun `task runtime ledger append loud-fails when persisted ledger is not a list`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = testPhaseRecorder(database)

    val opened =
      service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001")
        as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    val malformedArtifactsJson =
      """
      {
        "feature_task_runtime_phase_ledger": {"not": "a list"}
      }
      """.trimIndent()
    val record = requireNotNull(workflowRepository.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME))
    workflowRepository.saveFeatureTaskWorkflow(record.copy(artifactsJson = malformedArtifactsJson), RUNTIME)

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      recorder.appendPlanLedger(workflowId, FeatureTaskRuntimePhaseLedgerAction.RESUME)
    }
  }

  @Test
  fun `task runtime ledger seeds next sequence from persisted max across a re-read`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val recorder = testPhaseRecorder(database)

    val opened =
      service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001")
        as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    assertTrue(recorder.appendPlanLedger(workflowId, FeatureTaskRuntimePhaseLedgerAction.START))
    assertTrue(recorder.appendPlanLedger(workflowId, FeatureTaskRuntimePhaseLedgerAction.COMPLETE))

    assertTrue(recorder.appendPlanLedger(workflowId, FeatureTaskRuntimePhaseLedgerAction.RESUME))

    val artifacts =
      decodeArtifactsForTest(
        requireNotNull(workflowRepository.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson,
      )
    val ledger =
      requireNotNull(
        JsonCodec.anyToStringAnyMapList(artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY]),
      )
    val sequences = ledger.map { (it["sequence_number"] as Number).toInt() }
    assertEquals(listOf(0, 1, 2), sequences)
    assertEquals(listOf("start", "complete", "resume"), ledger.map { it["action"] })
  }
}
