package skillbill.application.review.parallel.planning

import skillbill.application.review.model.ParallelCodeReviewPlanned
import skillbill.application.review.parallel.runner.ParallelCodeReviewStackDetection
import skillbill.application.review.snapshot.ReviewHarnessConfig
import skillbill.application.review.snapshot.ReviewRecorder
import skillbill.application.review.snapshot.diffForChanges
import skillbill.application.review.snapshot.harnessRequest
import skillbill.application.review.snapshot.reviewHarness
import skillbill.application.review.snapshot.reviewLayer
import skillbill.application.review.snapshot.reviewPack
import skillbill.application.review.snapshot.reviewed
import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.ports.review.preparation.ReviewRubricResolver
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.scaffold.model.PlatformManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParallelReviewCrossRootLanePlanTest {
  private val kotlinAreas = listOf("architecture", "security", "testing")
  private val kmpAreas = listOf("architecture", "platform-correctness")

  private val kotlin = reviewPack("kotlin", kotlinAreas, routingSignals = listOf("*.kt"))
  private val kmp =
    reviewPack(
      "kmp",
      kmpAreas,
      layers = listOf(reviewLayer("kotlin")),
      routingSignals = listOf("commonMain"),
      contentSignals = listOf("expect"),
    )
  private val manifests = listOf(kotlin, kmp)

  private val crossRootDiff =
    diffForChanges(
      "src/commonMain/kotlin/App.kt" to "expect fun platformName(): String",
      "src/main/kotlin/Repo.kt" to "class Repo",
    )

  @Test fun `a cross-root plan carries one lane per area owned by the nearest composing pack`() {
    val lanes = run(manifests, crossRootDiff).durableLanes

    assertEquals(
      lanes.map { it.area }.distinct().size,
      lanes.size,
      "A routed root brought along as a composition baseline must not add a second lane for an area.",
    )
    assertEquals(
      ReviewLaunchPlanPolicy
        .flatten("kmp", manifests, ReviewLaunchPlanPolicy.composedAreas("kmp", manifests))
        .lanes.associate { it.area to it.packSlug },
      lanes.associate { it.area to it.packSlug },
      "The composing root's own flattened plan is the oracle for cross-root area ownership.",
    )
    assertEquals(listOf(0, 1, 2, 3), lanes.sortedBy { it.orderIndex }.map { it.orderIndex })
  }

  @Test fun `dropping a cross-root duplicate leaves area coverage and lane accounting untouched`() {
    val withDuplicate = run(manifests, crossRootDiff)
    val kotlinWithoutOverlap = kotlin.copy(declaredCodeReviewAreas = listOf("security", "testing"))
    val withoutDuplicate = run(listOf(kotlinWithoutOverlap, kmp), crossRootDiff)

    assertEquals(
      (kotlinAreas + kmpAreas).toSet(),
      withDuplicate.durableLanes.map { it.area }.toSet(),
      "Reconciliation removes a duplicate lane, never the last lane for an area.",
    )
    assertEquals(
      withoutDuplicate.durableLanes.associate { it.area to it.reviewDisposition.wireValue },
      withDuplicate.durableLanes.associate { it.area to it.reviewDisposition.wireValue },
      "A lane dropped as a cross-root duplicate must not reduce any lane disposition.",
    )
    assertTrue(
      withDuplicate.durableLanes.all { it.unreviewedSegmentIds.isEmpty() },
      "A dropped duplicate is not unreviewed coverage.",
    )
    assertEquals(
      assertNotNull(withoutDuplicate.durableIntegrationPass).terminalOutcome,
      assertNotNull(withDuplicate.durableIntegrationPass).terminalOutcome,
    )
  }

  @Test fun `Python fixtures review separately from composed Kotlin source with the same area names`() {
    val python = reviewPack("python", listOf("architecture", "code-quality"), routingSignals = listOf("*.py"))
    val withQuality = reviewPack("kotlin", kotlinAreas + "code-quality", routingSignals = listOf("*.kt"))
    val recorder =
      run(
        listOf(withQuality, kmp, python),
        diffForChanges(
          "src/commonMain/kotlin/App.kt" to "expect fun platformName(): String",
          "src/main/kotlin/Repo.kt" to "class ExpectedState",
          "src/test/resources/capture.py" to "def capture(): return {}",
        ),
      )
    assertEquals(
      setOf("kmp", "python"),
      recorder.durableLanes.filter {
        it.area == "architecture"
      }.map { it.packSlug }.toSet(),
    )
    assertEquals(
      setOf("kotlin", "python"),
      recorder.durableLanes.filter {
        it.area == "code-quality"
      }.map { it.packSlug }.toSet(),
    )
    assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), recorder.durableLanes.sortedBy { it.orderIndex }.map { it.orderIndex })
    assertTrue(recorder.durableLanes.all { it.unreviewedSegmentIds.isEmpty() })
    assertNotNull(recorder.durableIntegrationPass)

    val generic = reviewPack("generic", listOf("code-quality"), fallback = true)
    val installed = listOf(withQuality, kmp, python, generic)
    val evidence =
      assertNotNull(ReviewDiffEvidence.parseAttributable(diffForChanges("docs/repair.md" to "repair notes")))
    val planner =
      ParallelCodeReviewRunnerRubricPlanning(
        ReviewRubricResolver { ResolvedReviewRubric("review", "review rubric") },
        InstalledPlatformPackCatalogPort { installed },
        NoopRuntimeDiagnostics,
      )
    val followUp =
      planner.resolvePlannedRubrics(
        evidence,
        ParallelCodeReviewStackDetection(listOf(generic), installed, mapOf("generic" to setOf("docs/repair.md"))),
        acceptedLanes = recorder.durableLanes,
        qualityOnly = true,
      )
    assertEquals(setOf("kotlin", "python"), followUp.map { it.descriptor.packSlug }.toSet())
    assertTrue(followUp.all { it.descriptor.ownedPaths == listOf("docs/repair.md") })

    val mixedEvidence =
      assertNotNull(
        ReviewDiffEvidence.parseAttributable(
          diffForChanges(
            "src/main/kotlin/Repo.kt" to "class ExpectedState",
            "src/test/resources/capture.py" to "def capture(): return {}",
          ),
        ),
      )
    val introducedPack =
      planner.resolvePlannedRubrics(
        mixedEvidence,
        ParallelCodeReviewStackDetection(emptyList(), installed, emptyMap()),
        acceptedLanes = recorder.durableLanes.filter { it.area == "code-quality" && it.packSlug == "kotlin" },
        qualityOnly = true,
      )
    assertEquals(setOf("kotlin", "python"), introducedPack.map { it.descriptor.packSlug }.toSet())
  }

  @Test fun `conflicting owners use generic review and log errors while preserving uncontested lanes`() {
    val first =
      reviewPack(
        "first",
        listOf("architecture", "code-quality"),
        layers = listOf(reviewLayer("kotlin")),
        routingSignals = listOf("*.first"),
      )
    val second =
      reviewPack(
        "second",
        listOf("architecture", "code-quality"),
        layers = listOf(reviewLayer("kotlin")),
        routingSignals = listOf("*.second"),
      )
    val recorder = ReviewRecorder()
    val diff =
      diffForChanges(
        "src/App.first" to "first source",
        "src/App.second" to "second source",
      )

    val generic = reviewPack("generic", listOf("architecture", "code-quality"), fallback = true)
    val result =
      reviewHarness(ReviewHarnessConfig(manifests = listOf(kotlin, first, second, generic), diff = diff), recorder)
        .reviewed(
          harnessRequest(reviewRunId = "cross-root-ambiguous", codeReviewMode = CodeReviewExecutionMode.DELEGATED),
        )
    assertTrue(result.lane1.success, result.lane1.failureReason.orEmpty())
    assertEquals(
      setOf("generic"),
      recorder.durableLanes.filter { it.area == "architecture" }.map { it.packSlug }.toSet(),
    )
    assertEquals(
      setOf("generic"),
      recorder.durableLanes.filter { it.area == "code-quality" }.map { it.packSlug }.toSet(),
    )
    assertEquals("kotlin", recorder.durableLanes.single { it.area == "security" }.packSlug)
    assertTrue(recorder.durableLanes.all { it.unreviewedSegmentIds.isEmpty() })
    assertNotNull(recorder.durableIntegrationPass)
    assertEquals(2, recorder.diagnosticErrors.size)
    assertTrue(
      recorder.diagnosticErrors.all {
        listOf("AMBIGUOUS_LANE_OWNERSHIP", "first", "second", "value_used=generic").all { value -> value in it }
      },
    )
  }

  @Test
  fun `inline review includes scoped Kotlin and Python quality rubrics without launching workers`() {
    val kotlin = reviewPack("kotlin", listOf("architecture", "code-quality"), routingSignals = listOf("*.kt"))
    val python = reviewPack("python", listOf("architecture", "code-quality"), routingSignals = listOf("*.py"))
    val recorder = ReviewRecorder()
    val runner =
      reviewHarness(
        ReviewHarnessConfig(
          manifests = listOf(kotlin, python),
          diff =
            diffForChanges(
              "src/ExpectedState.kt" to "class ExpectedState(val actualOwner: String)",
              "src/test/resources/capture.py" to "def capture(): return {}",
            ),
          rubricBody = { "Rubric for $it" },
        ),
        recorder,
      )

    val prompt =
      assertNotNull(
        assertIs<ParallelCodeReviewPlanned.Ready<String?>>(
          runner.resolveInlineCodeQualityRubric(harnessRequest(codeReviewMode = CodeReviewExecutionMode.INLINE)),
        ).value,
      )
    assertEquals(
      "Selected code-quality specialist: bill-kotlin-code-review-code-quality\n" +
        "Apply this rubric to these changed paths:\n- src/ExpectedState.kt\n\n" +
        "Rubric for bill-kotlin-code-review-code-quality\n\n" +
        "Selected code-quality specialist: bill-python-code-review-code-quality\n" +
        "Apply this rubric to these changed paths:\n- src/test/resources/capture.py\n\n" +
        "Rubric for bill-python-code-review-code-quality",
      prompt,
    )
    assertTrue(recorder.parentLaunches.isEmpty())
    assertTrue(recorder.durableLanes.isEmpty())
  }

  private fun run(
    packs: List<PlatformManifest>,
    diff: String,
  ): ReviewRecorder {
    val recorder = ReviewRecorder()
    reviewHarness(ReviewHarnessConfig(manifests = packs, diff = diff), recorder)
      .reviewed(
        harnessRequest(reviewRunId = "cross-root-lane-plan", codeReviewMode = CodeReviewExecutionMode.DELEGATED),
      )
    return recorder
  }

  @Test
  fun `tied stack discovery logs an error and inline review uses the generic rubric`() {
    val first = reviewPack("first", listOf("code-quality"), routingSignals = listOf("*.shared"))
    val second = reviewPack("second", listOf("code-quality"), routingSignals = listOf("*.shared"))
    val generic = reviewPack("generic", listOf("code-quality"), fallback = true)
    val recorder = ReviewRecorder()
    val runner =
      reviewHarness(
        ReviewHarnessConfig(listOf(first, second, generic), diffForChanges("src/App.shared" to "source")),
        recorder,
      )

    val prompt =
      assertNotNull(
        assertIs<ParallelCodeReviewPlanned.Ready<String?>>(
          runner.resolveInlineCodeQualityRubric(harnessRequest(codeReviewMode = CodeReviewExecutionMode.INLINE)),
        ).value,
      )
    assertTrue("bill-generic-code-review-code-quality" in prompt && "src/App.shared" in prompt)
    assertTrue(
      listOf("review_stack_routing", "first", "second", "value_used=generic").all {
        it in recorder.diagnosticErrors.single()
      },
    )
    assertTrue(recorder.parentLaunches.isEmpty())
  }

  @Test
  fun `inline review falls back for conflicting routed owners and records routing errors`() {
    val first =
      reviewPack(
        "first",
        listOf("architecture", "code-quality"),
        layers = listOf(reviewLayer("kotlin")),
        routingSignals = listOf("*.first"),
      )
    val second =
      reviewPack(
        "second",
        listOf("architecture", "code-quality"),
        layers = listOf(reviewLayer("kotlin")),
        routingSignals = listOf("*.second"),
      )
    val generic = reviewPack("generic", listOf("architecture", "code-quality"), fallback = true)
    val recorder = ReviewRecorder()
    val runner =
      reviewHarness(
        ReviewHarnessConfig(
          manifests = listOf(kotlin, first, second, generic),
          diff = diffForChanges("src/App.first" to "first source", "src/App.second" to "second source"),
        ),
        recorder,
      )

    val prompt =
      assertNotNull(
        assertIs<ParallelCodeReviewPlanned.Ready<String?>>(
          runner.resolveInlineCodeQualityRubric(harnessRequest(codeReviewMode = CodeReviewExecutionMode.INLINE)),
        ).value,
      )
    assertTrue("bill-generic-code-review-code-quality" in prompt)
    assertTrue("src/App.first" in prompt && "src/App.second" in prompt)
    assertEquals(2, recorder.diagnosticErrors.size)
    assertTrue(
      recorder.diagnosticErrors.all {
        listOf("AMBIGUOUS_LANE_OWNERSHIP", "first", "second", "value_used=generic").all { value -> value in it }
      },
    )
    assertTrue(recorder.parentLaunches.isEmpty())
  }
}
