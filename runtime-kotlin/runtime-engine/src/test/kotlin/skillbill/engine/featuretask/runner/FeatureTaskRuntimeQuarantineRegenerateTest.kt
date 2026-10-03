package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS.label()

class FeatureTaskRuntimeQuarantineRegenerateTest {
  private val legacyImplement =
    """{"contract_version":"0.4","phase_id":"implement","status":"completed","summary":"Legacy implement.",""" +
      """"produced_outputs":{"changed_paths":["src/Foo.kt"],"narration":"free-form legacy body",""" +
      """"reconciled_state":{"reconciled":true,"evidence":"legacy"}}}"""

  @Test
  fun `invalidating a quarantined producer stamps the supplied loop watermark durably`() {
    val harness = runnerHarness()
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), legacyImplement)

    harness.recorder.invalidateQuarantinedProducerRecord(
      WORKFLOW_ID,
      "implement",
      "review_fix",
      edgeIteration = 1,
    )

    val implement = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals("running", implement.status.wireValue, "the settled completion is cleared so the producer relaunches")
    assertEquals(
      "review_fix",
      implement.loopId,
      "the loop id is stamped durably, seeding the resume watermark before the ledger write",
    )
    assertEquals(1, implement.edgeIteration, "the per-edge iteration is stamped durably so the cap cannot reset")
    assertEquals(null, implement.rejectedOutput, "raw rejected evidence must remain outside workflow artifacts")
    assertEquals(null, implement.outputArtifact, "the rejected payload is no longer selectable as the producer output")
  }

  @Test
  fun `a traversal dropping a recorded producer is refused before recovery`() {
    val surviving =
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
      )
    val truncated =
      FeatureTaskRuntimeTransitionDeclaration(
        forwardPhaseIds = surviving,
        backwardEdges =
          FeatureTaskRuntimePhaseWorkflowDefinition.transitions.backwardEdges
            .filter { it.fromPhaseId in surviving && it.destinationPhaseId in surviving },
        loopOnlyPhaseIds = emptySet(),
        entryGates =
          FeatureTaskRuntimePhaseWorkflowDefinition.transitions.entryGates
            .filter { it.phaseId in surviving && it.requiredPhaseId in surviving },
      )
    val harness = runnerHarness(RuntimeHarnessConfig(agentAssignment = phasePerAgentAssignment()))
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), legacyImplement.replace("0.4", "0.7"))
    harness.seedPhase("simplify", "completed", 1, phaseAgent("simplify"), SIMPLIFY_OUTPUT)

    val records = harness.recorder.loadPhaseRecords(WORKFLOW_ID)
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> {
      harness.runner.run(harness.request(truncated))
    }
    assertEquals(records, harness.recorder.loadPhaseRecords(WORKFLOW_ID))
    assertTrue(
      harness.launchedPromptPhaseOrder().none { it == "implement" },
      "a dropped producer is never re-entered",
    )
  }

  @Test
  fun `quarantine evidence is append-only retrievable in order and crash-replay idempotent`() {
    val harness = runnerHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    val first =
      FeatureTaskRuntimeQuarantineEntry(
        producingPhaseId = "implement",
        consumingPhaseId = "audit",
        producingIteration = 1,
        rejectionClass = "planning_projection_schema",
        rejectionDetail = "implement#produced_outputs: projection_kind is missing",
        regenerationAttempt = 1,
        quarantinedAtIteration = 1,
        diagnosticIdentity = "rod_one",
        rejectedRecordByteSize = 11,
        rejectedRecordSha256 = "a".repeat(64),
      )
    val second =
      first.copy(
        producingIteration = 2,
        regenerationAttempt = 2,
        diagnosticIdentity = "rod_two",
        rejectedRecordSha256 = "b".repeat(64),
      )
    harness.recorder.appendQuarantineEntry(WORKFLOW_ID, first)
    harness.recorder.appendQuarantineEntry(WORKFLOW_ID, second)

    val loaded = requireNotNull(harness.recorder.loadQuarantinedRecords(WORKFLOW_ID))
    assertEquals(listOf("rod_one", "rod_two"), loaded.map { it.diagnosticIdentity })

    harness.recorder.appendQuarantineEntry(WORKFLOW_ID, first)
    val reloaded = requireNotNull(harness.recorder.loadQuarantinedRecords(WORKFLOW_ID))
    assertEquals(2, reloaded.size, "an already-recorded entry is never duplicated")
    assertEquals(loaded.map { it.diagnosticIdentity }, reloaded.map { it.diagnosticIdentity })
  }

  @Test
  fun `a pre-change identity-bearing entry decodes with the identity unchanged`() {
    val harness = runnerHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    val identity = "rod_prechange_identity"
    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    artifacts[FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY] =
      mapOf(
        "contract_version" to "0.3",
        "entries" to
          listOf(
            mapOf(
              "producing_phase_id" to "implement",
              "consuming_phase_id" to "audit",
              "producing_iteration" to 1,
              "rejection_class" to "planning_projection_schema",
              "rejection_detail" to "implement#produced_outputs: projection_kind is missing",
              "regeneration_attempt" to 1,
              "quarantined_at_iteration" to 1,
              "diagnostic_identity" to identity,
              "rejected_record_byte_size" to 11,
              "rejected_record_sha256" to "a".repeat(64),
            ),
          ),
      )
    harness.repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)

    val loaded = requireNotNull(harness.recorder.loadQuarantinedRecords(WORKFLOW_ID))
    assertEquals(identity, loaded.single().diagnosticIdentity)
    assertEquals(false, loaded.single().diagnosticDegraded)
  }
}
