package skillbill.engine.featuretask.runner

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeValidationGateCoordinator
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateStatusProjectionTest {
  @Test
  fun `workflow status and settled validate artifact expose the same gate execution evidence`() {
    val workflowId = "wfl-gate-evidence-status"
    val harness = statusHarness()
    val recorder = harness.recorder
    recorder.openTestWorkflow(workflowId, "goal-gate-evidence")
    val output = gateEvidenceOutput()
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = workflowId,
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        status = "completed",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = true,
        outputArtifact = output,
      ),
    )
    val status = requireNotNull(harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId)))
    val projected = requireNotNull(status.validationGateExecutionEvidence)
    val settled = settledValidationResult(output)
    assertEquals(settled?.get(ValidationEvidencePayloadKeys.CHECKS), projected.checks)
    assertEquals(settled?.get(ValidationEvidencePayloadKeys.GATE_RUN_COUNT), projected.gateRunCount)
    val settledRun =
      (settled?.get(ValidationEvidencePayloadKeys.GATE_RUNS) as? List<*>)
        ?.single()
        ?.let(JsonCodec::anyToStringAnyMap)
    assertEquals(
      settledRun?.get(ValidationEvidencePayloadKeys.CACHE_MODE),
      projected.gateRuns.single().cacheMode.wireValue,
    )
    assertEquals(
      settledRun?.get(ValidationEvidencePayloadKeys.OUTCOME),
      projected.gateRuns.single().outcome.wireValue,
    )
    assertEquals(
      settledRun?.get(ValidationEvidencePayloadKeys.EXECUTED_WORK_UNITS),
      projected.gateRuns.single().executedWorkUnits,
    )
    assertEquals(
      listOf("runtime-engine|compileKotlin", "runtime-engine|test"),
      projected.checks,
    )
    assertEquals(ValidationGateCacheMode.FORCED_FULL, projected.gateRuns.single().cacheMode)
    assertEquals(ValidationGateRunOutcome.PASSED, projected.gateRuns.single().outcome)
    assertEquals(2, projected.gateRuns.single().executedWorkUnits)
  }

  private fun gateEvidenceOutput(): String =
    FeatureTaskRuntimeValidationGateCoordinator.runtimeOwnedValidationOutput(
      phaseId = "validate",
      repositoryCheckpoint = "checkpoint",
      measurements =
        listOf(
          FeatureTaskRuntimeValidationGateRunRecord(
            durationMs = 1,
            outcome = ValidationGateRunOutcome.PASSED,
            cacheMode = ValidationGateCacheMode.FORCED_FULL,
            executedWorkUnits = 2,
            executedChecks = listOf("runtime-engine|compileKotlin", "runtime-engine|test"),
            command = "./gradlew check --continue",
            exitCode = 0,
            repositoryCheckpoint = "checkpoint",
          ),
        ),
      requiredCommand = "./gradlew check --continue",
    ).payload

  private fun settledValidationResult(payload: String): Map<String, Any?>? {
    val envelope =
      requireNotNull(
        JsonCodec.parseObjectOrNull(payload)
          ?.let(JsonCodec::jsonElementToValue)
          ?.let(JsonCodec::anyToStringAnyMap),
      ) { "settled output envelope missing" }
    return JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS])
      ?.get(ValidationEvidencePayloadKeys.VALIDATION_RESULT)
      ?.let(JsonCodec::anyToStringAnyMap)
  }

  @Test
  fun `workflow status preserves an explicit zero-work run instead of treating it as missing evidence`() {
    val workflowId = "wfl-gate-evidence-zero-work"
    val harness = statusHarness()
    val recorder = harness.recorder
    recorder.openTestWorkflow(workflowId, "goal-gate-evidence-zero-work")
    val output =
      FeatureTaskRuntimeValidationGateCoordinator.runtimeOwnedValidationOutput(
        phaseId = "validate",
        repositoryCheckpoint = "checkpoint",
        measurements =
          listOf(
            FeatureTaskRuntimeValidationGateRunRecord(
              durationMs = 1,
              outcome = ValidationGateRunOutcome.PASSED,
              cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
              executedWorkUnits = 0,
              executedChecks = emptyList(),
              command = "./gradlew check",
              exitCode = 0,
              repositoryCheckpoint = "checkpoint",
            ),
          ),
        requiredCommand = "./gradlew check",
      )
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = workflowId,
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        status = "completed",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = true,
        outputArtifact = output.payload,
      ),
    )

    val evidence =
      requireNotNull(
        requireNotNull(harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId)))
          .validationGateExecutionEvidence,
      )
    assertTrue(evidence.zeroWork)
    assertTrue(evidence.evidenceRecorded)
    assertEquals(emptyList(), evidence.checks)
  }
}
