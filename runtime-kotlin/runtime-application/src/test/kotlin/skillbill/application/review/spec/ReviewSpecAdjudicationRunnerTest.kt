package skillbill.application.review.spec

import skillbill.application.review.model.ReviewDelegatedStageLaunch
import skillbill.application.review.model.ReviewSpecAdjudicationRunRequest
import skillbill.application.review.parallel.runner.finding
import skillbill.application.runner
import skillbill.application.testHarnessClock
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.commit.ReviewCommitCoverageFact
import skillbill.review.context.model.commit.ReviewCommitLaneDecision
import skillbill.review.context.model.commit.ReviewCommitLaneDisposition
import skillbill.review.context.model.commit.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.commit.ReviewCommitSource
import skillbill.review.context.model.commit.ReviewCommitUnit
import skillbill.review.context.model.commit.ReviewLaneDecision
import skillbill.review.context.model.execution.SpecIntentProjection
import skillbill.review.context.model.execution.SpecIntentProvenance
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.hunk.ReviewRevision
import skillbill.review.context.model.launch.ReviewSpecAdjudicationAdmission
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustmentDirection
import skillbill.review.model.ReviewStage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ReviewSpecAdjudicationRunnerTest {
  @Test
  fun `a refuted finding is not adjudicated`() {
    val launches = mutableListOf<GoalRunnerSubtaskLaunchRequest>()
    val outcome =
      runAdjudication(
        launcher = { request ->
          launches += request
          facts(request, IN_SCOPE)
        },
        fixture =
          AdjudicationRequestFixture(
            findings = listOf(finding("F-001"), finding("F-002", "src/B.kt:4", "other bug")),
            existingVerdicts =
              listOf(
                stage1("F-001", ReviewClaimVerdict.REFUTED),
                stage1("F-002", ReviewClaimVerdict.CONFIRMED),
              ),
            projection = projection(),
            budget = ReviewContextBudgetPolicy.DEFAULT,
            repoRoot = Files.createTempDirectory("adj-refuted"),
          ),
      )
    assertEquals(1, launches.size)
    val prompt = launches.single().skillRunRequest.promptOverride.orEmpty()
    assertTrue("F-002" in prompt)
    assertFalse("F-001" in prompt)
    assertEquals(listOf("F-002"), outcome.verdicts.map { it.findingRef })
  }

  @Test
  fun `no projection skips the stage and the run completes`() {
    val launches = mutableListOf<GoalRunnerSubtaskLaunchRequest>()
    val outcome =
      runAdjudication(
        launcher = { request ->
          launches += request
          facts(request, IN_SCOPE)
        },
        fixture =
          AdjudicationRequestFixture(
            findings = listOf(finding("F-001")),
            existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
            projection = null,
            includeProjection = false,
            budget = ReviewContextBudgetPolicy.DEFAULT,
            repoRoot = Files.createTempDirectory("adj-none"),
          ),
      )
    assertTrue(launches.isEmpty())
    assertTrue(outcome.verdicts.isEmpty())
    assertEquals(ReviewSpecAdjudicationRunner.SPEC_CONTEXT_NONE, outcome.skipReason)
  }

  @Test
  fun `an uncited downgrade is recorded in_scope and the finding survives`() {
    val claim = finding("F-001")
    val outcome =
      runAdjudication(
        launcher = { request -> facts(request, UNCITED_DOWNGRADE) },
        fixture =
          AdjudicationRequestFixture(
            findings = listOf(claim),
            existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
            projection = projection(),
            budget = ReviewContextBudgetPolicy.DEFAULT,
            repoRoot = Files.createTempDirectory("adj-uncited"),
          ),
      )
    val verdict = outcome.verdicts.single()
    assertEquals(ReviewScopeDisposition.IN_SCOPE, verdict.scopeDisposition)
    assertEquals(ReviewSpecAdjudicationAdmission.UNCITED_DOWNGRADE, verdict.rejectionReason)
    assertNull(verdict.severityAdjustment)
    assertEquals(ParallelReviewSeverity.MAJOR, claim.severity)
    assertEquals(ReviewClaimVerdict.CONFIRMED, verdict.claimVerdict)
  }

  @Test
  fun `an upward adjustment uses the same delta structure as a downward one`() {
    val raise =
      runAdjudication(
        launcher = { request -> facts(request, RAISE) },
        fixture =
          AdjudicationRequestFixture(
            findings = listOf(finding("F-001")),
            existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
            projection = projection(),
            budget = ReviewContextBudgetPolicy.DEFAULT,
            repoRoot = Files.createTempDirectory("adj-raise"),
          ),
      ).verdicts.single()
    val lower =
      runAdjudication(
        launcher = { request -> facts(request, LOWER) },
        fixture =
          AdjudicationRequestFixture(
            findings = listOf(finding("F-001")),
            existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
            projection = projection(),
            budget = ReviewContextBudgetPolicy.DEFAULT,
            repoRoot = Files.createTempDirectory("adj-lower"),
          ),
      ).verdicts.single()
    assertEquals(ReviewScopeDisposition.SPEC_DEVIATION, raise.scopeDisposition)
    assertEquals(ReviewSeverityAdjustmentDirection.RAISE, raise.severityAdjustment?.direction)
    assertEquals(ReviewSeverityAdjustmentDirection.LOWER, lower.severityAdjustment?.direction)
    assertTrue(raise.citations.isNotEmpty())
    assertTrue(lower.citations.isNotEmpty())
    assertEquals(raise.citations, lower.citations)
    assertTrue(!raise.severityAdjustment!!.justification.isBlank())
    assertTrue(!lower.severityAdjustment!!.justification.isBlank())
  }

  @Test
  fun `the original claim is unmodified after a severity adjustment`() {
    val claim = finding("F-001")
    val before = claim.copy()
    val verdict =
      runAdjudication(
        launcher = { request -> facts(request, RAISE) },
        fixture =
          AdjudicationRequestFixture(
            findings = listOf(claim),
            existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
            projection = projection(),
            budget = ReviewContextBudgetPolicy.DEFAULT,
            repoRoot = Files.createTempDirectory("adj-preserve"),
          ),
      ).verdicts.single()
    assertEquals(before, claim)
    assertEquals(ParallelReviewSeverity.MAJOR, claim.severity)
    assertEquals("src/A.kt:12", claim.location)
    assertEquals("Null is not checked.", claim.description)
    assertEquals(ReviewSeverityAdjustmentDirection.RAISE, verdict.severityAdjustment?.direction)
  }

  @Test
  fun `each surviving finding launches alone without sibling finding text`() {
    val launches = mutableListOf<GoalRunnerSubtaskLaunchRequest>()
    val envelopes = mutableListOf<Map<String, Any?>>()
    val outcome =
      runAdjudication(
        launcher = { request ->
          launches += request
          facts(request, IN_SCOPE)
        },
        fixture =
          AdjudicationRequestFixture(
            findings =
              listOf(
                finding("F-001"),
                finding("F-002", "src/B.kt:4", "second"),
                finding("F-003", "src/C.kt:8", "third"),
              ),
            existingVerdicts =
              listOf(
                stage1("F-001", ReviewClaimVerdict.CONFIRMED),
                stage1("F-002", ReviewClaimVerdict.UNRESOLVED),
                stage1("F-003", ReviewClaimVerdict.CONFIRMED),
              ),
            projection = projection(),
            budget = ReviewContextBudgetPolicy.DEFAULT,
            repoRoot = Files.createTempDirectory("adj-isolate"),
          ),
        validator = { envelope, _ -> envelopes += envelope },
      )
    assertEquals(3, launches.size)
    assertEquals(3, envelopes.size)
    val refs = envelopes.map { (it["finding"] as Map<*, *>)["finding_ref"] as String }
    assertEquals(listOf("F-001", "F-002", "F-003"), refs)
    envelopes.forEach { envelope ->
      assertEquals("adjudication_launch", envelope["kind"])
    }
    launches.forEachIndexed { index, launch ->
      val prompt = launch.skillRunRequest.promptOverride.orEmpty()
      val own = refs[index]
      refs.filterNot { it == own }.forEach { sibling ->
        assertFalse(sibling in prompt)
      }
    }
    assertEquals(listOf("F-001", "F-002", "F-003"), outcome.verdicts.map { it.findingRef })
  }

  @Test
  fun `an over-budget adjudication launch records the listed reason and does not launch`() {
    val launches = mutableListOf<GoalRunnerSubtaskLaunchRequest>()
    val outcome =
      runAdjudication(
        launcher = { request ->
          launches += request
          facts(request, IN_SCOPE)
        },
        fixture =
          AdjudicationRequestFixture(
            findings = listOf(finding("F-001")),
            existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
            projection = projection(),
            budget = ReviewContextBudgetPolicy.DEFAULT.copy(maxLaneLaunchBytes = 64),
            repoRoot = Files.createTempDirectory("adj-budget"),
          ),
      )
    assertTrue(launches.isEmpty())
    val verdict = outcome.verdicts.single()
    assertEquals("adjudication launch exceeded max_lane_launch_bytes", verdict.rejectionReason)
    assertEquals(ReviewScopeDisposition.IN_SCOPE, verdict.scopeDisposition)
    assertEquals(ReviewClaimVerdict.CONFIRMED, verdict.claimVerdict)
  }

  @Test
  fun `unparseable adjudication stdout records the unparseable reason instead of admitting a null worker`() {
    val verdict =
      runAdjudication(
        launcher = { request -> facts(request, "not-json") },
        fixture =
          AdjudicationRequestFixture(
            findings = listOf(finding("F-001")),
            existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
            projection = projection(),
            budget = ReviewContextBudgetPolicy.DEFAULT,
            repoRoot = Files.createTempDirectory("adj-unparseable"),
          ),
      ).verdicts.single()
    assertEquals("unparseable adjudication output", verdict.rejectionReason)
    assertEquals(ReviewScopeDisposition.IN_SCOPE, verdict.scopeDisposition)
    assertEquals(ReviewClaimVerdict.CONFIRMED, verdict.claimVerdict)
  }

  @Test
  fun `a result carrying two dispositions or none is recorded in_scope with the rejection reason`() {
    val none =
      runAdjudication(
        launcher = { request -> facts(request, "{}") },
        fixture =
          AdjudicationRequestFixture(
            findings = listOf(finding("F-001")),
            existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
            projection = projection(),
            budget = ReviewContextBudgetPolicy.DEFAULT,
            repoRoot = Files.createTempDirectory("adj-none-disp"),
          ),
      ).verdicts.single()
    val two =
      runAdjudication(
        launcher = { request -> facts(request, TWO_DISPOSITIONS) },
        fixture =
          AdjudicationRequestFixture(
            findings = listOf(finding("F-001")),
            existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
            projection = projection(),
            budget = ReviewContextBudgetPolicy.DEFAULT,
            repoRoot = Files.createTempDirectory("adj-two-disp"),
          ),
      ).verdicts.single()
    assertEquals(ReviewScopeDisposition.IN_SCOPE, none.scopeDisposition)
    assertEquals(ReviewSpecAdjudicationAdmission.AMBIGUOUS, none.rejectionReason)
    assertEquals(ReviewScopeDisposition.IN_SCOPE, two.scopeDisposition)
    assertEquals(ReviewSpecAdjudicationAdmission.AMBIGUOUS, two.rejectionReason)
  }

  @Test
  fun `zero adjudication citation lines are coerced without diagnostics`() {
    val stdout =
      """{"scope_disposition":"in_scope","citations":[{"path":"spec.md","line":1},{"path":"spec.md","line":0}]}"""
    val outcome =
      runAdjudication(
        launcher = { request -> facts(request, stdout) },
        fixture =
          AdjudicationRequestFixture(
            findings = listOf(finding("F-001")),
            existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
            projection = projection(),
            budget = ReviewContextBudgetPolicy.DEFAULT,
            repoRoot = Files.createTempDirectory("adj-malformed-citation"),
          ),
      )
    assertEquals(ReviewScopeDisposition.IN_SCOPE, outcome.verdicts.single().scopeDisposition)
    assertEquals(
      listOf(1, 1),
      outcome.verdicts.single().citations.map { it.line },
    )
    assertTrue(outcome.citationDiagnostics.isEmpty())
  }

  @Test
  fun `spec_deviation citing an element absent from the projection is not admitted`() {
    val verdict =
      runAdjudication(
        launcher = { request -> facts(request, INVENTED_DEVIATION) },
        fixture =
          AdjudicationRequestFixture(
            findings = listOf(finding("F-001")),
            existingVerdicts = listOf(stage1("F-001", ReviewClaimVerdict.CONFIRMED)),
            projection = projection(),
            budget = ReviewContextBudgetPolicy.DEFAULT,
            repoRoot = Files.createTempDirectory("adj-invented"),
          ),
      ).verdicts.single()
    assertEquals(ReviewScopeDisposition.IN_SCOPE, verdict.scopeDisposition)
    assertEquals(ReviewSpecAdjudicationAdmission.SPEC_DEVIATION_NOT_CONSTRAINT, verdict.rejectionReason)
  }

  private data class AdjudicationRequestFixture(
    val findings: List<ParallelReviewMergedFinding>,
    val existingVerdicts: List<ReviewFindingVerdict>,
    val projection: SpecIntentProjection? = null,
    val includeProjection: Boolean = true,
    val packet: ReviewContextPacket? = null,
    val budget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
    val repoRoot: Path? = null,
  )

  private fun runAdjudication(
    launcher: GoalRunnerSubtaskLauncher,
    fixture: AdjudicationRequestFixture,
    validator: ReviewContextEnvelopeValidator = ReviewContextEnvelopeValidator { _, _ -> },
  ) = runner(launcher, validator).run(adjudicationRequest(fixture))

  private fun adjudicationRequest(fixture: AdjudicationRequestFixture) =
    ReviewSpecAdjudicationRunRequest(
      packet = fixture.packet ?: packet(),
      findings = fixture.findings,
      existingVerdicts = fixture.existingVerdicts,
      projection = if (fixture.includeProjection) fixture.projection ?: projection() else null,
      launch =
        ReviewDelegatedStageLaunch(
          budget = fixture.budget,
          brokerId = "codex",
          repoRoot = fixture.repoRoot ?: Files.createTempDirectory("adj"),
          timeout = 1.seconds,
        ),
    )

  private fun runner(
    launcher: GoalRunnerSubtaskLauncher,
    validator: ReviewContextEnvelopeValidator = ReviewContextEnvelopeValidator { _, _ -> },
  ) = ReviewSpecAdjudicationRunner(launcher, validator, testHarnessClock)

  private fun facts(
    request: GoalRunnerSubtaskLaunchRequest,
    stdout: String,
  ) = agentRunLaunchFacts(
    agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
    stdout = stdout,
  )

  private fun finding(
    ref: String,
    location: String = "src/A.kt:12",
    description: String = "Null is not checked.",
  ) = ParallelReviewMergedFinding(
    fNumber = ref,
    agentIds = listOf("codex"),
    severity = ParallelReviewSeverity.MAJOR,
    confidence = "High",
    location = location,
    description = description,
    repositoryPath = location.substringBefore(':'),
    line = location.substringAfter(':').toInt(),
  )

  private fun stage1(
    ref: String,
    verdict: ReviewClaimVerdict,
  ) = ReviewFindingVerdict(
    stage = ReviewStage.VERIFICATION,
    findingRef = ref,
    claimVerdict = verdict,
    recordedAt = "2026-08-14T08:00:00Z",
  )

  private fun projection() =
    SpecIntentProjection(
      intendedOutcome = "Ship the adjudication stage.",
      acceptanceCriteria = listOf("Stage two runs."),
      constraints = listOf(CONSTRAINT),
      nonGoals = listOf("Re-testing claims."),
      deferredItems = listOf("Register assembly."),
      provenance = SpecIntentProvenance("spec.md", "a".repeat(64)),
      declaredByteBudget = 4096,
    )

  private fun packet(): ReviewContextPacket {
    val hunk = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")
    val lanes = listOf("security")
    return ReviewContextPacket(
      reviewId = "review",
      repositoryIdentity = "repo",
      baseRevision = "base",
      headRevision = "head",
      status = "clean",
      stack = "kotlin",
      pack = "kotlin",
      addOns = emptyList(),
      selectedLanes = lanes,
      changedHunks = listOf(hunk),
      commitUnits =
        listOf(
          ReviewCommitUnit("head", "base", "change", 0, listOf(hunk), ReviewCommitSource.COMMIT_RANGE),
        ),
      coverageFact = ReviewCommitCoverageFact("base", "head", 1, chainVerified = true, pathCoverageVerified = true),
      routingMatrix =
        ReviewCommitLaneRoutingMatrix(
          listOf("head"),
          lanes,
          listOf(ReviewCommitLaneDecision("head", 0, "security", ReviewCommitLaneDisposition.FOCUSED, "focused")),
        ),
      reviewRevision = ReviewRevision("rvs-1", 1),
      laneDecisions =
        listOf(
          ReviewLaneDecision(
            "security",
            true,
            "routed",
            ownedPaths = listOf("src/A.kt"),
            originLayerChains = listOf(listOf("kotlin")),
            owningPack = "kotlin",
            specialistSkillName = "bill-kotlin-code-review-security",
          ),
        ),
      dependencyAllowlist = ReviewDependencyAllowlist(listOf("src/Dep.kt")),
    )
  }

  private companion object {
    const val CONSTRAINT: String = "Do not launch workers on refuted findings."
    const val IN_SCOPE: String = """{"scope_disposition":"in_scope"}"""
    const val UNCITED_DOWNGRADE: String =
      """{"scope_disposition":"in_scope","severity_adjustment":{"direction":"lower","justification":"too noisy"}}"""
    const val RAISE: String =
      """{"scope_disposition":"spec_deviation","cited_spec_element":"$CONSTRAINT",""" +
        """"citations":[{"path":"spec.md","line":8}],"severity_adjustment":{""" +
        """"direction":"raise","adjusted_severity":"Blocker",""" +
        """"justification":"contradicts a stated constraint"}}"""
    const val LOWER: String =
      """{"scope_disposition":"spec_deviation","cited_spec_element":"$CONSTRAINT",""" +
        """"citations":[{"path":"spec.md","line":8}],"severity_adjustment":{""" +
        """"direction":"lower","adjusted_severity":"Nit",""" +
        """"justification":"listed as a non-blocking constraint"}}"""
    const val TWO_DISPOSITIONS: String = """{"scope_disposition":["in_scope","spec_deviation"]}"""
    const val INVENTED_DEVIATION: String =
      """{"scope_disposition":"spec_deviation","cited_spec_element":"Invented constraint",""" +
        """"citations":[{"path":"spec.md","line":1}]}"""
  }
}
