package skillbill.goalrunner

import skillbill.goalrunner.ledger.parseInstantOrNull
import skillbill.goalrunner.model.DurableDecodeSubstitutionObservations
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestRejectionReason
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalRunnerWorkerSubtaskRequestParserTest {
  @Test
  fun `parser ignores free form output and queues marked safe requests`() {
    val outcomes =
      GoalRunnerWorkerSubtaskRequestParser.parse(
        stdout =
          """
          ordinary model progress text
          SKILL_BILL_SUBTASK_REQUEST: {"kind":"skill_bill_subtask_request","name":"Follow up","spec_path":".feature-specs/SKILL-61/spec_subtask_2_follow_up.md"}
          """.trimIndent(),
        stderr = "",
        manifest = manifest(),
      )

    val queued = assertIs<GoalRunnerWorkerSubtaskRequestOutcome.Queued>(outcomes.single())
    assertEquals("Follow up", queued.request.name)
    assertEquals("stdout", queued.sourceStream)
  }

  @Test
  fun `parser produces typed rejection and confirmation outcomes`() {
    val outcomes =
      GoalRunnerWorkerSubtaskRequestParser.parse(
        stdout =
          """
          SKILL_BILL_SUBTASK_REQUEST: {"kind":"skill_bill_subtask_request","name":"Duplicate","spec_path":"../outside.md"}
          SKILL_BILL_SUBTASK_REQUEST_BEGIN
          {"kind":"skill_bill_subtask_request","name":"Confirm","spec_path":".feature-specs/SKILL-61/spec_subtask_3_confirm.md","requires_operator_confirmation":true}
          SKILL_BILL_SUBTASK_REQUEST_END
          """.trimIndent(),
        stderr = """SKILL_BILL_SUBTASK_REQUEST: {"kind":"skill_bill_subtask_request","name":"Broken"}""",
        manifest = manifest(),
      )

    val unsafe = assertIs<GoalRunnerWorkerSubtaskRequestOutcome.Rejected>(outcomes[0])
    assertEquals(GoalRunnerWorkerSubtaskRequestRejectionReason.UNSAFE_PATH, unsafe.reason)
    val confirmation = assertIs<GoalRunnerWorkerSubtaskRequestOutcome.RequiresOperatorConfirmation>(outcomes[1])
    assertEquals("Confirm", confirmation.request.name)
    val malformed = assertIs<GoalRunnerWorkerSubtaskRequestOutcome.Rejected>(outcomes[2])
    assertEquals(GoalRunnerWorkerSubtaskRequestRejectionReason.MALFORMED, malformed.reason)
  }

  @Test
  fun `parser rejects non boolean operator confirmation values`() {
    val outcomes =
      GoalRunnerWorkerSubtaskRequestParser.parse(
        stdout =
          """
          SKILL_BILL_SUBTASK_REQUEST: {"kind":"skill_bill_subtask_request","name":"String confirmation","spec_path":".feature-specs/SKILL-61/spec_subtask_2_string_confirmation.md","requires_operator_confirmation":"true"}
          """.trimIndent(),
        stderr = "",
        manifest = manifest(),
      )

    val rejected = assertIs<GoalRunnerWorkerSubtaskRequestOutcome.Rejected>(outcomes.single())
    assertEquals(GoalRunnerWorkerSubtaskRequestRejectionReason.MALFORMED, rejected.reason)
    assertTrue(rejected.message.contains("requires_operator_confirmation"))
  }

  @Test
  fun `parser rejects malformed dependency ids`() {
    val outcomes =
      GoalRunnerWorkerSubtaskRequestParser.parse(
        stdout =
          """
          SKILL_BILL_SUBTASK_REQUEST: {"kind":"skill_bill_subtask_request","name":"Bad dependency","spec_path":".feature-specs/SKILL-61/spec_subtask_2_bad_dependency.md","depends_on_subtask_ids":["abc"]}
          SKILL_BILL_SUBTASK_REQUEST: {"kind":"skill_bill_subtask_request","name":"Fractional dependency","spec_path":".feature-specs/SKILL-61/spec_subtask_2_fractional_dependency.md","depends_on_subtask_ids":[1.9]}
          SKILL_BILL_SUBTASK_REQUEST: {"kind":"skill_bill_subtask_request","name":"Null dependency","spec_path":".feature-specs/SKILL-61/spec_subtask_2_null_dependency.md","depends_on_subtask_ids":null}
          """.trimIndent(),
        stderr = "",
        manifest = manifest(),
      )

    assertEquals(3, outcomes.size)
    outcomes.forEach { outcome ->
      val rejected = assertIs<GoalRunnerWorkerSubtaskRequestOutcome.Rejected>(outcome)
      assertEquals(GoalRunnerWorkerSubtaskRequestRejectionReason.MALFORMED, rejected.reason)
      assertTrue(rejected.message.contains("dependencies"))
    }
  }

  @Test
  fun `malformed payload and timestamp substitutions are recorded at their seams`() {
    DurableDecodeSubstitutionObservations.drain()
    val outcome =
      GoalRunnerWorkerSubtaskRequestParser.parse(
        stdout = "SKILL_BILL_SUBTASK_REQUEST: {",
        stderr = "",
        manifest = manifest(),
      ).single()
    assertIs<GoalRunnerWorkerSubtaskRequestOutcome.Rejected>(outcome)
    assertEquals("2026-09-16T22:49:00Z", parseInstantOrNull("2026-09-16T22:49:00Z")?.toString())
    assertEquals("2026-09-16T22:49:00Z", parseInstantOrNull("2026-09-16 22:49:00")?.toString())
    assertNull(parseInstantOrNull("not-a-timestamp"))
    val records = DurableDecodeSubstitutionObservations.drain()
    assertEquals(
      listOf(
        "GoalRunnerWorkerSubtaskRequestParser.parsePayloadMap",
        "AttemptLedgerAccumulator.parseInstantOrNull",
      ),
      records.map { it.seam },
    )
  }

  @Test
  fun `scheduler appends queued work as visible sibling subtasks`() {
    val scheduled =
      GoalRunnerWorkerSubtaskScheduler.scheduleQueuedRequests(
        manifest = manifest(),
        outcomes =
          GoalRunnerWorkerSubtaskRequestParser.parse(
            stdout =
              """
              SKILL_BILL_SUBTASK_REQUEST: {"kind":"skill_bill_subtask_request","name":"Runtime sibling","spec_path":".feature-specs/SKILL-61/spec_subtask_2_runtime_sibling.md"}
              """.trimIndent(),
            stderr = "",
            manifest = manifest(),
          ),
      )

    val accepted = assertIs<GoalRunnerWorkerSubtaskRequestOutcome.Accepted>(scheduled.outcomes.single())
    assertEquals(2, accepted.subtask.id)
    assertEquals("Runtime sibling", scheduled.manifest.subtasks.last().name)
    assertTrue(scheduled.manifest.subtasks.last().dependencies.any { it.subtaskId == 1 })
  }

  private fun manifest(): DecompositionManifest =
    DecompositionManifest(
      issueKey = "SKILL-61",
      featureName = "goal-observability",
      parentSpecPath = ".feature-specs/SKILL-61/spec.md",
      baseBranch = "main",
      featureBranch = "feat/SKILL-61-goal-observability",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
      subtasks =
        listOf(
          DecompositionSubtask(
            id = 1,
            name = "Foundation",
            specPath = ".feature-specs/SKILL-61/spec_subtask_1_foundation.md",
            status = "in_progress",
          ),
        ),
    )
}
