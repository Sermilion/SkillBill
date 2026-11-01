package skillbill.engine.goalrunner.execution.core

import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.parentSpecPath
import skillbill.engine.goalrunner.execution.support.toPullRequestRequest
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalQaSummary
import skillbill.engine.goalrunner.model.GoalSubtaskQaSummary
import skillbill.engine.goalrunner.model.GoalSubtaskUnknownScenarios
import skillbill.engine.goalrunner.model.GoalUnknownScenario
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalRunnerPullRequestFormattingTest {
  @Test
  fun `pull request uses issue key title case feature title and capmo summary sections`() {
    val request = manifest().toPullRequestRequest(Path.of("/repo"))

    assertEquals("[333] Review Citation Ingestion", request.title)
    assertTrue(request.body.startsWith("# Summary\n\n"))
    assertTrue(request.body.contains("This pull request delivers Review Citation Ingestion"))
    assertTrue(request.body.contains("## Feature Flags\n\nN/A"))
    assertTrue(request.body.contains("## Media\n\nN/A"))
    assertTrue(request.body.contains("# How Has This Been Tested?"))
    assertTrue(request.body.contains("- Parse citation metadata"))
    assertFalse(request.body.contains("# Checklist"))
    assertFalse(request.body.contains("- [ ]"))
  }

  @Test
  fun `pull request states goal and subtask QA outcomes`() {
    val outcome =
      FeatureTaskQaOutcome(
        "wfl-1",
        StandaloneQaOutcome.SKIPPED,
        "offline_target",
        2,
        "a".repeat(64),
        "2026-10-18T00:00Z",
      )
    val body =
      manifest().toPullRequestRequest(
        Path.of("/repo"),
        goalQaSummary = GoalQaSummary.Recorded(outcome),
        qaSummary = { GoalSubtaskQaSummary.Recorded(outcome) },
      ).body
    assertTrue(
      body.contains("- Parse citation metadata: skipped (offline_target), after 2 qa_fix attempts"),
    )
    assertTrue(body.contains("## Mobile QA\n\n- Goal: skipped (offline_target), after 2 qa_fix attempts"))

    val preQa =
      manifest().toPullRequestRequest(Path.of("/repo"), qaSummary = { GoalSubtaskQaSummary.NotComposed }).body
    assertTrue(preQa.contains("- Parse citation metadata: QA not composed (pre-QA workflow)"))
    assertTrue(
      manifest().toPullRequestRequest(Path.of("/repo")).body.contains("- Goal: no goal-level QA outcome recorded"),
    )
  }

  @Test
  fun `pull request lists untraced interaction scenarios per subtask and skips subtasks without any`() {
    val body =
      twoSubtaskManifest().toPullRequestRequest(Path.of("/repo"), unknownScenarios = ::unknownScenariosFor).body

    assertTrue(body.contains("## Interaction Trace\n\n"))
    assertTrue(
      body.contains(
        "- Parse citation metadata: Interaction scenarios not traced: IS-001 (malformed audit \\| \\`output\\`); " +
          "IS-002 (reason unavailable)",
      ),
    )
    assertEquals(1, Regex("Interaction scenarios not traced:").findAll(body).count())
    assertFalse(body.contains("- Store citations: Interaction scenarios not traced"))

    val none =
      twoSubtaskManifest().toPullRequestRequest(Path.of("/repo")) { GoalSubtaskUnknownScenarios.Empty }.body
    assertFalse(none.contains("Interaction Trace"))
  }

  private fun unknownScenariosFor(subtask: DecompositionSubtask): GoalSubtaskUnknownScenarios =
    if (subtask.id == 1) {
      GoalSubtaskUnknownScenarios.Recorded(
        listOf(GoalUnknownScenario("IS-001", "malformed\naudit | `output`"), GoalUnknownScenario("IS-002", null)),
      )
    } else {
      GoalSubtaskUnknownScenarios.Empty
    }

  private fun twoSubtaskManifest(): DecompositionManifest =
    manifest().let { base ->
      base.copy(
        subtasks =
          base.subtasks +
            DecompositionSubtask(
              id = 2,
              name = "Store citations",
              specPath = ".feature-specs/333-review-citation-ingestion/spec_subtask_2.md",
            ),
      )
    }

  private fun manifest() =
    DecompositionManifest(
      issueKey = "333",
      featureName = "review-citation-ingestion",
      parentSpecPath = ".feature-specs/333-review-citation-ingestion/spec.md",
      baseBranch = "main",
      featureBranch = "feat/333-review-citation-ingestion",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
      subtasks =
        listOf(
          DecompositionSubtask(
            id = 1,
            name = "Parse citation metadata",
            specPath = ".feature-specs/333-review-citation-ingestion/spec_subtask_1.md",
          ),
        ),
    )
}
