package skillbill.engine.featuretask.phase.planning

import skillbill.engine.featuretask.runloop.planning.PlanDecompositionStop
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionDependency
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityAcceptedLine
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityMode
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityStateValidation
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanBundleFeasibilityTest {
  @Test
  fun `missing partial duplicate or stale approval never releases a bundle`() {
    val snapshot = snapshot()
    val receipt = approval(snapshot)
    assertNull(PlanBundleFeasibility.approvalRejection(snapshot, receipt))
    for (invalid in listOf("", "Looks feasible", receipt.lines().first(), receipt + "\n" + receipt.lines().last())) {
      assertNotNull(PlanBundleFeasibility.approvalRejection(snapshot, invalid))
    }
    val edited = snapshot.copy(specTexts = snapshot.specTexts + (1 to spec("Activate strict consumers now")))
    assertNotEquals(snapshot.fingerprint, edited.fingerprint)
    assertNotNull(PlanBundleFeasibility.approvalRejection(edited, receipt))
  }

  @Test
  fun `execution progress preserves approval but dependency edits require another review`() {
    val before = snapshot()
    val progressed =
      before.copy(
        manifest =
          before.manifest.copy(
            status = "running",
            subtasks = before.manifest.subtasks.map { it.copy(status = "complete", commitSha = "abc123") },
          ),
      )
    assertTrue(before.fingerprint == progressed.fingerprint)
    val changed =
      before.copy(
        manifest =
          before.manifest.copy(
            subtasks = before.manifest.subtasks.map { it.copy(dependencies = listOf(DecompositionDependency(9))) },
          ),
      )
    assertNotEquals(before.fingerprint, changed.fingerprint)
  }

  @Test
  fun `captured identity changes for raw parent manifest and complete child bytes`() {
    val before = snapshot()
    val childEdit =
      before.copy(
        specTexts = before.specTexts + (1 to spec("Keep readers compatible\r\n\uFEFFImplementation Details")),
      )
    val parentEdit = before.copy(parentText = "\uFEFF${before.parentText}")
    val manifestEdit = before.copy(manifestText = "${before.manifestText}\r\n")
    val whitespaceEdit = before.copy(specTexts = before.specTexts + (1 to "${before.specTexts.getValue(1)}\n"))

    assertNotEquals(before.identity.subtasks.single().specSha256, childEdit.identity.subtasks.single().specSha256)
    assertNotEquals(before.identity.subtasks.single().specSha256, whitespaceEdit.identity.subtasks.single().specSha256)
    assertNotEquals(before.identity.parentSha256, parentEdit.identity.parentSha256)
    assertNotEquals(before.identity.manifestSha256, manifestEdit.identity.manifestSha256)
    assertEquals(listOf("1.1"), before.identity.subtasks.single().criterionRefs)
  }

  @Test
  fun `typed state allows historical judgment hashes and rejects unknown refs`() {
    val snapshot = snapshot()
    val state =
      FeatureTaskRuntimePlanBundleFeasibilityState(
        revision = 0,
        mode = PlanBundleFeasibilityMode.REVIEW,
        identity = snapshot.identity,
        acceptedLines =
          listOf(
            PlanBundleFeasibilityAcceptedLine(
              "1.1",
              "criterion 1.1: accepted",
              "b".repeat(64),
              "c".repeat(64),
              "d".repeat(64),
              emptyList(),
            ),
          ),
        conflicts = emptyList(),
        changedSubtaskIds = emptyList(),
        requiredRefs = listOf("1.1"),
        carriedRefs = emptyList(),
        reviewRoundsStarted = 0,
        repairLaunchesStarted = 0,
        activeLaunch = null,
        settledLaunches = emptyList(),
        lastReviewValue = null,
        approval = null,
        legacyOrigin = false,
      )

    assertEquals(PlanBundleFeasibilityStateValidation.Valid, state.validation())
    assertEquals(
      PlanBundleFeasibilityStateValidation.Invalid("unknown criterion reference"),
      state.copy(requiredRefs = listOf("9.9")).validation(),
    )
  }

  @Test
  fun `repair preserves parent settled specs and manifest while allowing pending scope repair`() {
    val before = snapshot()
    assertNull(before.repairViolation(before.copy(specTexts = mapOf(1 to spec("Keep readers compatible")))))
    assertNotNull(before.repairViolation(before.copy(parentText = "Weakened requirement")))
    val settled =
      before.copy(
        manifest =
          before.manifest.copy(
            subtasks = before.manifest.subtasks.map { it.copy(status = "complete") },
          ),
      )
    assertNotNull(settled.repairViolation(settled.copy(specTexts = mapOf(1 to spec("Changed completed scope")))))
    assertNotNull(before.repairViolation(before.copy(manifest = before.manifest.copy(baseBranch = "other"))))
  }

  @Test
  fun `legacy completed plans require review and only current completed approval is reusable`() {
    val snapshot = snapshot()
    val record = record(approval(snapshot))
    assertTrue(
      PlanBundleFeasibility.approved(snapshot, record, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS),
    )
    assertFalse(
      PlanBundleFeasibility.approved(snapshot, null, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS),
    )
    assertFalse(
      PlanBundleFeasibility.approved(
        snapshot,
        record("Planned subtasks 1"),
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
      ),
    )
    assertFalse(
      PlanBundleFeasibility.approved(
        snapshot,
        record.copy(status = WorkflowStepStatus.RUNNING),
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
      ),
    )
    assertFalse(
      PlanBundleFeasibility.approved(
        snapshot.copy(parentText = "New parent scope"),
        record,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
      ),
    )
  }

  private fun snapshot(): PlanBundleFeasibilitySnapshot =
    PlanBundleFeasibilitySnapshot(
      DecompositionManifest(
        issueKey = "TEST-1",
        featureName = "repair",
        parentSpecPath = "specs/test/spec.md",
        baseBranch = "main",
        featureBranch = "feat/repair",
        currentSubtaskIntent = CurrentSubtaskIntent(1, "start"),
        subtasks = listOf(DecompositionSubtask(1, "Durable evidence", "specs/test/spec_subtask_1.md")),
      ),
      "Preserve running workflows",
      "Manifest review data",
      mapOf(1 to spec("Keep readers compatible")),
    )

  private fun approval(snapshot: PlanBundleFeasibilitySnapshot): String =
    "bundle-feasibility-v1: approved ${snapshot.fingerprint}\n" +
      "criterion 1.1: Compatible readers remain active until migration and evidence capture ship together."

  private fun spec(criterion: String): String = "## Acceptance Criteria\n\n1. $criterion\n"

  private fun record(value: String): FeatureTaskRuntimePhaseRecord =
    FeatureTaskRuntimePhaseRecord(
      phaseId = "plan_subtasks",
      status = WorkflowStepStatus.COMPLETED,
      attemptCount = 1,
      startedAt = Instant.EPOCH,
      resolvedAgentId = "codex",
      outputArtifact = PlanDecompositionStop.settledOutput("plan_subtasks", "Reviewed", value).canonicalJson,
    )
}
