package skillbill.engine.featuretask.phase.briefing

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.slot.statusProjectionPhaseStrategies
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeResolvedUpstreamOutputs
import skillbill.workflow.taskruntime.model.handoff.task.FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeSharedReviewEvidenceReference
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimePhaseBriefingBudgetTest {
  @Test
  fun `an oversized upstream projection is delivered whole rather than truncated`() {
    val oversizedBytes = 400_000
    val checkpoint =
      FeatureTaskRuntimeRepositoryCheckpoint(
        "fixture-checkpoint",
      )
    val handoff =
      FeatureTaskRuntimeHandoffContract.assembleHandoff(
        FeatureTaskRuntimeHandoffAssemblyRequest(
          declaration =
            FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
              .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX),
          runInvariants = multiUpstreamInvariants(),
          recordedOutputs = multiUpstreamOutputs(oversizedBytes),
          repositoryCheckpoint = checkpoint,
          expectedRepositoryCheckpoint = checkpoint,
        ),
      )

    val briefing =
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(
        handoff,
        workflowId = "wftr-1",
      )
    assertContains(briefing.briefingText, "r".repeat(64))
  }

  @Test
  fun `normal-size review repair projection excludes unrelated upstream bodies`() {
    val planBody = "p".repeat(4000)
    val implementBody = "i".repeat(4000)
    val reviewBody = "r".repeat(4000)
    val checkpoint =
      FeatureTaskRuntimeRepositoryCheckpoint(
        "fixture-checkpoint",
      )
    val recordedOutputs =
      listOf(
        phaseOutput(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
          """{"produced_outputs":{"finding_dispositions":[{"finding_id":"F-1","disposition":"verified"}]}}""",
        ),
      )
    val handoff =
      FeatureTaskRuntimeHandoffContract.assembleHandoff(
        FeatureTaskRuntimeHandoffAssemblyRequest(
          declaration =
            FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
              .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX),
          runInvariants = multiUpstreamInvariants(),
          recordedOutputs = recordedOutputs,
          repositoryCheckpoint = checkpoint,
          expectedRepositoryCheckpoint = checkpoint,
        ),
      )

    val briefing =
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(
        handoff,
      )

    assertFalse(briefing.briefingText.contains(planBody))
    assertFalse(briefing.briefingText.contains(implementBody))
    assertFalse(briefing.briefingText.contains(reviewBody))
    assertContains(briefing.briefingText, "### from: verify_findings")
    assertContains(briefing.briefingText, "repository_checkpoint")
  }

  @Test
  fun `an undeclared recorded output is never delivered, even when it is present in state`() {
    val handoff =
      FeatureTaskRuntimeHandoffContract.assembleHandoff(
        FeatureTaskRuntimeHandoffAssemblyRequest(
          declaration =
            FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
              .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT),
          runInvariants = multiUpstreamInvariants(),
          recordedOutputs =
            listOf(
              phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN, planProjectionOutput()),
              phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT, "Implemented the plan."),
              phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY, "Simplified the change."),
              phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW, """{"review":"undeclared"}"""),
            ),
          repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "budget-checkpoint-1"),
          expectedRepositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "budget-checkpoint-1"),
        ),
      )

    val briefing =
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(
        handoff,
      )

    assertTrue(briefing.briefingText.contains("Fixture plan prose for downstream implement and audit."))
    assertFalse(briefing.hasUpstreamReceipt("review"))
    assertFalse(briefing.briefingText.contains("undeclared"))
  }

  @Test
  fun `pathologically large layer-1 still renders the acceptance contract`() {
    val pathologicalCriterion = "AC-huge: ${"x".repeat(80_000)}"
    val handoff =
      FeatureTaskRuntimePhaseHandoff(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        runInvariants =
          FeatureTaskRuntimeRunInvariants(
            specReference = ".feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md",
            acceptanceCriteria = listOf(pathologicalCriterion),
            mandatesAndOverrides = emptyList(),
          ),
        upstreamOutputs = FeatureTaskRuntimeResolvedUpstreamOutputs(emptyMap()),
        derivedContextKeys = emptyList(),
      )

    val briefing =
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(
        handoff,
        workflowId = "wftr-1",
      )
    assertContains(briefing.briefingText, "AC-huge:")
    assertContains(briefing.briefingText, "x".repeat(64))
  }

  @Test
  fun `a finalization phase omits the acceptance contract but still receives the operator mandates`() {
    val invariants =
      FeatureTaskRuntimeRunInvariants(
        specReference = ".feature-specs/SKILL-137/spec.md",
        acceptanceCriteria = listOf("AC-1: the acceptance contract text"),
        mandatesAndOverrides = listOf("mandate-1: the policy text"),
      )

    val strategies = statusProjectionPhaseStrategies()
    val facts = PhaseStrategySelectionFacts(SkeletonDefinition.STANDALONE, setOf(CodeReviewExecutionMode.INLINE))

    fun briefingFor(phaseId: String) =
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(
        FeatureTaskRuntimePhaseHandoff(
          phaseId = phaseId,
          runInvariants = invariants,
          upstreamOutputs = FeatureTaskRuntimeResolvedUpstreamOutputs(emptyMap()),
          derivedContextKeys = emptyList(),
        ),
        scope =
          FeatureTaskRuntimeBriefingScope(
            invariantFields = strategies.strategyFor(phaseId, facts).briefingInvariantFields(phaseId),
          ),
      )

    val implementText = briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT).briefingText
    assertContains(implementText, "acceptance_criteria:")
    assertContains(implementText, "mandates_and_overrides:")

    listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
    ).forEach { phaseId ->
      val briefing = briefingFor(phaseId)
      assertFalse(
        briefing.briefingText.contains("acceptance_criteria:"),
        "finalization phase '$phaseId' rendered the acceptance contract; the allowlist did not apply",
      )
      assertContains(
        briefing.briefingText,
        "the policy text",
        message =
          "finalization phase '$phaseId' lost the operator mandates; " +
            "the allowlist is their only delivery path",
      )

      assertEquals(invariants.acceptanceCriteria, briefing.acceptanceCriteria)
      assertContains(briefing.briefingText, "spec_reference:")
    }
    assertContains(
      briefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR).briefingText,
      "acceptance_criteria:",
    )
  }

  @Test
  fun `the rendered briefing carries no forbidden raw-context field name`() {
    val handoff =
      FeatureTaskRuntimeHandoffContract.assembleHandoff(
        FeatureTaskRuntimeHandoffAssemblyRequest(
          declaration =
            FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
              .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT),
          runInvariants = multiUpstreamInvariants(),
          recordedOutputs =
            listOf(
              phaseOutput(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN, planProjectionOutput()),
            ),
        ),
      )

    val briefing =
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(
        handoff,
      )
    val serialized = JsonCodec.mapToJsonString(briefing.briefingArtifactWireMap())

    FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES.forEach { forbidden ->
      assertFalse(
        serialized.contains("\"$forbidden\""),
        "the persisted briefing carries forbidden raw-context field '$forbidden'",
      )
      assertFalse(
        briefing.briefingText.contains("$forbidden:"),
        "the rendered briefing carries forbidden raw-context field '$forbidden'",
      )
    }
  }

  @Test
  fun `shared evidence projection size is independent of branch diff size for review and audit`() {
    fun evidence(hunksPerFile: Int) =
      FeatureTaskRuntimeSharedReviewEvidenceReference(
        storePath = ".skill-bill/run-evidence/wf/fp",
        checkpointFingerprint = "fp",
        baseRef = "base",
        headRef = "head",
        changedFileCount = 8,
        changedHunkCount = 8 * hunksPerFile,
        fileHunkIndexDigest = "a".repeat(64),
      )

    fun projectionBytes(
      phaseId: String,
      hunksPerFile: Int,
    ): Int {
      val declaration = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations.getValue(phaseId)
      val handoff =
        FeatureTaskRuntimePhaseHandoff(
          phaseId = phaseId,
          runInvariants =
            FeatureTaskRuntimeRunInvariants(
              specReference = ".feature-specs/SKILL-164/spec.md",
              acceptanceCriteria = listOf("AC-002"),
              mandatesAndOverrides = emptyList(),
            ),
          upstreamOutputs = FeatureTaskRuntimeResolvedUpstreamOutputs(emptyMap()),
          derivedContextKeys = declaration.derivedContextKeys,
          projectionDeclarations =
            listOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.sharedReviewEvidenceDeclaration(phaseId),
            ),
          repositoryCheckpoint =
            FeatureTaskRuntimeRepositoryCheckpoint(
              fingerprint = "fp",
            ),
        )
      val briefing =
        FeatureTaskRuntimePhaseBriefingAssembler.assemble(
          handoff,
          scope = FeatureTaskRuntimeBriefingScope(sharedReviewEvidence = evidence(hunksPerFile)),
        )
      assertFalse(briefing.briefingText.contains("@@"), "diff hunk bodies must not reach the briefing")
      assertFalse(briefing.briefingText.contains("+val "), "diff bytes must not reach the briefing")
      val marker = "changed_file_count:"
      val start = briefing.briefingText.indexOf(marker)
      assertTrue(start >= 0, "shared evidence projection must render for $phaseId")
      return briefing.briefingText.substring(start).toByteArray(Charsets.UTF_8).size
    }

    listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
    ).forEach { phaseId ->
      val small = projectionBytes(phaseId, hunksPerFile = 1)
      val large = projectionBytes(phaseId, hunksPerFile = 400)

      assertTrue(
        large - small <= 8 * 2,
        "shared evidence briefing size for $phaseId grew from $small to $large across hunk counts",
      )
    }
  }

  private fun multiUpstreamInvariants() =
    FeatureTaskRuntimeRunInvariants(
      specReference = ".feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md",
      acceptanceCriteria = (1..11).map { "AC-$it: ${"criterion-detail ".repeat(20)}" },
      mandatesAndOverrides = (1..6).map { "mandate-$it: ${"override-detail ".repeat(20)}" },
    )

  private fun multiUpstreamOutputs(bodyBytes: Int) =
    listOf(
      phaseOutput(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
        """{"produced_outputs":{"finding_dispositions":[{"finding_id":"F-1","disposition":"verified",""" +
          """"reason":"${"r".repeat(bodyBytes)}"}]}}""",
      ),
    )

  private fun phaseOutput(
    phaseId: String,
    payload: String,
  ) = FeatureTaskRuntimePhaseOutput(phaseId = phaseId, iteration = 1, payload = payload)

  private fun planProjectionOutput(): String =
    """{"contract_version":"0.4","phase_id":"plan","status":"completed",""" +
      """"summary":"Phase produced a validated output.","produced_outputs":""" +
      PlanningProjectionFixtures.PLAN_PROSE + "}"
}
