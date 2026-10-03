package skillbill.workflow.taskruntime.phase.task

import skillbill.error.featuretask.InvalidSkeletonDefinitionError
import skillbill.error.featuretask.UnknownSkeletonDefinitionError
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdgeCapScope
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseEntryGate
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.SkeletonRunStateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SkeletonDefinitionTest {
  private val goalChildForward =
    listOf(
      "preplan",
      "plan",
      "implement",
      "simplify",
      "audit_plan_fix",
      "audit_implement_fix",
      "audit",
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
      loopOnlyPhaseIds = setOf("audit_plan_fix", "audit_implement_fix", "implement_fix", "build"),
      loopOnlySuccessors = mapOf("audit_plan_fix" to "audit_implement_fix"),
    )

  @Test
  fun `standalone derives today's transition declaration`() {
    assertEquals(todaysDeclaration(goalChildForward + "pr"), SkeletonDefinition.STANDALONE.declaration())
  }

  @Test
  fun `goal-child derives today's declaration without the pull request step`() {
    assertEquals(todaysDeclaration(goalChildForward), SkeletonDefinition.GOAL_CHILD.declaration())
  }

  @Test
  fun `a run resolves goal-child only with a goal continuation`() {
    assertEquals(SkeletonDefinition.GOAL_CHILD, SkeletonDefinition.forRun(goalContinuation = true))
    assertEquals(SkeletonDefinition.STANDALONE, SkeletonDefinition.forRun(goalContinuation = false))
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
  fun `review keeps the capped review_fix loop and no gate from implementation or audit`() {
    val declaration = SkeletonDefinition.REVIEW.declaration()

    assertEquals(listOf("review", "verify_findings", "implement_fix"), declaration.forwardPhaseIds)
    assertEquals(
      todaysDeclaration(goalChildForward).backwardEdges.filter {
        it.loopId == "review_fix"
      },
      declaration.backwardEdges,
    )
    assertEquals(
      listOf(
        FeatureTaskRuntimePhaseEntryGate(
          "implement_fix",
          "verify_findings",
          FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
        ),
      ),
      declaration.entryGates,
    )
    assertEquals(SkeletonRunStateKind.IN_MEMORY, SkeletonDefinition.REVIEW.runStateKind)
    assertEquals(SkeletonRunStateKind.DURABLE, SkeletonDefinition.STANDALONE.runStateKind)
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
  fun `goal-planning is the preplan and plan slots over the goal-planning state`() {
    val definition = SkeletonDefinition.byId("goal-planning")

    assertEquals(listOf("preplan", "plan"), definition.declaration().forwardPhaseIds)
    assertEquals(SkeletonRunStateKind.GOAL_PLANNING, definition.runStateKind)
  }

  @Test
  fun `an entry step selected for traversal is no longer loop-only`() {
    val selected =
      SkeletonDefinition.GOAL_CHILD.stepIds.toSet() - FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
    val traversal =
      SkeletonDefinition.GOAL_CHILD.traversal(
        selected,
        entryStepIds = setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD),
      )

    assertEquals(
      setOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
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
