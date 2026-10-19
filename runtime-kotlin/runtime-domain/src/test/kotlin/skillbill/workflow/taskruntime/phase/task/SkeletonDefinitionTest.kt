package skillbill.workflow.taskruntime.phase.task

import skillbill.error.featuretask.InvalidSkeletonDefinitionError
import skillbill.error.featuretask.UnknownSkeletonDefinitionError
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdgeCapScope
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCrossSlotAllowance
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseEntryGate
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.SkeletonRunStateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkeletonDefinitionTest {
  private val goalChildForward =
    listOf(
      "preplan",
      "plan",
      "plan_subtasks",
      "implement",
      "simplify",
      "audit_plan_fix",
      "audit_implement_fix",
      "audit",
      "qa",
      "qa_fix",
      "review",
      "verify_findings",
      "implement_fix",
      "build",
      "validate",
      "write_history",
      "commit_push",
    )

  private fun todaysDeclaration(forwardPhaseIds: List<String>): FeatureTaskRuntimeTransitionDeclaration =
    FeatureTaskRuntimeTransitionDeclaration(
      forwardPhaseIds = forwardPhaseIds,
      entryGates =
        listOf(
          FeatureTaskRuntimePhaseEntryGate("review", "audit", FeatureTaskRuntimeVerdict.SATISFIED),
          FeatureTaskRuntimePhaseEntryGate("qa", "audit", FeatureTaskRuntimeVerdict.SATISFIED),
          FeatureTaskRuntimePhaseEntryGate(
            "implement_fix",
            "verify_findings",
            FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
          ),
        ),
      backwardEdges =
        listOf(
          FeatureTaskRuntimeBackwardEdge(
            "audit",
            FeatureTaskRuntimeVerdict.ADVANCE,
            "audit_plan_fix",
            "audit_repair",
            perEdgeCap = null,
            warnAfterIterations = 3,
          ),
          FeatureTaskRuntimeBackwardEdge(
            fromPhaseId = "verify_findings",
            triggeringVerdict = FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
            destinationPhaseId = "implement_fix",
            loopId = "review_fix",
            perEdgeCap = 1,
            capExhaustionBehavior = FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE,
            capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
          ),
        ) +
          qaEdges +
          listOf("build" to "regenerate_build_receipt", "validate" to "regenerate_validation_receipt").map {
              (step, loop) ->
            FeatureTaskRuntimeBackwardEdge(
              fromPhaseId = "write_history",
              triggeringVerdict = FeatureTaskRuntimeVerdict.RECORD_REJECTED,
              destinationPhaseId = step,
              loopId = loop,
              perEdgeCap = 2,
              capExhaustionBehavior = FeatureTaskRuntimeCapExhaustionBehavior.BLOCK,
              capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
            )
          },
      loopOnlyPhaseIds = setOf("audit_plan_fix", "audit_implement_fix", "implement_fix", "build", "qa_fix"),
      loopOnlySuccessors = mapOf("audit_plan_fix" to "audit_implement_fix"),
    )

  private val qaEdges =
    listOf(
      FeatureTaskRuntimeBackwardEdge(
        "qa",
        FeatureTaskRuntimeVerdict.QA_FINDINGS,
        "qa_fix",
        "qa_fix",
        3,
        FeatureTaskRuntimeCapExhaustionBehavior.BLOCK,
        FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
      ),
      FeatureTaskRuntimeBackwardEdge(
        "qa_fix",
        FeatureTaskRuntimeVerdict.ADVANCE,
        "audit",
        "qa_fix_return",
        null,
        crossSlotAllowance = FeatureTaskRuntimeCrossSlotAllowance.DECLARED,
      ),
      FeatureTaskRuntimeBackwardEdge(
        "write_history",
        FeatureTaskRuntimeVerdict.QA_INVALIDATED,
        "audit",
        "qa_invalidation",
        3,
        FeatureTaskRuntimeCapExhaustionBehavior.BLOCK,
        FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
        crossSlotAllowance = FeatureTaskRuntimeCrossSlotAllowance.DECLARED,
      ),
    )

  @Test
  fun `standalone derives today's transition declaration plus the monitor fix loop`() {
    val expected = todaysDeclaration(goalChildForward + listOf("pr", "monitor", "monitor_fix"))
    val monitorLoop =
      listOf(
        FeatureTaskRuntimeBackwardEdge(
          fromPhaseId = "monitor",
          triggeringVerdict = FeatureTaskRuntimeVerdict.CI_FAILED,
          destinationPhaseId = "monitor_fix",
          loopId = "monitor_fix",
          perEdgeCap = 2,
          capExhaustionBehavior = FeatureTaskRuntimeCapExhaustionBehavior.BLOCK,
          capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
        ),
        FeatureTaskRuntimeBackwardEdge(
          fromPhaseId = "monitor",
          triggeringVerdict = FeatureTaskRuntimeVerdict.PROGRESS,
          destinationPhaseId = "monitor_fix",
          loopId = "monitor_fix_trigger",
          perEdgeCap = null,
          capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
        ),
        FeatureTaskRuntimeBackwardEdge(
          fromPhaseId = "monitor_fix",
          triggeringVerdict = FeatureTaskRuntimeVerdict.ADVANCE,
          destinationPhaseId = "monitor",
          loopId = "monitor_fix_commit",
          perEdgeCap = null,
          capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
        ),
      )

    assertEquals(
      expected.copy(
        backwardEdges = expected.backwardEdges.take(2) + monitorLoop + expected.backwardEdges.drop(2),
        loopOnlyPhaseIds = expected.loopOnlyPhaseIds + "monitor_fix",
      ),
      SkeletonDefinition.STANDALONE.declaration(),
    )
  }

  @Test
  fun `goal-child derives today's declaration without the pull request step`() {
    assertEquals(todaysDeclaration(goalChildForward), SkeletonDefinition.GOAL_CHILD_REVISION_3.declaration())
  }

  @Test
  fun `goal QA composes its own repair and revalidation loops only`() {
    val loops = setOf("qa_fix_revalidate", "qa_requalify")
    val goalQa = SkeletonDefinition.GOAL_QA

    assertEquals(listOf("qa", "qa_fix", "validate"), goalQa.stepIds)
    assertEquals(setOf("qa_fix", "validate"), goalQa.declaration().loopOnlyPhaseIds)
    assertTrue(loops.all { loop -> goalQa.declaration().backwardEdges.any { it.loopId == loop } })
    assertFalse(
      SkeletonDefinition.entries.filter { it != goalQa }.any { definition ->
        definition.declaration().backwardEdges.any { it.loopId in loops }
      },
    )
  }

  @Test
  fun `recorded goal-child revisions keep their composition while new lookup selects revision four without QA`() {
    val current = SkeletonDefinition.byId("goal-child")
    assertEquals(SkeletonDefinition.GOAL_CHILD, current)
    assertEquals(4, current.semanticRevision)
    assertTrue(current.stepIds.none { it == "qa" || it == "qa_fix" })
    assertEquals(3, SkeletonDefinition.GOAL_CHILD_REVISION_3.semanticRevision)
    assertTrue(PhaseSlot.QA in SkeletonDefinition.GOAL_CHILD_REVISION_3.slots)
    assertFalse(PhaseSlot.QA in SkeletonDefinition.GOAL_CHILD_REVISION_2.slots)
    assertEquals(2, SkeletonDefinition.GOAL_CHILD_REVISION_2.semanticRevision)
    for (preQa in listOf(SkeletonDefinition.GOAL_CHILD_REVISION_2, SkeletonDefinition.STANDALONE_REVISION_2)) {
      assertTrue(preQa.declaration().backwardEdges.none { it.loopId == "qa_invalidation" })
    }
  }

  @Test
  fun `monitor closes the standalone and pr skeletons and goal-child excludes it`() {
    assertEquals(PhaseSlot.MONITOR, SkeletonDefinition.STANDALONE.slots.last())
    assertEquals(
      listOf("commit_push", "pr", "monitor", "monitor_fix"),
      SkeletonDefinition.PR.stepIds,
    )
    assertEquals(listOf("commit_push", "monitor", "monitor_fix"), SkeletonDefinition.MONITOR.stepIds)
    assertFalse(PhaseSlot.MONITOR in SkeletonDefinition.GOAL_CHILD.slots)
  }

  @Test
  fun `the internal standalone monitor mirrors the monitor traversal but is never publicly invocable`() {
    val standalone = SkeletonDefinition.STANDALONE_MONITOR

    assertEquals("standalone-monitor", standalone.id)
    assertEquals(SkeletonDefinition.MONITOR.stepIds, standalone.stepIds)
    assertEquals(SkeletonDefinition.MONITOR.declaration(), standalone.declaration())
    assertEquals(SkeletonRunStateKind.IN_MEMORY, standalone.runStateKind)
    assertFalse(standalone.standaloneInvocable)
    assertEquals(standalone, SkeletonDefinition.byId("standalone-monitor"))
    assertEquals(standalone, SkeletonDefinition.preparedExecutionFor(SkeletonDefinition.MONITOR.id))
    assertEquals(null, SkeletonDefinition.preparedExecutionFor(SkeletonDefinition.PR.id))
  }

  @Test
  fun `a definition that reorders slots raises a typed error`() {
    val error =
      assertFailsWith<InvalidSkeletonDefinitionError> {
        SkeletonDefinition("reordered", listOf(PhaseSlot.PLAN, PhaseSlot.PREPLAN))
      }

    assertEquals(listOf("plan", "preplan"), error.slots)
  }

  @Test
  fun `a definition that repeats a slot raises a typed error`() {
    assertFailsWith<InvalidSkeletonDefinitionError> {
      SkeletonDefinition("repeated", listOf(PhaseSlot.PLAN, PhaseSlot.PLAN))
    }
  }

  @Test
  fun `standalone review has one report step and no repair loop`() {
    val declaration = SkeletonDefinition.REVIEW.declaration()

    assertEquals(listOf("present_findings"), declaration.forwardPhaseIds)
    assertEquals(emptyList(), declaration.backwardEdges)
    assertEquals(emptyList(), declaration.entryGates)
    assertEquals(SkeletonRunStateKind.IN_MEMORY, SkeletonDefinition.REVIEW.runStateKind)
    assertEquals(SkeletonRunStateKind.DURABLE, SkeletonDefinition.STANDALONE.runStateKind)
  }

  @Test
  fun `standalone plan is durable, invocable on its own, and admitted only as a standalone run`() {
    assertEquals(SkeletonRunStateKind.DURABLE, SkeletonDefinition.PLAN.runStateKind)
    assertEquals(listOf("intake", "preplan", "plan", "plan_subtasks"), SkeletonDefinition.PLAN.stepIds)
    assertEquals(
      listOf("review", "validation", "qa", "plan", "pr", "monitor"),
      SkeletonDefinition.entries.filter(SkeletonDefinition::standaloneInvocable).map(SkeletonDefinition::id),
    )
    assertEquals(setOf(SkeletonDefinition.PLAN), SkeletonDefinition.admittedForRun(goalContinuation = false))
    assertEquals(setOf(SkeletonDefinition.GOAL_CHILD), SkeletonDefinition.admittedForRun(goalContinuation = true))
  }

  @Test
  fun `the retired standalone skeleton is decodable but never admitted for a run`() {
    assertEquals(SkeletonDefinition.STANDALONE, SkeletonDefinition.byId("standalone"))
    assertFalse(SkeletonDefinition.STANDALONE.standaloneInvocable)
    listOf(true, false).forEach { goalContinuation ->
      assertFalse(SkeletonDefinition.STANDALONE in SkeletonDefinition.admittedForRun(goalContinuation))
    }
  }

  @Test
  fun `lookup knows the short definitions and rejects commit_push and unknown ids`() {
    assertEquals(SkeletonDefinition.VALIDATION, SkeletonDefinition.byId("validation"))
    listOf("commit_push", "bogus").forEach { id ->
      val error = assertFailsWith<UnknownSkeletonDefinitionError> { SkeletonDefinition.byId(id) }
      assertEquals(id, error.definitionId)
    }
  }

  @Test
  fun `planning is one plan skeleton and no goal-planning definition remains`() {
    assertFailsWith<UnknownSkeletonDefinitionError> { SkeletonDefinition.byId("goal-planning") }
    assertEquals(listOf(PhaseSlot.INTAKE, PhaseSlot.PREPLAN, PhaseSlot.PLAN), SkeletonDefinition.PLAN.slots)
    assertEquals(listOf("plan", "plan_subtasks"), PhaseSlot.PLAN.steps)
  }

  @Test
  fun `an entry step selected for traversal is no longer loop-only`() {
    val goalChild = SkeletonDefinition.GOAL_CHILD_REVISION_3
    val selected = goalChild.stepIds.toSet() - FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
    val traversal =
      goalChild.traversal(
        selected,
        entryStepIds = setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD),
      )

    assertEquals(
      setOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX,
      ),
      traversal.loopOnlyPhaseIds,
    )
    assertEquals(
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
      ),
      traversal.forwardPhaseIds.dropWhile { it != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX }
        .take(3),
    )
  }
}
