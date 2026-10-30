package skillbill.infrastructure.sqlite.workflow.artifact

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.LegacyGoalContinuationMarker
import skillbill.workflow.taskruntime.model.persistence.UndecodedTypedArtifact
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

private val continuationKey = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()
private val sha = "a".repeat(64)

class GoalContinuationArtifactCodecTest {
  @Test
  fun `encode keeps the pre-change key order and decode returns the same record`() {
    val full =
      FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = "SKILL-173",
        subtaskId = 2,
        suppressPr = true,
        goalBranch = "feat/SKILL-173",
        parentWorkflowId = "wfl-parent",
        codeReviewMode = CodeReviewExecutionMode.DELEGATED,
        validationDepth = ValidationDepth.FULL,
        qualityGateSelection = FeatureTaskRuntimeQualityGateSelection.BUILD,
        subtaskName = "Codec owner",
        agentAddonSelection =
          AgentAddonSelection(
            listOf(
              PersistedAgentAddonSelectionEntry("helper", "/repo/helper/agent-addon.yaml", sha),
              PersistedAgentAddonSelectionEntry("second-helper", "/repo/second/agent-addon.yaml", sha),
            ),
          ),
      )
    val minimal =
      FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = "SKILL-173",
        subtaskId = 1,
        suppressPr = true,
        goalBranch = "feat/SKILL-173",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      )

    assertEquals(
      """{"issue_key":"SKILL-173","subtask_id":2,"suppress_pr":true,"goal_branch":"feat/SKILL-173",""" +
        """"code_review_mode":"${CodeReviewExecutionMode.DELEGATED.wireValue}","parent_workflow_id":"wfl-parent",""" +
        """"validation_depth":"${ValidationDepth.FULL.wireValue}",""" +
        """"quality_gate_selection":"${FeatureTaskRuntimeQualityGateSelection.BUILD.wireValue}",""" +
        """"subtask_name":"Codec owner","agent_addon_selection":[""" +
        """{"slug":"helper","source_identity":"/repo/helper/agent-addon.yaml","content_sha256":"$sha"},""" +
        """{"slug":"second-helper","source_identity":"/repo/second/agent-addon.yaml","content_sha256":"$sha"}]}""",
      JsonCodec.valueToJsonString(GoalContinuationArtifactCodec.encode(full)),
    )
    assertEquals(
      """{"issue_key":"SKILL-173","subtask_id":1,"suppress_pr":true,"goal_branch":"feat/SKILL-173",""" +
        """"code_review_mode":"${CodeReviewExecutionMode.INLINE.wireValue}"}""",
      JsonCodec.valueToJsonString(GoalContinuationArtifactCodec.encode(minimal)),
    )
    listOf(full, minimal).forEach { record ->
      assertEquals(record, GoalContinuationArtifactCodec.decode(GoalContinuationArtifactCodec.encode(record)))
    }
  }

  @Test
  fun `decode rejects unknown fields, bad enum values, a blank lane and non-integral or non-positive subtask ids`() {
    val complete =
      GoalContinuationArtifactCodec.encode(
        FeatureTaskRuntimeGoalContinuationArtifact(
          issueKey = "SKILL-119",
          subtaskId = 2,
          suppressPr = true,
          goalBranch = "feat/SKILL-119",
          codeReviewMode = CodeReviewExecutionMode.INLINE,
        ),
      )

    listOf(
      complete - "code_review_mode",
      complete + ("unexpected" to true),
      complete + ("parallel_review_agent" to ""),
      complete + ("validation_depth" to "partial"),
      complete + ("code_review_mode" to "partial"),
      complete + ("quality_gate_selection" to "unknown"),
      complete + ("subtask_id" to 0),
      complete + ("subtask_id" to 2.7),
    ).forEach { malformed ->
      assertFailsWith<SkillBillRuntimeException>(malformed.toString()) {
        GoalContinuationArtifactCodec.decode(malformed)
      }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
    }
  }

  @Test
  fun `a stored object the strict decoder rejects loads as an opaque carrier and merges back unchanged`() {
    val stored = """{"issue_key":"SKILL-1","subtask_id":1,"suppress_pr":true,"goal_branch":"b","unexpected":true}"""
    val artifactsJson = """{"assessment":{"a":1},"$continuationKey":$stored}"""

    val split = FeatureTaskWorkflowArtifactsCodec.split(artifactsJson)

    val carrier = assertIs<UndecodedTypedArtifact>(split.typed[continuationKey])
    assertEquals(stored, carrier.jsonText)
    assertEquals("""{"assessment":{"a":1}}""", split.remainderJson)
    val firstFailure = assertFailsWith<SkillBillRuntimeException> { split.typed.goalContinuationArtifact() }
    val secondFailure = assertFailsWith<SkillBillRuntimeException> { split.typed.goalContinuationArtifact() }
    assertEquals(firstFailure.message, secondFailure.message)
    assertEquals(
      JsonCodec.parseValue(artifactsJson),
      JsonCodec.parseValue(FeatureTaskWorkflowArtifactsCodec.merge(split.remainderJson, split.typed)),
    )
  }

  @Test
  fun `a legacy prose marker object keeps its goal child identity and merges back unchanged`() {
    val stored = """{"enabled":true,"issue_key":"SKILL-2","subtask_id":3}"""
    val artifactsJson = """{"$continuationKey":$stored}"""

    val split = FeatureTaskWorkflowArtifactsCodec.split(artifactsJson)

    val carrier = assertIs<UndecodedTypedArtifact>(split.typed[continuationKey])
    assertEquals(LegacyGoalContinuationMarker("SKILL-2", 3), carrier.legacyMarker)
    assertEquals("{}", split.remainderJson)
    assertEquals(artifactsJson, FeatureTaskWorkflowArtifactsCodec.merge(split.remainderJson, split.typed))
    assertNull(GoalContinuationArtifactCodec.legacyMarker(mapOf("issue_key" to "SKILL-2", "subtask_id" to 3)))
  }
}
