package skillbill.workflow.taskruntime.phase.task

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimePhaseWorkflowDefinitionTest {
  private val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition

  @Test
  fun `definition exposes public feature task identity and uses runtime mode`() {
    assertEquals("bill-feature-task", definition.skillName)
    assertEquals("bill-feature-task", definition.workflowName)
    assertEquals("runtime", definition.workflowMode)
    assertEquals("wftr", definition.workflowIdPrefix)
  }

  @Test
  fun `continuation extras are enabled only for the feature-task definition`() {
    val engine = WorkflowEngine()
    val artifacts =
      JsonCodec.mapToJsonString(
        mapOf(
          "assessment" to mapOf("feature_name" to "typed boundaries", "feature_size" to "medium"),
          "branch" to mapOf("branch_name" to "feat/SKILL-351"),
        ),
      )
    val runtimeDecision =
      engine.continueDecision(
        definition,
        WorkflowStateSnapshot(
          workflowId = "wftr-1",
          sessionId = "sess",
          workflowName = definition.workflowName,
          contractVersion = definition.contractVersion,
          workflowStatus = WorkflowStatus.BLOCKED,
          currentStepId = "implement",
          steps = emptyList(),
          artifacts =
            DurableWorkflowArtifacts.fromMap(
              requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(artifacts))),
            ),
          startedAt = null,
          updatedAt = null,
          finishedAt = null,
          mode = definition.workflowMode?.let(FeatureTaskWorkflowMode::fromWireValue),
        ),
      )
    val verifyDefinition = FeatureVerifyWorkflowDefinition.definition
    val verifyDecision =
      engine.continueDecision(
        verifyDefinition,
        WorkflowStateSnapshot(
          workflowId = "wfv-1",
          sessionId = "sess",
          workflowName = verifyDefinition.workflowName,
          contractVersion = verifyDefinition.contractVersion,
          workflowStatus = WorkflowStatus.BLOCKED,
          currentStepId = "gather_diff",
          steps = emptyList(),
          artifacts =
            DurableWorkflowArtifacts.fromMap(
              requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(artifacts))),
            ),
          startedAt = null,
          updatedAt = null,
          finishedAt = null,
          mode = verifyDefinition.workflowMode?.let(FeatureTaskWorkflowMode::fromWireValue),
        ),
      )

    assertEquals(
      mapOf(
        "feature_name" to "typed boundaries",
        "feature_size" to "medium",
        "branch_name" to "feat/SKILL-351",
      ),
      runtimeDecision.view.extraFields.toMap(),
    )
    assertEquals(emptyMap(), verifyDecision.view.extraFields.toMap())
  }

  @Test
  fun `durable workflow-state contract version is independent of the phase-output contract`() {
    assertEquals(WORKFLOW_STATE_CONTRACT_VERSION, definition.contractVersion)
  }

  @Test
  fun `definition keeps typed statuses alongside their wire compatibility sets`() {
    assertEquals(
      definition.workflowStatusEnums.map { it.wireValue }.toSet(),
      definition.workflowStatuses,
    )
    assertEquals(
      definition.stepStatusEnums.map { it.wireValue }.toSet(),
      definition.stepStatuses,
    )
    assertEquals(
      definition.terminalStatusEnums.map { it.wireValue }.toSet(),
      definition.terminalStatuses,
    )
  }

  @Test
  fun `step ids are ordered and every step has a label and a declared dependency set`() {
    val expectedOrder =
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
      )
    assertEquals(expectedOrder, definition.stepIds)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN, definition.defaultInitialStepId)
    assertEquals(
      mapOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to "Phase 1: Pre-plan",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to "Phase 2: Plan",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to "Phase 3: Implement",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY to "Phase 3b: Simplify",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to "Phase 4: Completeness Audit",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to "Phase 5: Code Review",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to "Phase 5a: Verify Findings",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to "Phase 5b: Implement Fix",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD to "Phase 5c: Build",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to "Phase 6: Quality Validation",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to "Phase 7: Boundary History",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to "Phase 8: Commit and Push",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to "Phase 9: Pull Request",
      ),
      definition.stepLabels,
    )
    definition.stepIds.forEach { stepId ->
      assertTrue(definition.stepLabels.containsKey(stepId), "Missing label for $stepId")
      assertTrue(definition.requiredArtifactsByStep.containsKey(stepId), "Missing dependency set for $stepId")
      assertTrue(definition.resumeActions.containsKey(stepId), "Missing resume action for $stepId")
    }
  }

  @Test
  fun `per-phase dependency-set resolution over the DAG matches declarations`() {
    assertPerPhaseDependencySetsMatchDeclarations()
  }

  @Test
  fun `every forward phase's declared dependency references an earlier phase forming a valid DAG`() {
    val order = definition.stepIds
    val loopOnly = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds
    definition.stepIds.forEachIndexed { index, phaseId ->
      if (phaseId in loopOnly) return@forEachIndexed
      phaseWorkflowDependenciesOf(phaseId).forEach { upstream ->
        val upstreamIndex = order.indexOf(upstream)
        assertTrue(upstreamIndex in 0 until index, "$phaseId depends on $upstream which is not strictly earlier")
      }
    }
  }

  @Test
  fun `implement_fix is reached only by the bounded review_fix edge`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val transitions = def.transitions
    assertEquals(setOf(def.PHASE_IMPLEMENT_FIX, def.PHASE_BUILD), transitions.loopOnlyPhaseIds)
    assertEquals(emptyMap(), transitions.loopOnlySuccessors)
    val edge = transitions.backwardEdges.single { it.loopId == def.REVIEW_FIX_LOOP_ID }
    assertEquals(def.PHASE_VERIFY_FINDINGS, edge.fromPhaseId)
    assertEquals(def.PHASE_IMPLEMENT_FIX, edge.destinationPhaseId)
    assertEquals("review_fix", edge.loopId)
    assertEquals(1, edge.perEdgeCap)
    assertEquals(FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE, edge.capExhaustionBehavior)
    assertEquals(FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED, edge.triggeringVerdict)
    assertEquals(
      listOf(def.PHASE_VERIFY_FINDINGS),
      phaseWorkflowDependenciesOf(def.PHASE_IMPLEMENT_FIX),
    )
    val fixProjections = def.phaseDeclarations.getValue(def.PHASE_IMPLEMENT_FIX).projectionDeclarations
    assertEquals(
      FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH,
      fixProjections.single { it.projectionName == "review_repair_request" }.checkpointPolicy,
    )
  }

  @Test
  fun `record regeneration loops are empty after implement prose migration`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition

    assertEquals(emptySet(), def.REGENERATION_LOOP_IDS)
    assertTrue(def.transitions.backwardEdges.none { def.isRegenerationLoopId(it.loopId) })
  }

  @Test
  fun `audit has no backward edge and only review_fix remains`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val transitions = def.transitions
    assertEquals(1, transitions.backwardEdges.size)
    assertTrue(transitions.backwardEdges.none { it.loopId == def.AUDIT_GAP_LOOP_ID })
  }

  @Test
  fun `phase projections omit audit completion dependencies and pr owns its diff key`() {
    val declarations = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    definition.stepIds.forEach { phaseId ->
      val declaration =
        when (phaseId) {
          def.PHASE_WRITE_HISTORY, def.PHASE_COMMIT_PUSH ->
            FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclarationWithoutSteps(
              phaseId,
              FeatureTaskRuntimeFeatureSize.MEDIUM,
              setOf(def.PHASE_BUILD),
            )
          else -> declarations.getValue(phaseId)
        }
      val projectedDependencies = phaseWorkflowDependenciesOf(phaseId).filterNot { it == def.PHASE_AUDIT }
      assertEquals(projectedDependencies, declaration.consumedUpstreamPhaseIds, phaseId)
    }
    assertEquals(
      listOf("diff"),
      declarations.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW).derivedContextKeys,
    )
    assertEquals(
      listOf(FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_PR_BRANCH_DIFF),
      declarations.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR).derivedContextKeys,
    )
    assertTrue(
      declarations.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR).projectionDeclarations.none {
        it.sourceRef == FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence
      },
    )
    assertEquals(
      listOf("current_unit_of_work"),
      FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclaration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        FeatureTaskRuntimeFeatureSize.SMALL,
      ).derivedContextKeys,
    )
    assertEquals(
      listOf("diff"),
      FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclaration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        FeatureTaskRuntimeFeatureSize.MEDIUM,
      ).derivedContextKeys,
    )
    assertEquals(
      emptyList(),
      declarations.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN).derivedContextKeys,
    )
    assertEquals(
      emptyList(),
      declarations.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN).derivedContextKeys,
    )
  }
}

internal fun phaseWorkflowDependenciesOf(phaseId: String): List<String> =
  FeatureTaskRuntimePhaseWorkflowDefinition.definition.requiredArtifactsByStep.getValue(phaseId)
