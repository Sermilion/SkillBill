package skillbill.engine.featuretask.slot.attempt

import skillbill.agent.model.PhaseOutput
import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionInteractionTraceRound
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairPhaseRecorderScenarios
import skillbill.engine.featuretask.persist.acceptance.SerialRepairSqliteFixture
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.phase.core.auditProseValue
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.runloop.core.CapturedPhaseOutput
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.interaction.unknownScenarios
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcceptanceCriterionInteractionTraceOutputGateTest {
  private val scenarios = AcceptanceCriterionRepairPhaseRecorderScenarios { _, _ -> }

  @Test
  fun `a long audit report with a bare verdict commits without multiplying its evidence`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val (scope, recorder) = scenarios.tracePendingSession(f, worker)
        val phase = auditPhaseRequest(f, 3)
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(phase))
        val report = "Source evidence. ".repeat(14000) + "\nInteraction IS-001: holds"

        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.complete(completedPhaseWrite(phase, report)))

        assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)
        assertEquals(listOf("IS-001"), scope.snapshot?.interactionTrace?.tracedScenarioRefs)
        val saved = assertNotNull(decodePhaseRecords(f.snapshot().artifacts)[phase.phaseId]?.outputArtifact)
        assertEquals(report, auditProseValue(FeatureTaskRuntimeWorkflowArtifactMap.from(JsonCodec.parseValue(saved))))
      }
    }
  }

  @Test
  fun `oversized explicit trace evidence is rejected without committing and can be corrected`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val (scope, recorder) = scenarios.tracePendingSession(f, worker)
        val phase = auditPhaseRequest(f, 3)
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(phase))
        val before = f.snapshot()
        val accounting = scope.snapshot
        val oversized =
          "Interaction IS-001: holds trace " +
            "é".repeat(FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION.maxUtf8Bytes / 2)

        val rejected =
          assertIs<RequiredPhaseWrite.Rejected>(recorder.complete(completedPhaseWrite(phase, oversized)))

        assertTrue(rejected.message.contains("UTF-8 bytes"))
        assertEquals(before, f.snapshot())
        assertEquals(accounting, scope.snapshot)
        assertEquals(
          RequiredPhaseWrite.Acknowledged,
          recorder.complete(completedPhaseWrite(phase, "Interaction IS-001: holds trace src/Flow.kt:10")),
        )
        assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, scope.snapshot?.stage)
      }
    }
  }

  @Test
  fun `first malformed interaction trace completion maps to retry output check`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val (scope, _) = scenarios.tracePendingSession(f, worker)
        val malformedValue = "No scenario lines at all."
        val prose = requireNotNull(auditProseValue(completedAuditEnvelope(malformedValue).envelopeWireMap()))
        assertEquals(malformedValue, prose)
        val run = auditRun(f, scope)
        val reason = requireNotNull(AcceptanceCriterionInteractionTraceRound.reaskRejection(scope, prose))
        val check =
          PhaseOutputGate.interactionTraceCompletionOutputCheck(
            run,
            reason,
            PhaseStepOutputCheck.OUTPUT_VERIFICATION_RULE,
          )
        val retry = assertIs<PhaseStepOutputCheck.Retry>(check)
        assertEquals(reason, retry.reason)
        assertEquals(PhaseStepOutputCheck.OUTPUT_VERIFICATION_RULE, retry.rule)
        assertTrue(
          reason.startsWith("${FeatureTaskRuntimeFailureCode.INVALID_INTERACTION_TRACE_AUDIT_OUTPUT.name}:"),
        )
        assertTrue(reason.contains(AcceptanceCriterionInteractionTraceRound.LINE_GRAMMAR))
        assertNotNull(scope.traceReaskScope)
      }
    }
  }

  @Test
  fun `completion rejection without an active interaction trace stays a schema reject`() {
    val run = auditRun("wf-no-interaction-trace", Path.of("."))
    val reason = "Audit verdict requires a no-change claim on the implementation output."
    val check =
      PhaseOutputGate.interactionTraceCompletionOutputCheck(
        run,
        reason,
        PhaseStepOutputCheck.OUTPUT_VERIFICATION_RULE,
      )
    val rejected = assertIs<PhaseStepOutputCheck.Reject>(check)
    assertEquals(reason, rejected.reason)
    assertEquals(PhaseStepOutputCheck.OUTPUT_VERIFICATION_RULE, rejected.rule)
  }

  @Test
  fun `second malformed interaction trace after one correction degrades without hand reask`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val (scope, recorder) = scenarios.tracePendingSession(f, worker)
        val malformed = "No scenario lines at all."
        assertNotNull(AcceptanceCriterionInteractionTraceRound.reaskRejection(scope, malformed))
        assertNotNull(scope.traceReaskScope)
        assertNull(AcceptanceCriterionInteractionTraceRound.reaskRejection(scope, malformed))

        val trace = auditPhaseRequest(f, 3)
        assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(trace))
        assertEquals(
          RequiredPhaseWrite.Acknowledged,
          recorder.complete(completedPhaseWrite(trace, malformed)),
        )

        val degraded = assertNotNull(scope.snapshot)
        assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, degraded.stage)
        assertEquals(listOf("IS-001"), degraded.interactionTrace.unknownScenarioRefs)
        assertEquals(listOf("malformed audit output"), degraded.unknownScenarios().map { it.reason })
      }
    }
  }

  @Test
  fun `malformed interaction trace rejection diagnostic retains envelope value bytes`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val (scope, _) = scenarios.tracePendingSession(f, worker)
        val envelopeValue =
          "Interaction IS-001: holds trace src/Foo.kt:1\nRecheck AC-001: holds evidence not-a-citation"
        val attested = completedAuditEnvelope(envelopeValue)
        val run = auditRun(f, scope)
        val stdoutSummary = "Audit complete."
        val capture =
          ValidatedOutputCapture(
            run = run,
            iteration = 1,
            captured = CapturedPhaseOutput.fromBytes(stdoutSummary.encodeToByteArray(), stdoutSummary),
            repairEvidence = null,
            fileManifest = FeatureTaskRuntimePhaseFileManifest(emptyList(), emptyList()),
          )
        val diagnostic =
          PhaseOutputGate.interactionTraceRejectionDiagnosticCapture(
            capture,
            attested.envelopeWireMap(),
          )
        val envelopeBytes = envelopeValue.encodeToByteArray()
        assertContentEquals(envelopeBytes, diagnostic.bytes)
        assertEquals(envelopeValue, diagnostic.text)
        assertFalse(diagnostic.bytes.contentEquals(stdoutSummary.encodeToByteArray()))
        assertEquals(RejectedOutputDiagnosticService.sha256(envelopeBytes), diagnostic.sha256)
      }
    }
  }

  private fun auditRun(
    f: SerialRepairSqliteFixture,
    scope: AcceptanceCriterionRepairRunState,
  ): PhaseRun = auditRun(f.workflowId, f.root, scope)

  private fun auditRun(
    workflowId: String,
    repoRoot: Path,
    scope: AcceptanceCriterionRepairRunState? = null,
  ): PhaseRun {
    val request =
      FeatureTaskRuntimeRunRequest(
        issueKey = workflowId,
        workflowId = workflowId,
        sessionId = "sess-interaction-trace-gate",
        runInvariants =
          FeatureTaskRuntimeRunInvariants(
            specReference = "SKILL-480 interaction trace gate",
            featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
            acceptanceCriteria = scope?.binding?.authoredCriteria.orEmpty().ifEmpty { listOf("AC-001") },
            mandatesAndOverrides = emptyList(),
            codeReviewMode = CodeReviewExecutionMode.INLINE,
          ),
        invokedAgentId = "codex",
        repoRoot = repoRoot,
        acceptanceCriterionRepair = scope,
      )
    return PhaseRun(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      declaration =
        phaseDeclaration(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          FeatureTaskRuntimeFeatureSize.MEDIUM,
          emptySet(),
        ),
      resolvedAgent =
        FeatureTaskRuntimeResolvedPhaseAgent(
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          invokedAgentId = "codex",
          configuredAgentOverrideId = null,
        ),
      modelDirective = null,
      compaction = null,
      request = request,
      specSource = SpecSource.LOCAL,
      policy = AcceptanceAuditStrategy().policyFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT),
      projectStoreRoot = repoRoot.resolve("project-store"),
    )
  }

  private fun completedAuditEnvelope(prose: String): NormalizedFeatureTaskRuntimePhaseOutput =
    NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        mapOf(
          SharedPayloadKeys.STATUS to WorkflowStepStatus.COMPLETED.wireValue,
          SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to prose),
        ),
      ),
    )

  private fun auditPhaseRequest(
    f: SerialRepairSqliteFixture,
    attempt: Int,
  ) = FeatureTaskRuntimePhaseStateRequest(
    f.workflowId,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
    "running",
    attempt + 2,
    "codex",
    false,
    mutating = false,
  )

  private fun completedPhaseWrite(
    phase: FeatureTaskRuntimePhaseStateRequest,
    prose: String,
  ): FeatureTaskRuntimePhaseStateRequest {
    val normalized =
      NormalizedFeatureTaskRuntimePhaseOutput(
        phase.phaseId,
        "completed",
        "Audit conclusion",
        PhaseOutput(prose),
        verdict = "advance",
      )
    return phase.copy(
      status = "completed",
      finished = true,
      normalizedOutput = normalized,
      outputArtifact = normalized.canonicalJson,
    )
  }
}
