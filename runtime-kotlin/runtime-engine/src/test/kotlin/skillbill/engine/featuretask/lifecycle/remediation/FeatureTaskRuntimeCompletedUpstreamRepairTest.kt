package skillbill.engine.featuretask.lifecycle.remediation

import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeatureTaskRuntimeCompletedUpstreamRepairTest {
  @Test
  fun `diagnose returns verify_findings when implement_fix is blocked on missing settled output`() {
    val phaseRecords =
      mapOf(
        "verify_findings" to
          phaseRecord(
            phaseId = "verify_findings",
            status = "completed",
            outputArtifact = null,
          ),
        "implement_fix" to
          phaseRecord(
            phaseId = "implement_fix",
            status = "blocked",
            blockedReason = "missing verify_findings output",
          ),
      )

    assertEquals(
      "verify_findings",
      diagnoseUnsettledCompletedUpstreamPhaseId(
        phaseRecords,
        FeatureTaskRuntimeFeatureSize.MEDIUM,
      ),
    )
  }

  @Test
  fun `diagnose is null when completed upstream has settled output`() {
    val phaseRecords =
      mapOf(
        "verify_findings" to
          phaseRecord(
            phaseId = "verify_findings",
            status = "completed",
            outputArtifact =
              """{"contract_version":"0.2","verdict":"no_findings_verified","finding_dispositions":[]}""",
          ),
        "implement_fix" to
          phaseRecord(
            phaseId = "implement_fix",
            status = "blocked",
            blockedReason = "other",
          ),
      )

    assertNull(
      diagnoseUnsettledCompletedUpstreamPhaseId(
        phaseRecords,
        FeatureTaskRuntimeFeatureSize.MEDIUM,
      ),
    )
  }

  @Test
  fun `diagnose returns build when build-stamped write_history is blocked on missing build output`() {
    val phaseRecords =
      mapOf(
        "review" to
          phaseRecord(
            phaseId = "review",
            status = "completed",
            outputArtifact = """{"contract_version":"0.1"}""",
          ),
        "build" to
          phaseRecord(
            phaseId = "build",
            status = "completed",
            outputArtifact = null,
          ),
        "write_history" to
          phaseRecord(
            phaseId = "write_history",
            status = "blocked",
            blockedReason = "Phase 'write_history' requires upstream output(s) build that are not present",
          ),
      )

    assertEquals(
      "build",
      diagnoseUnsettledCompletedUpstreamPhaseId(
        phaseRecords,
        FeatureTaskRuntimeFeatureSize.MEDIUM,
        FeatureTaskRuntimeQualityGateSelection.BUILD,
      ),
    )
  }

  @Test
  fun `diagnose does not return validate when build-stamped child lacks settled build output`() {
    val phaseRecords =
      mapOf(
        "review" to
          phaseRecord(
            phaseId = "review",
            status = "completed",
            outputArtifact = """{"contract_version":"0.1"}""",
          ),
        "build" to
          phaseRecord(
            phaseId = "build",
            status = "completed",
            outputArtifact = null,
          ),
        "validate" to
          phaseRecord(
            phaseId = "validate",
            status = "completed",
            outputArtifact = """{"contract_version":"0.1"}""",
          ),
        "write_history" to
          phaseRecord(
            phaseId = "write_history",
            status = "blocked",
            blockedReason = "Phase 'write_history' requires upstream output(s) build that are not present",
          ),
      )

    assertEquals(
      "build",
      diagnoseUnsettledCompletedUpstreamPhaseId(
        phaseRecords,
        FeatureTaskRuntimeFeatureSize.MEDIUM,
        FeatureTaskRuntimeQualityGateSelection.BUILD,
      ),
    )
  }

  @Test
  fun `diagnose returns blocked consumer when upstream block reason is stale`() {
    val phaseRecords =
      mapOf(
        "verify_findings" to
          phaseRecord(
            phaseId = "verify_findings",
            status = "completed",
            outputArtifact = """{"contract_version":"0.2","verdict":"findings_verified","finding_dispositions":[]}""",
          ),
        "implement_fix" to
          phaseRecord(
            phaseId = "implement_fix",
            status = "blocked",
            blockedReason = "Phase 'implement_fix' requires upstream output(s) verify_findings that are not present",
          ),
      )

    assertEquals(
      "implement_fix",
      diagnoseUnsettledCompletedUpstreamPhaseId(phaseRecords, FeatureTaskRuntimeFeatureSize.MEDIUM),
    )
  }

  private fun phaseRecord(
    phaseId: String,
    status: String,
    outputArtifact: String? = null,
    blockedReason: String? = null,
  ): FeatureTaskRuntimePhaseRecord =
    FeatureTaskRuntimePhaseRecord(
      phaseId = phaseId,
      status = status,
      attemptCount = 1,
      startedAt = "2026-01-01T00:00:00Z",
      firstStartedAt = "2026-01-01T00:00:00Z",
      resolvedAgentId = "claude",
      outputArtifact = outputArtifact,
      blockedReason = blockedReason,
    )
}
