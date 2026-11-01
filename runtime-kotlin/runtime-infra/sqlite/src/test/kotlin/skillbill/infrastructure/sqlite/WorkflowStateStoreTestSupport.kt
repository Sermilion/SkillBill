package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.workflow.featuretask.FeatureVerifyWorkflowStateStore
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal val testWorkflowSnapshotValidator =
  object : WorkflowSnapshotValidator {
    override fun validate(
      snapshot: WorkflowStateSnapshot,
      slug: String,
    ) = Unit
  }

internal fun verifyWorkflowStore(connection: Connection): FeatureVerifyWorkflowStateStore =
  FeatureVerifyWorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)

internal fun assertRuntimeAndVerifyStateTransitions(
  connection: Connection,
  initial: WorkflowStateRecord,
  startedAt: String,
) {
  val store = WorkflowStateStore(connection, Clock.systemUTC(), testWorkflowSnapshotValidator)
  val verify = verifyWorkflowStore(connection)
  val runtimeInitial =
    initial.copy(
      workflowId = "wftr-state-entry",
      sessionId = "ftr-state-entry",
      mode = RUNTIME,
    )
  store.saveFeatureTaskWorkflow(runtimeInitial, RUNTIME)
  val runtimeInserted = assertNotNull(store.getFeatureTaskWorkflowAsMode("wftr-state-entry", RUNTIME))
  assertEquals(startedAt, runtimeInserted.stateEnteredAt)

  store.saveFeatureTaskWorkflow(
    runtimeInserted.copy(workflowStatus = WorkflowStatus.BLOCKED.wireValue, currentStepId = "plan"),
    RUNTIME,
  )
  val runtimeTransitioned = assertNotNull(store.getFeatureTaskWorkflowAsMode("wftr-state-entry", RUNTIME))
  assertTrue(Instant.parse(runtimeTransitioned.stateEnteredAt).isAfter(Instant.parse(startedAt)))
  assertEquals(false, runtimeTransitioned.stateEnteredAtEstimated)

  val verifyInitial =
    WorkflowStateRecord(
      workflowId = "wfv-state-entry",
      sessionId = "fvr-state-entry",
      workflowName = "bill-feature-verify",
      contractVersion = "0.1",
      workflowStatus = WorkflowStatus.RUNNING.wireValue,
      currentStepId = "gather_diff",
      stepsJson = "[]",
      artifactsJson = "{}",
      startedAt = startedAt,
      updatedAt = null,
      finishedAt = null,
    )
  verify.saveWorkflow(verifyInitial)
  val verifyInserted = assertNotNull(verify.getWorkflow("wfv-state-entry"))
  assertEquals(startedAt, verifyInserted.stateEnteredAt)

  verify.saveWorkflow(verifyInitial.copy(currentStepId = "code_review"))
  val verifySameStatus = assertNotNull(verify.getWorkflow("wfv-state-entry"))
  assertEquals(startedAt, verifySameStatus.stateEnteredAt)

  verify.saveWorkflow(
    verifySameStatus.copy(workflowStatus = WorkflowStatus.COMPLETED.wireValue, currentStepId = "finish"),
  )
  val verifyTransitioned = assertNotNull(verify.getWorkflow("wfv-state-entry"))
  assertTrue(Instant.parse(verifyTransitioned.stateEnteredAt).isAfter(Instant.parse(startedAt)))
  assertEquals(false, verifyTransitioned.stateEnteredAtEstimated)
}

internal fun seedRunningRowWithLease(
  store: WorkflowStateStore,
  workflowId: String,
  ownerToken: String,
  expiresAt: String,
) {
  store.saveFeatureTaskWorkflow(
    workflowRow(
      workflowId,
      "ftr-$workflowId",
      "bill-feature-task",
      "implement",
      RUNTIME,
    ).copy(workflowStatus = WorkflowStatus.RUNNING.wireValue),
    RUNTIME,
  )
  val updatedAt = requireNotNull(store.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).updatedAt
  val ownership = workerOwnership(workflowId, generation = 1, ownerToken = ownerToken).copy(expiresAt = expiresAt)
  check(store.acquireFeatureTaskRuntimeWorker(ownership, updatedAt))
}

internal fun interruptionReasonOf(
  connection: Connection,
  workflowId: String,
): String? =
  connection.prepareStatement(
    "SELECT interruption_reason FROM feature_task_workflows WHERE workflow_id = ?",
  ).use { statement ->
    statement.setString(1, workflowId)
    statement.executeQuery().use { rows -> if (rows.next()) rows.getString("interruption_reason") else null }
  }

internal fun workerOwnership(
  workflowId: String,
  generation: Long,
  ownerToken: String,
): FeatureTaskRuntimeWorkerOwnership =
  FeatureTaskRuntimeWorkerOwnership(
    workflowId = workflowId,
    generation = generation,
    ownerToken = ownerToken,
    hostIdentity = "host-a",
    bootIdentity = "boot-a",
    pid = 1234,
    processBirthToken = "birth-1234",
    leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
    heartbeatAt = "2026-07-14T10:00:00Z",
    expiresAt = "2026-07-14T10:05:00Z",
    phaseId = "implement",
    phaseAttempt = 1,
  )

internal val taskRuntimeArtifactsJson: String =
  """
  {
    "feature_task_runtime_phase_records": {
      "plan": {
        "phase_id": "plan",
        "status": "completed",
        "attempt_count": 1,
        "started_at": "2026-06-02T10:00:00Z",
        "finished_at": "2026-06-02T10:01:30Z",
        "duration_millis": 90000,
        "resolved_agent_id": "agent-plan-1",
        "output_artifact": "{\"contract_version\":\"0.1\",\"plan\":\"ok\"}"
      }
    },
    "feature_task_runtime_phase_ledger": [
      {
        "action": "start",
        "sequence_number": 0,
        "timestamp": "2026-06-02T10:00:00Z",
        "phase_id": "plan",
        "attempt_count": 1,
        "resolved_agent_id": "agent-plan-1"
      },
      {
        "action": "complete",
        "sequence_number": 1,
        "timestamp": "2026-06-02T10:01:30Z",
        "phase_id": "plan",
        "attempt_count": 1,
        "resolved_agent_id": "agent-plan-1"
      }
    ]
  }
  """.trimIndent()

internal fun auditRepairArtifactsJson(contractVersion: String = "0.2"): String =
  """
  {"feature_task_runtime_audit_repair_state":{
    "contract_version":"$contractVersion",
    "accepted_plans":[{"contract_version":"0.2","gaps":[{
      "gap_id":"ac-001-gap-1","acceptance_criterion_ref":"AC-001",
      "acceptance_criterion_text":"Criterion","failure_evidence":{"observation":"required_behavior_absent",
        "artifact_ref":"runtime-kotlin","check_ref":"AC-001"},
      "diagnosis":"Diagnosis","affected_boundary":"runtime","repair_items":[{
        "repair_item_id":"ac-001-gap-1-item-1","intended_outcome":"Outcome",
        "implementation_actions":["Implement"],"affected_paths_or_symbols":["src/Foo.kt"],
        "required_verification":["Test"],"depends_on":[],"status":"pending"
      }]
    }]}],
    "latest_plan":{"contract_version":"0.2","gaps":[{
      "gap_id":"ac-001-gap-1","acceptance_criterion_ref":"AC-001",
      "acceptance_criterion_text":"Criterion","failure_evidence":{"observation":"required_behavior_absent",
        "artifact_ref":"runtime-kotlin","check_ref":"AC-001"},
      "diagnosis":"Diagnosis","affected_boundary":"runtime","repair_items":[{
        "repair_item_id":"ac-001-gap-1-item-1","intended_outcome":"Outcome",
        "implementation_actions":["Implement"],"affected_paths_or_symbols":["src/Foo.kt"],
        "required_verification":["Test"],"depends_on":[],"status":"pending"
      }]
    }]},
    "execution_history":[],"prior_gap_dispositions":[],
    "unresolved_gap_ledger":{"contract_version":"0.2","gaps":[{
      "gap_id":"ac-001-gap-1","acceptance_criterion_ref":"AC-001","generation":1
    }]},
    "repository_fingerprint":"fingerprint",
    "progress":{"first_pass_convergence":false,"recurring_gap_count":0,"new_gap_count":1,
      "attempted_repair_item_count":0,"resolved_repair_item_count":0,"audit_gap_iteration_count":1}
  }}
  """.trimIndent()

internal fun insertFeatureVerifySession(connection: Connection) {
  connection.prepareStatement(
    """
    INSERT INTO feature_verify_sessions (
      session_id,
      acceptance_criteria_count,
      rollout_relevant,
      spec_summary
    ) VALUES (?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, "fvr-session")
    statement.setInt(2, 4)
    statement.setInt(3, 1)
    statement.setString(4, "Verify workflow runtime")
    statement.executeUpdate()
  }
}

internal fun workflowRow(
  workflowId: String,
  sessionId: String,
  workflowName: String,
  currentStepId: String,
  mode: FeatureTaskWorkflowMode? = null,
): WorkflowStateRecord =
  WorkflowStateRecord(
    workflowId = workflowId,
    sessionId = sessionId,
    workflowName = workflowName,
    contractVersion = "0.1",
    workflowStatus = WorkflowStatus.RUNNING.wireValue,
    currentStepId = currentStepId,
    stepsJson = "[]",
    artifactsJson = "{}",
    startedAt = null,
    updatedAt = null,
    finishedAt = null,
    mode = mode,
  )

internal fun goalChildWorkflow(
  workflowId: String,
  parentWorkflowId: String,
): WorkflowStateRecord =
  workflowRow(
    workflowId = workflowId,
    sessionId = "ftr-$workflowId",
    workflowName = "bill-feature-task",
    currentStepId = "preplan",
    mode = RUNTIME,
  ).copy(
    artifactsJson =
      """{"goal_continuation":{"issue_key":"SKILL-128","subtask_id":1,"parent_workflow_id":"$parentWorkflowId"}}""",
  )

internal fun goalChildIdentity(row: WorkflowStateRecord): FeatureTaskExecutionIdentity =
  FeatureTaskExecutionIdentity(
    workflowId = row.workflowId,
    normalizedIssueKey = "SKILL-128",
    repositoryIdentity = "repo",
    governedSpecPath = ".feature-specs/SKILL-128/spec.md",
    mode = RUNTIME,
    routeScope = FeatureTaskRouteScope.GOAL_CHILD,
  )
