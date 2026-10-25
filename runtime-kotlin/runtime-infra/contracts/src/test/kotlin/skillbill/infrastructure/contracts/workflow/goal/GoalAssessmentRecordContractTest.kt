package skillbill.infrastructure.contracts.workflow.goal

import skillbill.contracts.workflow.featuretask.GOAL_ASSESSMENT_RECORD_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.error.shellcontent.invalidGoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentDisposition
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRole
import skillbill.goalrunner.assessment.model.GoalAssessmentRound
import skillbill.goalrunner.assessment.model.GoalAssessmentRun
import skillbill.goalrunner.assessment.model.GoalAssessmentRunStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.locator.GoalAssessmentRecordSchemaPaths
import skillbill.workflow.taskruntime.artifact.asGoalAssessmentRecordWireMap
import skillbill.workflow.taskruntime.artifact.toGoalAssessmentRecord
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import skillbill.contracts.workflow.featuretask.GoalAssessmentRecordPayloadKeys as Keys

class GoalAssessmentRecordContractTest {
  private val schema =
    ClasspathContractSchemaLoader.readClasspathYamlNode(javaClass.classLoader, SCHEMA_RESOURCE) {
      invalidGoalAssessmentRecord(SCHEMA_RESOURCE, "packaged schema resource is missing.")
    }

  @Test
  fun `schema packages the canonical identity and version`() {
    assertEquals(GoalAssessmentRecordSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
    assertEquals(
      GOAL_ASSESSMENT_RECORD_CONTRACT_VERSION,
      schema.path("properties").path(Keys.CONTRACT_VERSION).path("const").asText(),
    )
    assertFalse(schema.path("additionalProperties").asBoolean(true))
  }

  @Test
  fun `a record round trips through the wire codec and satisfies the schema`() {
    val record = record()

    val wire = record.asGoalAssessmentRecordWireMap()

    assertTrue(violations(wire).isEmpty())
    assertEquals(record, wire.toGoalAssessmentRecord("test"))
  }

  @Test
  fun `a payload that breaks the schema or its coherence is rejected by the codec`() {
    val valid = record().asGoalAssessmentRecordWireMap()
    val secondRoundReusingRef =
      artifact(valid, Keys.ROUNDS to listOf(roundMap(valid), roundMap(valid) + (Keys.ROUND to 2)))
    val danglingOpenRef = artifact(valid, Keys.OPEN_FINDING_REFS to listOf("GA-009"))
    val fourRounds = artifact(valid, Keys.ROUNDS to (1..4).map { roundMap(valid) + (Keys.ROUND to it) })

    assertTrue(violations(fourRounds).isNotEmpty())
    listOf(
      secondRoundReusingRef,
      danglingOpenRef,
      fourRounds,
      artifact(valid, Keys.CONTRACT_VERSION to "9.0"),
      artifact(valid, "extra" to true),
    ).forEach { payload ->
      val error = assertFailsWith<SkillBillRuntimeException> { payload.toGoalAssessmentRecord("bad") }
      assertEquals(FeatureTaskRuntimeFailureCode.INVALID_GOAL_ASSESSMENT_RECORD, error.code)
    }
  }

  private fun artifact(
    base: Map<String, Any?>,
    override: Pair<String, Any?>,
  ): FeatureTaskRuntimeWorkflowArtifactMap = FeatureTaskRuntimeWorkflowArtifactMap.from(base + override)

  private fun violations(wire: Map<String, Any?>) =
    ClasspathContractSchemaLoader.validate(
      ClasspathContractSchemaLoader.compileUncachedYamlNode(schema),
      ClasspathContractSchemaLoader.valueToTree(wire),
    )

  private fun roundMap(wire: Map<String, Any?>): Map<String, Any?> =
    FeatureTaskRuntimeWorkflowArtifactMap.from((wire[Keys.ROUNDS] as List<*>).first())

  private fun record() =
    GoalAssessmentRecord(
      issueKey = "SKILL-1",
      rounds =
        listOf(
          GoalAssessmentRound(
            round = 1,
            headSha = "a".repeat(HEAD_SHA_LENGTH),
            scopeFiles = emptyList(),
            assessorRuns = listOf(GoalAssessmentRun(GoalAssessmentRole.SUBTASK, 1, GoalAssessmentRunStatus.COMPLETED)),
            findings =
              listOf(
                GoalAssessmentFinding(
                  findingRef = "GA-001",
                  kind = GoalAssessmentFindingKind.DEFECT,
                  severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
                  path = "src/Main.kt",
                  line = 12,
                  failureScenario = "an empty list crashes the loader",
                  guardedRef = null,
                  subtaskId = 1,
                  originRound = 1,
                  verification = GoalAssessmentVerification.UNVERIFIED,
                  verificationEvidence = "",
                  fixRoundsSurvived = 0,
                  disposition = GoalAssessmentDisposition.PR_ONLY,
                  state = GoalAssessmentFindingState.OPEN,
                ),
              ),
            scenarios = emptyList(),
            regressions = emptyList(),
            fix = null,
          ),
        ),
      exitReason = null,
      openFindingRefs = listOf("GA-001"),
    )

  private companion object {
    const val HEAD_SHA_LENGTH = 40
    const val SCHEMA_RESOURCE = GoalAssessmentRecordSchemaPaths.CLASSPATH_RESOURCE
  }
}
