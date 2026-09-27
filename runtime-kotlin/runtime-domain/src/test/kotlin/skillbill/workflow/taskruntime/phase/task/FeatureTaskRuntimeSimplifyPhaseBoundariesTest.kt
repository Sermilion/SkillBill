package skillbill.workflow.taskruntime.phase.task

import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.validation.shippedTransition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeSimplifyPhaseBoundariesTest {
  private val def = FeatureTaskRuntimePhaseWorkflowDefinition
  private val shipped = def.transitions

  @Test
  fun `implement advances to simplify before audit on the shipped graph`() {
    val next =
      assertIs<FeatureTaskRuntimeNextPhase.Next>(
        shippedTransition(
          shipped,
          def.PHASE_IMPLEMENT,
          FeatureTaskRuntimeVerdict.ADVANCE,
          settledVerdicts = emptyMap(),
        ),
      )
    assertEquals(def.PHASE_SIMPLIFY, next.phaseId)
  }

  @Test
  fun `review entry gate still requires satisfied audit after simplify is in the forward path`() {
    val auditIndex = shipped.forwardPhaseIds.indexOf(def.PHASE_AUDIT)
    val simplifyIndex = shipped.forwardPhaseIds.indexOf(def.PHASE_SIMPLIFY)
    val reviewIndex = shipped.forwardPhaseIds.indexOf(def.PHASE_REVIEW)
    assertTrue(simplifyIndex > shipped.forwardPhaseIds.indexOf(def.PHASE_IMPLEMENT))
    assertTrue(auditIndex > simplifyIndex)
    assertTrue(reviewIndex > auditIndex)
    val reviewGate = shipped.entryGates.single { it.phaseId == def.PHASE_REVIEW }
    assertEquals(def.PHASE_AUDIT, reviewGate.requiredPhaseId)
    assertEquals(FeatureTaskRuntimeVerdict.SATISFIED, reviewGate.requiredVerdict)
  }

  @Test
  fun `simplify requires the implement artifact and resumes without replaying completed edits`() {
    assertEquals(
      listOf(def.PHASE_IMPLEMENT),
      def.definition.requiredArtifactsByStep.getValue(def.PHASE_SIMPLIFY),
    )
    assertContains(def.definition.resumeActions.getValue(def.PHASE_SIMPLIFY), "without replaying completed edits")
  }

  @Test
  fun `simplify handoff carries only runtime scoped paths and the current diff context`() {
    val declaration = def.phaseDeclarations.getValue(def.PHASE_SIMPLIFY)
    val projection = declaration.projectionDeclarations.single()

    assertEquals("subtask_scope", projection.projectionName)
    assertEquals(
      listOf("changed_paths", "repository_checkpoint"),
      projection.declaredFieldNames,
    )
    assertEquals(
      FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      projection.checkpointPolicy,
    )
    assertEquals(listOf(def.DERIVED_CONTEXT_DIFF), declaration.derivedContextKeys)
  }
}
