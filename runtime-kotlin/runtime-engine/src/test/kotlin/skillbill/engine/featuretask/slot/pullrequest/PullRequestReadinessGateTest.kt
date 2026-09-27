package skillbill.engine.featuretask.slot.pullrequest

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeReadinessEvidencePort
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeReadinessEvidence
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PullRequestReadinessGateTest {
  private val gitOperations = RecordingWorkflowGitOperations()
  private val identity = gitOperations.readinessTreeIdentity

  @Test
  fun `pr entry is ready when persisted evidence matches the current tree identity`() {
    val evidence = evidence(identity.sourceTreeSha, identity.baseRefSha, identity.headSha)

    assertNull(blockedReason(StoredEvidence { evidence }))
  }

  @Test
  fun `pr entry blocks when readiness evidence is missing`() {
    assertEquals("Readiness evidence is missing for PR entry.", blockedReason(StoredEvidence { null }))
  }

  @Test
  fun `pr entry blocks when readiness evidence cannot be loaded`() {
    val reason = blockedReason(StoredEvidence { error("database unavailable") })

    assertEquals("Readiness evidence could not be loaded for PR entry.", reason)
  }

  @Test
  fun `pr entry blocks when the base ref drifted since readiness was captured`() {
    val evidence = evidence(identity.sourceTreeSha, "d".repeat(40), identity.headSha)

    val reason = assertNotNull(blockedReason(StoredEvidence { evidence }))

    assertTrue(reason.contains("base_ref_sha mismatch"), reason)
  }

  private fun blockedReason(store: FeatureTaskRuntimeReadinessEvidencePort): String? =
    PullRequestReadinessGate(store, NoopRuntimeDiagnostics).blockedReason(
      workflowId = WORKFLOW_ID,
      repoRoot = Path.of("."),
      baseBranch = "main",
      gitOperations = gitOperations,
    )

  private fun evidence(
    sourceTreeSha: String,
    baseRefSha: String,
    headSha: String,
  ) = FeatureTaskRuntimeReadinessEvidence(sourceTreeSha, baseRefSha, headSha, emptyList(), emptyList())

  private class StoredEvidence(
    private val load: () -> FeatureTaskRuntimeReadinessEvidence?,
  ) : FeatureTaskRuntimeReadinessEvidencePort {
    override fun loadReadinessEvidence(workflowId: String): FeatureTaskRuntimeReadinessEvidence? = load()

    override fun persistReadinessEvidence(
      workflowId: String,
      evidence: FeatureTaskRuntimeReadinessEvidence,
    ) = error("The pr readiness gate must not persist evidence.")
  }

  private companion object {
    const val WORKFLOW_ID = "wf-pr-readiness"
  }
}
