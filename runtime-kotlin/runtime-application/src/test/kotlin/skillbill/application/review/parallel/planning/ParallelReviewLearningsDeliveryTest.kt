package skillbill.application.review.parallel.planning

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.snapshot.HARNESS_ORIGIN_UNAVAILABLE
import skillbill.application.review.snapshot.ReviewHarnessConfig
import skillbill.application.review.snapshot.ReviewRecorder
import skillbill.application.review.snapshot.diffForPaths
import skillbill.application.review.snapshot.harnessLearning
import skillbill.application.review.snapshot.harnessOrigin
import skillbill.application.review.snapshot.harnessRequest
import skillbill.application.review.snapshot.reviewHarness
import skillbill.application.review.snapshot.reviewPack
import skillbill.application.review.snapshot.reviewed
import skillbill.contracts.JsonCodec
import skillbill.contracts.learning.LearningPayloadKeys
import skillbill.learnings.model.LearningRecord
import skillbill.learnings.model.LearningScope
import skillbill.ports.repository.RepositoryOriginScopeKeyPort
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParallelReviewLearningsDeliveryTest {
  private val pack = reviewPack("kotlin", listOf("architecture", "security"), routingSignals = listOf("*.kt"))
  private val diff = diffForPaths("src/main/kotlin/Repo.kt")

  private val repoLearning =
    harnessLearning(1, LearningScope.REPO, ORIGIN_SCOPE_KEY, "Name strategies", REPO_RULE)
  private val skillLearning =
    harnessLearning(2, LearningScope.SKILL, ROUTED_SKILL, "Cite evidence", SKILL_RULE)
  private val globalLearning =
    harnessLearning(3, LearningScope.GLOBAL, "", "Keep changes small", GLOBAL_RULE)
  private val foreignRepoLearning =
    harnessLearning(4, LearningScope.REPO, "other/repo", "Foreign repo rule", FOREIGN_REPO_RULE)
  private val foreignSkillLearning =
    harnessLearning(5, LearningScope.SKILL, "bill-swift-code-review", "Foreign skill rule", FOREIGN_SKILL_RULE)
  private val disabledLearning =
    harnessLearning(6, LearningScope.REPO, ORIGIN_SCOPE_KEY, "Disabled rule", DISABLED_RULE, status = "disabled")

  private val allLearnings =
    listOf(
      repoLearning,
      skillLearning,
      globalLearning,
      foreignRepoLearning,
      foreignSkillLearning,
      disabledLearning,
    )

  @Test fun `every launch envelope carries the in-scope learnings the driver resolved`() {
    val recorder = ReviewRecorder()
    val result = review(recorder, allLearnings, harnessOrigin(ORIGIN_SCOPE_KEY))

    assertEquals(listOf<Pair<String?, String?>>(ORIGIN_SCOPE_KEY to ROUTED_SKILL), recorder.learningResolutions)
    assertEquals("L-001, L-002, L-003", result.appliedLearnings)
    val launches = recorder.parentPrompts
    assertTrue(launches.isNotEmpty(), "The delegated run must launch the parent review.")
    launches.forEach { prompt ->
      assertTrue(REPO_RULE in prompt, "Every launch must carry the repo-scoped rule text.")
      assertTrue("Name strategies" in prompt, "Every launch must carry the learning title.")
      assertTrue(SKILL_RULE in prompt && GLOBAL_RULE in prompt)
    }
    recorder.parentPrompts.forEach { prompt ->
      assertTrue(FOREIGN_REPO_RULE !in prompt, "A learning scoped to another repo must never be launched.")
      assertTrue(FOREIGN_SKILL_RULE !in prompt, "A learning scoped to another skill must never be launched.")
      assertTrue(DISABLED_RULE !in prompt, "A disabled learning must never be launched.")
    }

    val (sessionId, sessionJson) = recorder.savedSessionLearnings.single()
    assertEquals(result.reviewSessionId, sessionId)
    val telemetryRow = persistedSessionRow(sessionJson)
    assertEquals(
      result.appliedLearnings,
      telemetryRow[LearningPayloadKeys.APPLIED_LEARNINGS],
      "Finished-review telemetry reads applied_learnings from this row; it must match the printed summary.",
    )
    assertEquals(
      listOf("L-001", "L-002", "L-003"),
      JsonCodec.anyToStringList(telemetryRow[LearningPayloadKeys.APPLIED_LEARNING_REFERENCES]),
    )
  }

  @Test fun `a review with no matching learnings still records one session row and reports none`() {
    val recorder = ReviewRecorder()
    val result = review(recorder, listOf(foreignRepoLearning, disabledLearning), harnessOrigin(ORIGIN_SCOPE_KEY))

    assertEquals("none", result.appliedLearnings)
    assertEquals(1, recorder.savedSessionLearnings.size, "An empty resolution still owns one session_learnings row.")
    val (sessionId, sessionJson) = recorder.savedSessionLearnings.single()
    assertEquals(result.reviewSessionId, sessionId)
    assertEquals("none", persistedSessionRow(sessionJson)[LearningPayloadKeys.APPLIED_LEARNINGS])
  }

  @Test fun `a repository without an origin remote still resolves global and skill learnings`() {
    val recorder = ReviewRecorder()
    val result = review(recorder, allLearnings, null)

    assertEquals(listOf<Pair<String?, String?>>(null to ROUTED_SKILL), recorder.learningResolutions)
    assertEquals("L-002, L-003", result.appliedLearnings)
    recorder.parentPrompts.forEach { prompt ->
      assertTrue(SKILL_RULE in prompt && GLOBAL_RULE in prompt)
    }
    recorder.parentPrompts.forEach { prompt ->
      assertTrue(REPO_RULE !in prompt, "No origin remote means no repo-scoped learning may be launched.")
    }
    assertEquals(1, recorder.diagnosticWarnings.size, "A missing repo scope is a degradation and must be recorded.")
  }

  private fun persistedSessionRow(sessionJson: String): Map<String, Any?> =
    assertNotNull(
      JsonCodec.parseObjectOrNull(sessionJson)?.let(JsonCodec::jsonElementToValue)?.let(JsonCodec::anyToStringAnyMap),
      "The persisted session_learnings row must be a JSON object.",
    )

  private fun review(
    recorder: ReviewRecorder,
    learnings: List<LearningRecord>,
    origin: RepositoryOriginScopeKeyPort?,
  ): ParallelCodeReviewResult {
    val config =
      ReviewHarnessConfig(
        manifests = listOf(pack),
        diff = diff,
        learnings = learnings,
        originScopeKeyPort = origin ?: HARNESS_ORIGIN_UNAVAILABLE,
      )
    return reviewHarness(config, recorder)
      .reviewed(
        harnessRequest(
          reviewRunId = "learnings-delivery",
          codeReviewMode = CodeReviewExecutionMode.DELEGATED,
        ),
      )
  }
}

private const val ORIGIN_SCOPE_KEY = "acme/repo"

private const val ROUTED_SKILL = "bill-kotlin-code-review"

private const val REPO_RULE = "Prefer named strategies over identity branching."

private const val SKILL_RULE = "Cite file and line for every finding."

private const val GLOBAL_RULE = "Prefer the smallest change that holds."

private const val FOREIGN_REPO_RULE = "This rule belongs to another repository."

private const val FOREIGN_SKILL_RULE = "This rule belongs to another review skill."

private const val DISABLED_RULE = "This rule was disabled and must not ship."
