package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimeSimplifyPhasePromptTest {
  @Test
  fun `simplify prompt carries scoped boundary and forbidden actions`() {
    val prompt = composePromptForPhase(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY)
    assertContains(prompt, "subtask_scope")
    assertContains(prompt, "Simplify scope boundary")
    assertContains(prompt, "whole-repository")
    assertContains(prompt, "parity tests")
    listOf(
      "paths outside the boundary",
      "build or check",
      "test execution",
      "subagents",
      "delegated review",
      "spawning other agents",
    ).forEach { forbiddenAction ->
      assertContains(prompt, forbiddenAction)
    }
    assertContains(prompt, "do not run builds or tests")
    assertFalse(prompt.contains("simplification_receipt"))
  }

  @Test
  fun `simplify prompt names every protected surface and only high-confidence reductions`() {
    val prompt = composePromptForPhase(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY)

    listOf(
      "dead feature-local code",
      "one-use wrappers",
      "unnecessary one-implementation abstractions",
      "hand-rolled standard-library behavior",
      "equivalent local shrinkage",
      "governed contracts",
      "typed errors",
      "loud-fail seams",
      "parity tests",
      "validator-backed rules",
      "security measures",
      "accessibility requirements",
      "behavior the spec explicitly requires",
    ).forEach { protectedOrAllowedSurface ->
      assertContains(prompt, protectedOrAllowedSurface)
    }
  }

  @Test
  fun `forward phase order in header lists simplify between implement and audit`() {
    val prompt = composePromptForPhase(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY)
    val implementIndex = prompt.indexOf("implement")
    val simplifyIndex = prompt.indexOf("simplify")
    val auditIndex = prompt.indexOf("audit")
    assertTrue(implementIndex >= 0 && simplifyIndex > implementIndex && auditIndex > simplifyIndex)
  }
}
