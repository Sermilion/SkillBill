package skillbill.engine.featuretask.slot.qualitygate

import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairBinding
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairHydration
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.promptComposerBriefingFor
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.review.spec.model.InteractionScenario
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class ValidateProbeActivationTest {
  @Test
  fun `mixed authored and generated repairs reach validation with joint pair probe coverage`() {
    val request = request(listOf("IS-004", "AC-003+AC-004"))
    val original = request.acceptanceCriterionRepair?.snapshot
    val scope = assertNotNull(ValidateProbeActivation.scopeFor(request, validationGateTriage = false))

    assertEquals(listOf(scenario), scope.repairedScenarios)
    val pair = scope.repairedPairs.single()
    assertEquals("AC-003+AC-004", pair.pairRef)
    assertEquals(listOf(SHARED_PATH), pair.sharedPaths)
    assertEquals(listOf("AC-001", "AC-003", "AC-004"), scope.requiredCriteria.map { it.criterionRef })

    val sections =
      AgentValidateStrategy().promptSections(
        PHASE_VALIDATE,
        FeatureTaskRuntimePhasePromptComposeInputs(
          issueKey = request.issueKey,
          briefing = promptComposerBriefingFor(PHASE_VALIDATE),
          validateProbeScopeSource = { scope },
        ),
      )

    assertTrue("IS-004 (entry point Needs you; outcome focus announces the selected run)" in sections.taskDirective)
    assertTrue("AC-003+AC-004 (shared production files $SHARED_PATH)" in sections.taskDirective)
    assertTrue("one probed test that exercises both criteria together" in sections.taskDirective)
    listOf(1, 3, 4).forEach { ordinal ->
      assertTrue("AC-00$ordinal: ${criterionText(ordinal)}" in sections.taskDirective)
    }
    assertEquals(original, request.acceptanceCriterionRepair?.snapshot)
  }

  @Test
  fun `unknown scenarios and pairs without shared production evidence still fail validation preparation`() {
    listOf("IS-999", "AC-003+AC-999", "AC-001+AC-003", "AC-003+AC-003").forEach { unknown ->
      val request = request(listOf("IS-004", "AC-003+AC-004", unknown))
      val original = request.acceptanceCriterionRepair?.snapshot

      val failure =
        assertFailsWith<SkillBillRuntimeException>(unknown) {
          ValidateProbeActivation.scopeFor(request, validationGateTriage = false)
        }

      assertEquals(FeatureTaskRuntimeFailureCode.INVALID_INTERACTION_SCENARIO_CATALOG, failure.code)
      assertTrue(unknown in failure.message.orEmpty())
      assertEquals(original, request.acceptanceCriterionRepair?.snapshot)
    }
  }

  private fun request(repairedRefs: List<String>): FeatureTaskRuntimeRunRequest {
    val criteria = (1..4).map(::criterion)
    val authored = criteria.map { it.originalText }
    val admitted =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission("catalog", "wf-probe", DIGEST, authored, criteria, emptyList()),
        ),
      )
    val wire = admitted.asRepairWireMap()
    val trace =
      FeatureTaskRuntimeWorkflowArtifactMap.from(wire[Keys.INTERACTION_TRACE]) +
        mapOf(
          Keys.TRACED_SCENARIO_REFS to repairedRefs,
          Keys.REPAIRED_SCENARIO_REFS to repairedRefs,
          Keys.TRACED_SPEC_DIGEST to DIGEST,
        )
    val state =
      FeatureTaskRuntimeWorkflowArtifactMap
        .from(wire + (Keys.INTERACTION_TRACE to trace))
        .toAcceptanceCriterionRepairState("probe fixture")
    return FeatureTaskRuntimeRunRequest(
      issueKey = "SKILL-PROBE",
      workflowId = "wf-probe",
      sessionId = "probe-session",
      runInvariants =
        FeatureTaskRuntimeRunInvariants(
          specReference = "spec.md",
          acceptanceCriteria = authored,
          mandatesAndOverrides = emptyList(),
        ),
      invokedAgentId = "codex",
      repoRoot = Path.of("."),
      skeletonDefinition = SkeletonDefinition.GOAL_CHILD,
      acceptanceCriterionRepair =
        AcceptanceCriterionRepairRunState(
          AcceptanceCriterionRepairHydration.Present(state),
          AcceptanceCriterionRepairBinding(
            "catalog",
            "wf-probe",
            DIGEST,
            authored,
            interactionScenarios = listOf(scenario),
          ),
        ),
    )
  }

  private fun criterion(ordinal: Int): AcceptanceCriterionRepairCriterion {
    val path = if (ordinal >= 3) SHARED_PATH else "src/main/kotlin/Card$ordinal.kt"
    return AcceptanceCriterionRepairCriterion(
      canonicalRef = "AC-00$ordinal",
      originalAlias = null,
      originalText = criterionText(ordinal),
      ordinal = ordinal,
      sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_CLOSED,
      validationObligation =
        if (ordinal == 1) {
          AcceptanceCriterionValidationObligation.REQUIRED
        } else {
          AcceptanceCriterionValidationObligation.DEFERRED
        },
      validationOwnerAvailable = true,
      evidenceRef = "$path:12",
      closureEvidenceRef = "$path:12",
    )
  }

  private fun criterionText(ordinal: Int): String =
    when (ordinal) {
      1 -> "Cards cap their height."
      2 -> "Open chats keep their badge."
      3 -> "Arrow keys select a row."
      else -> "Focused cards announce the selected row."
    }

  private val scenario =
    InteractionScenario(
      scenarioRef = "IS-004",
      criterionRefs = listOf("AC-001", "AC-004"),
      subtaskRefs = emptyList(),
      entryPoint = "Needs you",
      preconditions = "both cards have rows",
      steps = "Tab into Needs you",
      outcome = "focus announces the selected run",
    )

  private companion object {
    const val SHARED_PATH = "src/main/kotlin/HomeCard.kt"
    val DIGEST = "a".repeat(64)
  }
}
