package skillbill.engine.featuretask.phase.planning

import skillbill.engine.featuretask.runloop.planning.PlanDecompositionStop
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionDependency
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PLAN_BUNDLE_FEASIBILITY_OPERATOR_RETRY_REASON
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityAcceptedLine
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityApprovalCandidate
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityConflict
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityDependencyHash
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunch
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchKind
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchOutcome
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityMode
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityReviewScope
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityReviewScopeResult
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityStateValidation
import skillbill.workflow.taskruntime.model.planning.blockPlanBundleFeasibilityLaunch
import skillbill.workflow.taskruntime.model.planning.publishPlanBundleFeasibilityApproval
import skillbill.workflow.taskruntime.model.planning.reopenApprovedPlanBundleFeasibilityCycle
import skillbill.workflow.taskruntime.model.planning.reopenBlockedPlanBundleFeasibilityCycle
import skillbill.workflow.taskruntime.model.planning.settlePlanBundleFeasibilityRepair
import skillbill.workflow.taskruntime.model.planning.settlePlanBundleFeasibilityReview
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
  fun `review receipt accepts a complete mixed verdict and rejects malformed citations atomically`() {
    val snapshot =
      snapshot().copy(
        specTexts = mapOf(1 to "## Acceptance Criteria\n\n1. Preserve compatibility.\n2. Repair lifecycle state.\n"),
      )
    val mixed =
      "bundle-feasibility-v2: changes_requested ${snapshot.fingerprint}\n" +
        "criterion 1.1: The existing owner keeps the prior compatible behavior during migration.\n" +
        "conflict 1.2: The dependency state cannot be reached from the declared predecessor. src/plan.md:12"
    val parsed = PlanBundleFeasibility.parseReviewReceipt(snapshot, mixed)
    val receipt = assertIs<PlanBundleFeasibility.ReviewParseResult.Accepted>(parsed).receipt
    assertEquals(mixed, receipt.originalValue)
    assertEquals(
      listOf("criterion 1.1: The existing owner keeps the prior compatible behavior during migration."),
      receipt.acceptedLines,
    )
    assertEquals(
      listOf("conflict 1.2: The dependency state cannot be reached from the declared predecessor. src/plan.md:12"),
      receipt.conflictLines,
    )

    for (invalid in listOf(
      mixed.substringBefore("conflict 1.2"),
      mixed.replace("criterion 1.1", "criterion 1.2"),
      mixed.replace("1.2", "1.1"),
      mixed.replace("1.2", "9.1"),
      "bundle-feasibility-v2: changes_requested ${snapshot.fingerprint}\n" +
        "conflict 1.2: The dependency state cannot be reached from the declared predecessor. src/plan.md:12\n" +
        "criterion 1.1: The existing owner keeps the prior compatible behavior during migration.",
      mixed.replace("dependency state cannot be reached from the declared predecessor.", "short"),
      mixed.replace("src/plan.md:12", ":12"),
      mixed.replace("src/plan.md:12", "src/plan.md:0"),
      mixed.replace("changes_requested", "approved"),
      mixed.replace("bundle-feasibility-v2", "bundle-feasibility-v1"),
      mixed.replace(snapshot.fingerprint, "0".repeat(64)),
      "$mixed\nextra text",
    )) {
      assertTrue(
        PlanBundleFeasibility.parseReviewReceipt(snapshot, invalid) is PlanBundleFeasibility.ReviewParseResult.Rejected,
      )
    }
  }

  @Test
  fun `scoped receipt accepts only required refs and rejects carried extras`() {
    val snapshot =
      snapshot().copy(
        specTexts = mapOf(1 to "## Acceptance Criteria\n\n1. Preserve compatibility.\n2. Repair lifecycle state.\n"),
      )
    val required = listOf("1.2")
    val scoped =
      "bundle-feasibility-v2: approved ${snapshot.fingerprint}\n" +
        "criterion 1.2: The repair keeps the durable lifecycle record intact under a reopened planning run."
    assertIs<PlanBundleFeasibility.ReviewParseResult.Accepted>(
      PlanBundleFeasibility.parseReviewReceipt(snapshot, scoped, required),
    )

    val carriedExtra =
      scoped + "\ncriterion 1.1: The earlier successful line is carried and must not be reviewed again."
    assertIs<PlanBundleFeasibility.ReviewParseResult.Rejected>(
      PlanBundleFeasibility.parseReviewReceipt(snapshot, carriedExtra, required),
    )
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
    assertNotNull(before.repairViolation(manifestEdit))
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
    val state = approvedState(snapshot, record)
    assertTrue(
      PlanBundleFeasibility.approved(
        state,
        snapshot,
        record,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
      ),
    )
    assertFalse(
      PlanBundleFeasibility.approved(
        state,
        snapshot,
        null,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
      ),
    )
    assertFalse(
      PlanBundleFeasibility.approved(
        state,
        snapshot,
        record("Planned subtasks 1"),
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
      ),
    )
    assertFalse(
      PlanBundleFeasibility.approved(
        state,
        snapshot,
        record.copy(status = WorkflowStepStatus.RUNNING),
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
      ),
    )
    assertFalse(
      PlanBundleFeasibility.approved(
        state,
        snapshot.copy(parentText = "New parent scope"),
        record,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
      ),
    )
  }

  @Test
  fun `a reopened all-conflict review never restores successes from the approved cycle`() {
    val before = snapshot()
    val phaseRecord = record(approval(before))
    val approved = approvedState(before, phaseRecord)
    val current = before.copy(parentText = "Changed parent requirements")
    val reopened =
      assertNotNull(
        reopenApprovedPlanBundleFeasibilityCycle(approved, current.identity, "cycle-2", 1, phaseRecord),
      )
    val launch =
      PlanBundleFeasibilityLaunch(
        "review-2", "plan_subtasks", 2, PlanBundleFeasibilityLaunchKind.REVIEW, current.identity, null, null,
        cycleId = "cycle-2", sequence = 1,
      )
    val conflicts =
      listOf(
        PlanBundleFeasibilityConflict("1.1", "conflict 1.1: Changed requirements contradict the plan. src/plan.md:12"),
      )
    val rejected =
      assertNotNull(
        settlePlanBundleFeasibilityReview(
          reopened.copy(activeLaunch = launch, reviewRoundsStarted = 1),
          launch.transitionId,
          emptyList(),
          conflicts,
          "bundle-feasibility-v2: changes_requested ${current.fingerprint}\n${conflicts.single().line}",
        ),
      )

    val projection =
      assertIs<PlanBundleFeasibilityReviewScopeResult.Valid>(
        PlanBundleFeasibilityReviewScope.derive(current.identity, rejected),
      ).projection
    assertEquals(listOf("1.1"), projection.requiredRefs)
    assertEquals(emptyList(), projection.carriedLines)
    assertEquals(conflicts, projection.previousConflictLines)
    assertEquals(approved.acceptedLines, rejected.cycleHistory.single().acceptedLines)
  }

  @Test
  fun `operator retry opens a fresh review cycle from a safety-blocked launch`() {
    val snapshot = snapshot()
    val blockedRecord = record(approval(snapshot)).copy(status = WorkflowStepStatus.BLOCKED, attemptCount = 4)
    val blocked = blockedReviewState(snapshot, "Feasibility changed repository paths outside its admitted authority.")

    val reopened =
      assertNotNull(reopenBlockedPlanBundleFeasibilityCycle(blocked, "cycle-retry-7", 7, blockedRecord))

    assertNull(reopened.invalidReason())
    assertEquals(PlanBundleFeasibilityMode.REVIEW, reopened.mode)
    assertEquals(blocked.revision + 1, reopened.revision)
    assertEquals("cycle-retry-7", reopened.currentCycleId)
    assertEquals(0, reopened.reviewRoundsStarted)
    assertEquals(0, reopened.repairLaunchesStarted)
    assertEquals(snapshot.criterionRefs, reopened.requiredRefs)
    assertEquals(blocked.settledLaunches, reopened.settledLaunches)
    val history = reopened.cycleHistory.single()
    assertEquals("cycle-1", history.cycleId)
    assertEquals(PlanBundleFeasibilityLaunchOutcome.BLOCKED, history.settledLaunches.single().outcome)
    val reopening = reopened.reopenings.single()
    assertEquals(PLAN_BUNDLE_FEASIBILITY_OPERATOR_RETRY_REASON, reopening.reason)
    assertEquals(4, reopening.sourceAttempt)
    assertEquals(7, reopening.retryLedgerSequence)
    val projection =
      assertIs<PlanBundleFeasibilityReviewScopeResult.Valid>(
        PlanBundleFeasibilityReviewScope.derive(snapshot.identity, reopened),
      ).projection
    assertEquals(snapshot.criterionRefs, projection.requiredRefs)
  }

  @Test
  fun `operator retry reopen refuses unblocked or reused cycles`() {
    val snapshot = snapshot()
    val blockedRecord = record(approval(snapshot)).copy(status = WorkflowStepStatus.BLOCKED)
    val blocked = blockedReviewState(snapshot, "blocked")

    assertNull(reopenBlockedPlanBundleFeasibilityCycle(blocked, "cycle-1", 1, blockedRecord))
    assertNull(reopenBlockedPlanBundleFeasibilityCycle(blocked, " ", 1, blockedRecord))
    assertNull(reopenBlockedPlanBundleFeasibilityCycle(blocked, "cycle-2", -1, blockedRecord))
    assertNull(
      reopenBlockedPlanBundleFeasibilityCycle(
        blocked.copy(mode = PlanBundleFeasibilityMode.REVIEW),
        "cycle-2",
        1,
        blockedRecord,
      ),
    )
    val approved = approvedState(snapshot, record(approval(snapshot)))
    assertNull(reopenBlockedPlanBundleFeasibilityCycle(approved, "cycle-2", 1, blockedRecord))
  }

  @Test
  fun `reopening after criterion removal preserves readable historical evidence`() {
    val before = snapshot().copy(specTexts = mapOf(1 to spec("First requirement") + "2. Second requirement.\n"))
    val phaseRecord = record(approval(before))
    val approved = approvedState(before, phaseRecord)
    val current = snapshot()
    val reopened =
      assertNotNull(
        reopenApprovedPlanBundleFeasibilityCycle(approved, current.identity, "cycle-2", 1, phaseRecord),
      )

    assertNull(reopened.invalidReason())
    assertEquals(listOf("1.1"), reopened.acceptedLines.map { it.ref })
    assertEquals(approved.acceptedLines, reopened.cycleHistory.single().acceptedLines)
    val projection =
      assertIs<PlanBundleFeasibilityReviewScopeResult.Valid>(
        PlanBundleFeasibilityReviewScope.derive(current.identity, reopened),
      ).projection
    assertEquals(listOf("1.1"), projection.requiredRefs)
    assertEquals(emptyList(), projection.carriedLines)
  }

  @Test
  fun `repair removing a previously accepted criterion keeps the artifact readable`() {
    val before =
      snapshot().copy(
        specTexts = mapOf(1 to spec("First requirement") + "2. Second requirement.\n3. Conflicted requirement.\n"),
      )
    val approved = approvedState(before, record(approval(before)))
    val current = snapshot()
    val launch =
      PlanBundleFeasibilityLaunch(
        "repair-1", "plan_subtasks", 2, PlanBundleFeasibilityLaunchKind.REPAIR, before.identity, null, current.identity,
        cycleId = "cycle-1", sequence = 2, intendedRepairValue = "repaired",
      )
    val repairing =
      approved.copy(
        mode = PlanBundleFeasibilityMode.REPAIR,
        approval = null,
        approvalCandidate = null,
        acceptedLines = approved.acceptedLines.take(2),
        conflicts = listOf(PlanBundleFeasibilityConflict("1.3", "conflict 1.3: Incompatible planning. src/plan.md:12")),
        activeLaunch = launch,
        repairLaunchesStarted = 1,
      )
    val repaired =
      assertNotNull(
        settlePlanBundleFeasibilityRepair(repairing, launch.transitionId, current.identity, "repaired"),
      )

    assertNull(repaired.invalidReason())
    assertEquals(listOf("1.1"), repaired.acceptedLines.map { it.ref })
    assertEquals(approved.settledLaunches.single(), repaired.settledLaunches.first())
    val projection =
      assertIs<PlanBundleFeasibilityReviewScopeResult.Valid>(
        PlanBundleFeasibilityReviewScope.derive(current.identity, repaired),
      ).projection
    assertEquals(listOf("1.1"), projection.requiredRefs)
    assertEquals(emptyList(), projection.carriedLines)
  }

  @Test
  fun `pending direct-dependency approval remains publishable after upgrade`() {
    val before = snapshot()
    val chain =
      before.copy(
        manifest =
          before.manifest.copy(
            subtasks =
              listOf(
                DecompositionSubtask(1, "First", "specs/test/spec_subtask_1.md"),
                DecompositionSubtask(
                  2,
                  "Second",
                  "specs/test/spec_subtask_2.md",
                  dependencies = listOf(DecompositionDependency(1)),
                ),
                DecompositionSubtask(
                  3,
                  "Third",
                  "specs/test/spec_subtask_3.md",
                  dependencies = listOf(DecompositionDependency(2)),
                ),
              ),
          ),
        specTexts = mapOf(1 to spec("First"), 2 to spec("Second"), 3 to spec("Third")),
      )
    val phaseRecord = record(approval(chain))
    val approved = approvedState(chain, phaseRecord)

    assertEquals(listOf(2), approved.acceptedLines.last().dependencyHashes.map { it.subtaskId })
    assertTrue(PlanBundleFeasibility.approved(approved, chain, phaseRecord, "plan_subtasks"))
  }

  private fun approvedState(
    snapshot: PlanBundleFeasibilitySnapshot,
    record: FeatureTaskRuntimePhaseRecord,
  ): FeatureTaskRuntimePlanBundleFeasibilityState {
    val launch =
      PlanBundleFeasibilityLaunch(
        transitionId = "review-1",
        phaseId = record.phaseId,
        attempt = record.attemptCount,
        kind = PlanBundleFeasibilityLaunchKind.REVIEW,
        baselineIdentity = snapshot.identity,
        repairDirectory = null,
        intendedAfterIdentity = null,
        cycleId = "cycle-1",
        sequence = 1,
      )
    val initial =
      FeatureTaskRuntimePlanBundleFeasibilityState(
        revision = 1,
        mode = PlanBundleFeasibilityMode.REVIEW,
        identity = snapshot.identity,
        acceptedLines = emptyList(),
        conflicts = emptyList(),
        changedSubtaskIds = emptyList(),
        requiredRefs = snapshot.criterionRefs,
        carriedRefs = emptyList(),
        reviewRoundsStarted = 1,
        repairLaunchesStarted = 0,
        activeLaunch = launch,
        settledLaunches = emptyList(),
        lastReviewValue = null,
        approval = null,
        legacyOrigin = false,
        currentCycleId = launch.cycleId,
      )
    val receipt =
      assertIs<PlanBundleFeasibility.ReviewParseResult.Accepted>(
        PlanBundleFeasibility.parseReviewReceipt(snapshot, approval(snapshot)),
      ).receipt
    val accepted = approvalAcceptedLines(snapshot, receipt)
    val candidate =
      PlanBundleFeasibilityApprovalCandidate(
        sourceRevision = 2,
        immutableFingerprint = snapshot.fingerprint,
        transitionId = launch.transitionId,
        phaseId = record.phaseId,
        attempt = record.attemptCount,
        reviewerAgentId = record.resolvedAgentId,
        normalizedEnvelope = assertNotNull(record.outputArtifact),
        fileManifestBefore = record.fileManifestBefore,
        fileManifestAfter = record.fileManifestAfter,
        fileManifestIntroduced = record.fileManifestIntroduced,
      )
    val settled =
      assertNotNull(
        settlePlanBundleFeasibilityReview(
          initial,
          launch.transitionId,
          accepted,
          emptyList(),
          receipt.originalValue,
        )?.copy(approvalCandidate = candidate),
      )
    return assertNotNull(publishPlanBundleFeasibilityApproval(settled, record.phaseId, record.attemptCount, 2))
  }

  private fun blockedReviewState(
    snapshot: PlanBundleFeasibilitySnapshot,
    reason: String,
  ): FeatureTaskRuntimePlanBundleFeasibilityState {
    val launch =
      PlanBundleFeasibilityLaunch(
        transitionId = "review-1",
        phaseId = "plan_subtasks",
        attempt = 1,
        kind = PlanBundleFeasibilityLaunchKind.REVIEW,
        baselineIdentity = snapshot.identity,
        repairDirectory = null,
        intendedAfterIdentity = null,
        cycleId = "cycle-1",
        sequence = 1,
      )
    val active =
      FeatureTaskRuntimePlanBundleFeasibilityState(
        revision = 1,
        mode = PlanBundleFeasibilityMode.REVIEW,
        identity = snapshot.identity,
        acceptedLines = emptyList(),
        conflicts = emptyList(),
        changedSubtaskIds = emptyList(),
        requiredRefs = snapshot.criterionRefs,
        carriedRefs = emptyList(),
        reviewRoundsStarted = 1,
        repairLaunchesStarted = 0,
        activeLaunch = launch,
        settledLaunches = emptyList(),
        lastReviewValue = null,
        approval = null,
        legacyOrigin = false,
        currentCycleId = launch.cycleId,
      )
    return assertNotNull(blockPlanBundleFeasibilityLaunch(active, launch.transitionId, reason))
  }

  private fun approvalAcceptedLines(
    snapshot: PlanBundleFeasibilitySnapshot,
    receipt: PlanBundleFeasibility.ReviewReceipt,
  ): List<PlanBundleFeasibilityAcceptedLine> {
    val byId = snapshot.identity.subtasks.associateBy { it.id }
    return receipt.lines.map { line ->
      val subtask = snapshot.identity.subtasks.first { line.ref in it.criterionRefs }
      PlanBundleFeasibilityAcceptedLine(
        line.ref,
        line.originalLine,
        snapshot.identity.parentSha256,
        snapshot.identity.manifestSha256,
        subtask.specSha256,
        subtask.dependencies.map { dependency ->
          PlanBundleFeasibilityDependencyHash(dependency.subtaskId, byId.getValue(dependency.subtaskId).specSha256)
        },
      )
    }
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
    "bundle-feasibility-v2: approved ${snapshot.fingerprint}\n" +
      snapshot.criterionRefs.joinToString("\n") { ref ->
        "criterion $ref: Compatible readers remain active until migration and evidence capture ship together."
      }

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
